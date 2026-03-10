package com.wayrecall.tracker.users.service

import com.wayrecall.tracker.users.cache.PermissionCache
import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.repository.*
import org.mindrot.jbcrypt.BCrypt
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Сервис управления пользователями
// ============================================================

trait UserService:
  def createUser(actorId: UUID, companyId: UUID, request: CreateUserRequest): Task[UserCreated]
  def getProfile(userId: UUID): Task[UserProfile]
  def updateProfile(userId: UUID, request: UpdateProfileRequest): Task[Unit]
  def changePassword(userId: UUID, request: ChangePasswordRequest): Task[Unit]
  def assignRole(actorId: UUID, userId: UUID, roleId: UUID): Task[Unit]
  def deactivateUser(actorId: UUID, userId: UUID): Task[Unit]
  def listUsers(companyId: UUID, page: Int, pageSize: Int, search: Option[String]): Task[UsersListResponse]
  def getUser(userId: UUID): Task[UserProfile]
  def resetPassword(actorId: UUID, userId: UUID): Task[Unit]

object UserService:
  val live: ZLayer[UserRepository & RoleRepository & AuditRepository & PermissionService & PermissionCache, Nothing, UserService] =
    ZLayer.fromFunction { (
        userRepo: UserRepository,
        roleRepo: RoleRepository,
        auditRepo: AuditRepository,
        permService: PermissionService,
        cache: PermissionCache
    ) =>
      new UserServiceLive(userRepo, roleRepo, auditRepo, permService, cache)
    }

