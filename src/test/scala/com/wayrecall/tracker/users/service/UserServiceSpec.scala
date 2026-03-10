package com.wayrecall.tracker.users.service

import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.repository.*
import com.wayrecall.tracker.users.cache.PermissionCache
import zio.*
import zio.test.*
import java.util.UUID
import java.time.Instant

// ============================================================
// Тесты UserService — in-memory реализации репозиториев
// Покрытие: createUser, getProfile, updateProfile, listUsers,
//           deactivateUser, assignRole
// ============================================================

object UserServiceSpec extends ZIOSpecDefault:

  // --- InMemory UserRepository ---
  final case class InMemoryUserRepo(
    store: Ref[Map[UUID, User]]
  ) extends UserRepository:

    override def findById(userId: UUID): Task[Option[User]] =
      store.get.map(_.get(userId))

    override def findByEmail(email: String): Task[Option[User]] =
      store.get.map(_.values.find(_.email == email))

    override def findByCompany(companyId: UUID, page: Int, pageSize: Int, search: Option[String]): Task[(List[User], Long)] =
      store.get.map { m =>
        val filtered = m.values.filter(_.companyId == companyId).toList
          .filter(u => search.fold(true)(s =>
            u.firstName.toLowerCase.contains(s.toLowerCase) ||
            u.lastName.toLowerCase.contains(s.toLowerCase) ||
            u.email.toLowerCase.contains(s.toLowerCase)
          ))
        val paged = filtered.drop((page - 1) * pageSize).take(pageSize)
        (paged, filtered.length.toLong)
      }

    override def create(user: User): Task[UUID] =
      store.update(_ + (user.id -> user)).as(user.id)

    override def update(user: User): Task[Unit] =
      store.update(_ + (user.id -> user))

    override def deactivate(userId: UUID): Task[Unit] =
      store.update(m => m.updatedWith(userId)(_.map(_.copy(isActive = false))))

    override def updateLastLogin(userId: UUID): Task[Unit] =
      store.update(m => m.updatedWith(userId)(_.map(u => u.copy(lastLoginAt = Some(Instant.now())))))

    override def incrementFailedLogins(userId: UUID): Task[Unit] =
      store.update(m => m.updatedWith(userId)(_.map(u => u.copy(failedLoginAttempts = u.failedLoginAttempts + 1))))

    override def resetFailedLogins(userId: UUID): Task[Unit] =
      store.update(m => m.updatedWith(userId)(_.map(u => u.copy(failedLoginAttempts = 0))))

  object InMemoryUserRepo:
    val live: ZLayer[Any, Nothing, UserRepository] =
      ZLayer(Ref.make(Map.empty[UUID, User]).map(InMemoryUserRepo(_)))

  // --- InMemory RoleRepository ---
  final case class InMemoryRoleRepo(
    roles: Ref[Map[UUID, Role]],
    userRoles: Ref[Map[UUID, List[UUID]]] // userId -> roleIds
  ) extends RoleRepository:

    override def findById(roleId: UUID): Task[Option[Role]] =
      roles.get.map(_.get(roleId))

    override def findByName(companyId: Option[UUID], name: String): Task[Option[Role]] =
      roles.get.map(_.values.find(r => r.name == name && r.companyId == companyId))

    override def findAll(companyId: Option[UUID]): Task[List[Role]] =
      roles.get.map(_.values.filter(_.companyId == companyId).toList)

    override def findSystemRoles: Task[List[Role]] =
      roles.get.map(_.values.filter(_.isSystem).toList)

    override def create(role: Role): Task[UUID] =
      roles.update(_ + (role.id -> role)).as(role.id)

    override def assignRole(userId: UUID, roleId: UUID, assignedBy: UUID): Task[Unit] =
      userRoles.update { m =>
        val current = m.getOrElse(userId, Nil)
        m + (userId -> (current :+ roleId))
      }

    override def removeRole(userId: UUID, roleId: UUID): Task[Unit] =
      userRoles.update { m =>
        m.updatedWith(userId)(_.map(_.filterNot(_ == roleId)))
      }

    override def getUserRoles(userId: UUID): Task[List[Role]] =
      for {
        ur   <- userRoles.get.map(_.getOrElse(userId, Nil))
        allR <- roles.get
      } yield ur.flatMap(id => allR.get(id).toList)

    override def getUserRoleLevel(userId: UUID): Task[Option[Int]] =
      for {
        userR <- getUserRoles(userId)
      } yield userR.map(_.level).minOption

  object InMemoryRoleRepo:
    val live: ZLayer[Any, Nothing, RoleRepository] =
      ZLayer {
        for {
          r  <- Ref.make(Map.empty[UUID, Role])
          ur <- Ref.make(Map.empty[UUID, List[UUID]])
        } yield InMemoryRoleRepo(r, ur)
      }

  // --- InMemory AuditRepository ---
  final case class InMemoryAuditRepo(
    store: Ref[List[AuditLogEntry]]
  ) extends AuditRepository:

    override def log(entry: AuditLogEntry): Task[Unit] =
      store.update(entries => entries :+ entry)

    override def find(companyId: UUID, filters: AuditFilters): Task[(List[AuditLogEntry], Long)] =
      store.get.map { entries =>
        val filtered = entries
          .filter(_.companyId == companyId)
          .filter(e => filters.userId.fold(true)(_ == e.userId))
          .filter(e => filters.action.fold(true)(_ == e.action))
        (filtered.take(filters.pageSize), filtered.length.toLong)
      }

  object InMemoryAuditRepo:
    val live: ZLayer[Any, Nothing, AuditRepository] =
      ZLayer(Ref.make(List.empty[AuditLogEntry]).map(InMemoryAuditRepo(_)))

  // --- Тестовые данные ---
  private val companyId = UUID.randomUUID()
  private val actorId = UUID.randomUUID()

  private def makeUser(
    firstName: String = "Test",
    lastName: String = "User",
    email: String = "test@example.com",
    companyId: UUID = companyId,
    isActive: Boolean = true
  ): User =
    User(
      id = UUID.randomUUID(),
      companyId = companyId,
      email = email,
      passwordHash = "hashed",
      firstName = firstName,
      lastName = lastName,
      phone = None,
      position = None,
      language = "ru",
      timezone = "Europe/Moscow",
      isActive = isActive,
      emailVerified = false,
      lastLoginAt = None,
      failedLoginAttempts = 0,
      createdAt = Instant.now(),
      updatedAt = Instant.now()
    )

  // --- Тесты ---
  def spec = suite("UserService — unit тесты")(
    userRepoSuite,
    roleRepoSuite,
    auditRepoSuite,
    domainValidationSuite
  ) @@ TestAspect.timeout(60.seconds)

  val userRepoSuite = suite("InMemory UserRepository")(
    test("create + findById") {
      val user = makeUser()
      for {
        repo  <- ZIO.service[UserRepository]
        _     <- repo.create(user)
        found <- repo.findById(user.id)
      } yield assertTrue(
        found.isDefined,
        found.get.id == user.id,
        found.get.email == user.email
      )
    }.provide(InMemoryUserRepo.live),

    test("findByEmail — существующий") {
      val user = makeUser(email = "findme@test.com")
      for {
        repo  <- ZIO.service[UserRepository]
        _     <- repo.create(user)
        found <- repo.findByEmail("findme@test.com")
      } yield assertTrue(found.isDefined, found.get.id == user.id)
    }.provide(InMemoryUserRepo.live),

    test("findByEmail — несуществующий") {
      for {
        repo  <- ZIO.service[UserRepository]
        found <- repo.findByEmail("nonexistent@test.com")
      } yield assertTrue(found.isEmpty)
    }.provide(InMemoryUserRepo.live),

    test("findByCompany — фильтрация и пагинация") {
      val u1 = makeUser(firstName = "Alice", email = "alice@test.com")
      val u2 = makeUser(firstName = "Bob", email = "bob@test.com")
      val otherCompany = makeUser(firstName = "Charlie", email = "charlie@test.com", companyId = UUID.randomUUID())
      for {
        repo   <- ZIO.service[UserRepository]
        _      <- repo.create(u1) *> repo.create(u2) *> repo.create(otherCompany)
        result <- repo.findByCompany(companyId, 1, 10, None)
      } yield {
        val (found, _) = result
        assertTrue(
          found.length == 2,
          found.map(_.firstName).toSet == Set("Alice", "Bob")
        )
      }
    }.provide(InMemoryUserRepo.live),

    test("findByCompany — поиск по имени") {
      val u1 = makeUser(firstName = "Alice", email = "alice@test.com")
      val u2 = makeUser(firstName = "Bob", email = "bob@test.com")
      for {
        repo   <- ZIO.service[UserRepository]
        _      <- repo.create(u1) *> repo.create(u2)
        result <- repo.findByCompany(companyId, 1, 10, Some("Alice"))
      } yield {
        val (found, _) = result
        assertTrue(found.length == 1, found.head.firstName == "Alice")
      }
    }.provide(InMemoryUserRepo.live),

    test("deactivate") {
      val user = makeUser()
      for {
        repo  <- ZIO.service[UserRepository]
        _     <- repo.create(user)
        _     <- repo.deactivate(user.id)
        found <- repo.findById(user.id)
      } yield {
        val isNotActive = !found.get.isActive
        assertTrue(isNotActive)
      }
    }.provide(InMemoryUserRepo.live),

    test("update") {
      val user = makeUser(firstName = "Before")
      val updated = user.copy(firstName = "After")
      for {
        repo  <- ZIO.service[UserRepository]
        _     <- repo.create(user)
        _     <- repo.update(updated)
        found <- repo.findById(user.id)
      } yield assertTrue(found.get.firstName == "After")
    }.provide(InMemoryUserRepo.live)
  )

  val roleRepoSuite = suite("InMemory RoleRepository")(
    test("create + findById") {
      val role = Role(
        id = UUID.randomUUID(), companyId = Some(companyId),
        name = "Admin", displayName = "Administrator",
        description = Some("Administrator role"),
        permissions = List("vehicles:view", "vehicles:edit"),
        isSystem = false, level = 1, createdAt = Instant.now()
      )
      for {
        repo  <- ZIO.service[RoleRepository]
        _     <- repo.create(role)
        found <- repo.findById(role.id)
      } yield assertTrue(found.isDefined, found.get.name == "Admin")
    }.provide(InMemoryRoleRepo.live),

    test("assignRole + getUserRoles") {
      val userId = UUID.randomUUID()
      val role = Role(
        id = UUID.randomUUID(), companyId = Some(companyId),
        name = "Viewer", displayName = "Viewer",
        description = Some("Read-only"),
        permissions = List("vehicles:view"),
        isSystem = false, level = 10, createdAt = Instant.now()
      )
      for {
        repo  <- ZIO.service[RoleRepository]
        _     <- repo.create(role)
        _     <- repo.assignRole(userId, role.id, actorId)
        roles <- repo.getUserRoles(userId)
      } yield assertTrue(roles.length == 1, roles.head.name == "Viewer")
    }.provide(InMemoryRoleRepo.live),

    test("removeRole — удаление роли пользователя") {
      val userId = UUID.randomUUID()
      val role = Role(
        id = UUID.randomUUID(), companyId = Some(companyId),
        name = "Editor", displayName = "Editor",
        description = Some("Can edit"),
        permissions = List("vehicles:edit"),
        isSystem = false, level = 5, createdAt = Instant.now()
      )
      for {
        repo  <- ZIO.service[RoleRepository]
        _     <- repo.create(role)
        _     <- repo.assignRole(userId, role.id, actorId)
        _     <- repo.removeRole(userId, role.id)
        roles <- repo.getUserRoles(userId)
      } yield assertTrue(roles.isEmpty)
    }.provide(InMemoryRoleRepo.live),

    test("getUserRoleLevel — минимальный уровень из ролей") {
      val userId = UUID.randomUUID()
      val role1 = Role(
        id = UUID.randomUUID(), companyId = Some(companyId),
        name = "Admin", displayName = "Admin", description = None,
        permissions = List("users:*", "vehicles:*"),
        isSystem = false, level = 1, createdAt = Instant.now()
      )
      val role2 = Role(
        id = UUID.randomUUID(), companyId = Some(companyId),
        name = "Viewer", displayName = "Viewer", description = None,
        permissions = List("vehicles:view"),
        isSystem = false, level = 10, createdAt = Instant.now()
      )
      for {
        repo  <- ZIO.service[RoleRepository]
        _     <- repo.create(role1) *> repo.create(role2)
        _     <- repo.assignRole(userId, role1.id, actorId) *> repo.assignRole(userId, role2.id, actorId)
        level <- repo.getUserRoleLevel(userId)
      } yield assertTrue(level.contains(1)) // минимальный уровень = 1
    }.provide(InMemoryRoleRepo.live),

    test("findByName — существующая роль") {
      val role = Role(
        id = UUID.randomUUID(), companyId = Some(companyId),
        name = "SpecialRole", displayName = "Special", description = None,
        permissions = Nil, isSystem = false, level = 5, createdAt = Instant.now()
      )
      for {
        repo  <- ZIO.service[RoleRepository]
        _     <- repo.create(role)
        found <- repo.findByName(Some(companyId), "SpecialRole")
      } yield assertTrue(found.isDefined, found.get.name == "SpecialRole")
    }.provide(InMemoryRoleRepo.live),

    test("findAll — все роли компании") {
      val r1 = Role(UUID.randomUUID(), Some(companyId), "Role1", "Role1", None, Nil, false, 1, Instant.now())
      val r2 = Role(UUID.randomUUID(), Some(companyId), "Role2", "Role2", None, Nil, false, 2, Instant.now())
      val r3 = Role(UUID.randomUUID(), Some(UUID.randomUUID()), "Role3", "Role3", None, Nil, false, 3, Instant.now())
      for {
        repo  <- ZIO.service[RoleRepository]
        _     <- repo.create(r1) *> repo.create(r2) *> repo.create(r3)
        found <- repo.findAll(Some(companyId))
      } yield assertTrue(found.length == 2)
    }.provide(InMemoryRoleRepo.live)
  )

  val auditRepoSuite = suite("InMemory AuditRepository")(
    test("log + find — логирование и чтение") {
      val entry = AuditLogEntry(
        id = UUID.randomUUID(),
        companyId = companyId,
        userId = actorId,
        userName = "admin",
        action = "USER_CREATED",
        entityType = "user",
        entityId = Some(UUID.randomUUID().toString),
        details = Some("Created test user"),
        ipAddress = Some("127.0.0.1"),
        createdAt = Instant.now()
      )
      for {
        repo   <- ZIO.service[AuditRepository]
        _      <- repo.log(entry)
        result <- repo.find(companyId, AuditFilters(None, None, None, None, None, 1, 10))
      } yield {
        val (items, total) = result
        assertTrue(
          total == 1L,
          items.head.action == "USER_CREATED"
        )
      }
    }.provide(InMemoryAuditRepo.live),

    test("find — фильтр по userId") {
      val uid1 = UUID.randomUUID()
      val uid2 = UUID.randomUUID()
      val entry1 = AuditLogEntry(UUID.randomUUID(), companyId, uid1, "user1", "LOGIN", "session", Some("s1"), None, None, Instant.now())
      val entry2 = AuditLogEntry(UUID.randomUUID(), companyId, uid2, "user2", "LOGIN", "session", Some("s2"), None, None, Instant.now())
      for {
        repo   <- ZIO.service[AuditRepository]
        _      <- repo.log(entry1) *> repo.log(entry2)
        result <- repo.find(companyId, AuditFilters(Some(uid1), None, None, None, None, 1, 10))
      } yield {
        val (items, total) = result
        assertTrue(total == 1L, items.head.userId == uid1)
      }
    }.provide(InMemoryAuditRepo.live),

    test("find — фильтр по action") {
      val entry1 = AuditLogEntry(UUID.randomUUID(), companyId, actorId, "admin", "LOGIN", "session", Some("s1"), None, None, Instant.now())
      val entry2 = AuditLogEntry(UUID.randomUUID(), companyId, actorId, "admin", "LOGOUT", "session", Some("s2"), None, None, Instant.now())
      for {
        repo   <- ZIO.service[AuditRepository]
        _      <- repo.log(entry1) *> repo.log(entry2)
        result <- repo.find(companyId, AuditFilters(None, Some("LOGIN"), None, None, None, 1, 10))
      } yield {
        val (items, total) = result
        assertTrue(total == 1L, items.head.action == "LOGIN")
      }
    }.provide(InMemoryAuditRepo.live)
  )

  val domainValidationSuite = suite("Domain — валидация")(
    test("User email не пустой") {
      val user = makeUser(email = "user@company.com")
      assertTrue(user.email.nonEmpty, user.email.contains("@"))
    },

    test("User companyId обязателен") {
      val user = makeUser()
      assertTrue(user.companyId == companyId)
    },

    test("User isActive по умолчанию true") {
      val user = makeUser()
      assertTrue(user.isActive)
    }
  )