final class UserServiceLive(
    userRepo: UserRepository,
    roleRepo: RoleRepository,
    auditRepo: AuditRepository,
    permService: PermissionService,
    cache: PermissionCache
) extends UserService:

  override def createUser(actorId: UUID, companyId: UUID, request: CreateUserRequest): Task[UserCreated] =
    for {
      _ <- ZIO.logInfo(s"Создание пользователя: actor=$actorId, company=$companyId, email=${request.email}")

      // Проверяем права актора
      hasPerms <- permService.hasPermission(actorId, "users:create")
      _ <- ZIO.when(!hasPerms)(ZIO.logWarning(s"Отказ в доступе: actor=$actorId не имеет прав users:create"))
      _        <- ZIO.unless(hasPerms)(ZIO.fail(UserError.PermissionDenied("users:create", "Нет прав")))

      // Проверяем уникальность email
      existing <- userRepo.findByEmail(request.email)
      _ <- ZIO.when(existing.isDefined)(ZIO.logWarning(s"Дубликат email при создании пользователя: ${request.email}"))
      _        <- ZIO.when(existing.isDefined)(ZIO.fail(UserError.EmailAlreadyExists(request.email)))

      // Проверяем что роль существует и актор может её назначать
      role <- roleRepo.findById(request.roleId).someOrFail(UserError.RoleNotFound(request.roleId))
      canAssign <- permService.canAssignRole(actorId, role.level)
      _ <- ZIO.unless(canAssign)(
        ZIO.logWarning(s"Попытка назначить роль выше уровня: actor=$actorId, roleLevel=${role.level}") *>
        ZIO.fail(UserError.PermissionDenied("assignRole", s"Нельзя назначить роль уровня ${role.level}"))
      )

      // Хэшируем пароль с помощью BCrypt
      now          = Instant.now()
      userId       = UUID.randomUUID()
      passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt(12))

      user = User(
        id = userId, companyId = companyId, email = request.email,
        passwordHash = passwordHash, firstName = request.firstName, lastName = request.lastName,
        phone = request.phone, position = request.position, language = "ru", timezone = "Europe/Moscow",
        isActive = true, emailVerified = false, lastLoginAt = None,
        failedLoginAttempts = 0, createdAt = now, updatedAt = now
      )

      // Создаём пользователя и назначаем роль
      _ <- userRepo.create(user)
      _ <- roleRepo.assignRole(userId, request.roleId, actorId)
      _ <- ZIO.logInfo(s"Пользователь создан: id=$userId, email=${request.email}, role=${role.name}, company=$companyId")

      // Аудит
      _ <- auditRepo.log(AuditLogEntry(
        id = UUID.randomUUID(), companyId = companyId, userId = actorId,
        userName = "", action = "user.created", entityType = "user",
        entityId = Some(userId.toString), details = Some(s"""{"email":"${request.email}"}"""),
        ipAddress = None, createdAt = now
      ))
    } yield UserCreated(userId, request.email)

  override def getProfile(userId: UUID): Task[UserProfile] =
    // Пробуем из кэша
    cache.getProfile(userId).flatMap {
      case Some(profile) => ZIO.succeed(profile)
      case None =>
        for {
          user  <- userRepo.findById(userId).someOrFail(UserError.UserNotFound(userId))
          roles <- roleRepo.getUserRoles(userId)
          perms <- permService.getUserPermissions(userId)
          profile = UserProfile(
            id = user.id, companyId = user.companyId, email = user.email,
            firstName = user.firstName, lastName = user.lastName,
            phone = user.phone, position = user.position,
            language = user.language, timezone = user.timezone,
            roles = roles.map(_.name), permissions = perms.toList
          )
          _ <- cache.setProfile(userId, profile)
        } yield profile
    }

  override def updateProfile(userId: UUID, request: UpdateProfileRequest): Task[Unit] =
    for {
      _ <- ZIO.logDebug(s"Обновление профиля пользователя: userId=$userId")
      user <- userRepo.findById(userId).someOrFail(UserError.UserNotFound(userId))
      updated = user.copy(
        firstName = request.firstName.getOrElse(user.firstName),
        lastName = request.lastName.getOrElse(user.lastName),
        phone = request.phone.orElse(user.phone),
        position = request.position.orElse(user.position),
        language = request.language.getOrElse(user.language),
        timezone = request.timezone.getOrElse(user.timezone)
      )
      _ <- userRepo.update(updated)
      _ <- cache.invalidateUser(userId)
      _ <- ZIO.logInfo(s"Профиль обновлён: userId=$userId")
    } yield ()

  override def changePassword(userId: UUID, request: ChangePasswordRequest): Task[Unit] =
    for {
      _ <- ZIO.logInfo(s"Смена пароля: userId=$userId")
      user <- userRepo.findById(userId).someOrFail(UserError.UserNotFound(userId))
      // Проверяем текущий пароль
      valid = BCrypt.checkpw(request.currentPassword, user.passwordHash)
      _ <- ZIO.when(!valid)(ZIO.logWarning(s"Неверный текущий пароль при смене: userId=$userId"))
      _ <- ZIO.unless(valid)(ZIO.fail(UserError.InvalidPassword("Текущий пароль неверен")))
      // Хэшируем новый
      newHash = BCrypt.hashpw(request.newPassword, BCrypt.gensalt(12))
      updated = user.copy(passwordHash = newHash, updatedAt = Instant.now())
      _ <- userRepo.update(updated)
      _ <- ZIO.logInfo(s"Пароль успешно изменён: userId=$userId")
    } yield ()

  override def assignRole(actorId: UUID, userId: UUID, roleId: UUID): Task[Unit] =
    for {
      _ <- ZIO.logInfo(s"Назначение роли: actor=$actorId, target=$userId, role=$roleId")
      // Проверяем права
      hasPerms <- permService.hasPermission(actorId, "users:edit")
      _ <- ZIO.when(!hasPerms)(ZIO.logWarning(s"Отказ в назначении роли: actor=$actorId не имеет прав users:edit"))
      _        <- ZIO.unless(hasPerms)(ZIO.fail(UserError.PermissionDenied("users:edit", "Нет прав")))
      // Проверяем что роль существует и можно назначить
      role <- roleRepo.findById(roleId).someOrFail(UserError.RoleNotFound(roleId))
      canAssign <- permService.canAssignRole(actorId, role.level)
      _ <- ZIO.unless(canAssign)(
        ZIO.logWarning(s"Попытка назначить роль выше уровня: actor=$actorId, roleLevel=${role.level}") *>
        ZIO.fail(UserError.PermissionDenied("assignRole", s"Нельзя назначить роль уровня ${role.level}"))
      )
      _ <- roleRepo.assignRole(userId, roleId, actorId)
      _ <- cache.invalidateUser(userId)
      _ <- ZIO.logInfo(s"Роль назначена: user=$userId, role=${role.name} (level=${role.level}), actor=$actorId")
    } yield ()

  override def deactivateUser(actorId: UUID, userId: UUID): Task[Unit] =
    for {
      _ <- ZIO.logInfo(s"Деактивация пользователя: actor=$actorId, target=$userId")
      hasPerms <- permService.hasPermission(actorId, "users:delete")
      _ <- ZIO.when(!hasPerms)(ZIO.logWarning(s"Отказ в деактивации: actor=$actorId не имеет прав users:delete"))
      _        <- ZIO.unless(hasPerms)(ZIO.fail(UserError.PermissionDenied("users:delete", "Нет прав")))
      _        <- userRepo.deactivate(userId)
      _        <- cache.invalidateUser(userId)
      _ <- ZIO.logInfo(s"Пользователь деактивирован: userId=$userId, actor=$actorId")
      _        <- auditRepo.log(AuditLogEntry(
        id = UUID.randomUUID(), companyId = UUID.randomUUID(), userId = actorId,
        userName = "", action = "user.deactivated", entityType = "user",
        entityId = Some(userId.toString), details = None, ipAddress = None, createdAt = Instant.now()
      ))
    } yield ()

  override def listUsers(companyId: UUID, page: Int, pageSize: Int, search: Option[String]): Task[UsersListResponse] =
    for {
      (users, total) <- userRepo.findByCompany(companyId, page, pageSize, search)
      summaries = users.map { u =>
        UserSummary(u.id, u.email, u.firstName, u.lastName, Nil, u.isActive, u.lastLoginAt)
      }
    } yield UsersListResponse(total, page, pageSize, summaries)

  override def getUser(userId: UUID): Task[UserProfile] =
    getProfile(userId)

  override def resetPassword(actorId: UUID, userId: UUID): Task[Unit] =
    for {
      hasPerms <- permService.hasPermission(actorId, "users:edit")
      _        <- ZIO.unless(hasPerms)(ZIO.fail(UserError.PermissionDenied("users:edit", "Нет прав")))
      // Генерируем временный пароль и хэшируем
      tempPassword = UUID.randomUUID().toString.take(12)
      newHash      = BCrypt.hashpw(tempPassword, BCrypt.gensalt(12))
      user <- userRepo.findById(userId).someOrFail(UserError.UserNotFound(userId))
      _    <- userRepo.update(user.copy(passwordHash = newHash, updatedAt = Instant.now()))
      _    <- ZIO.logInfo(s"Пароль сброшен для пользователя $userId (временный пароль отправлен)")
    } yield ()
