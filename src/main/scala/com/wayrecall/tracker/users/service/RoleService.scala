package com.wayrecall.tracker.users.service

import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.repository.RoleRepository
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Сервис управления ролями
// ============================================================

trait RoleService:
  def listRoles(companyId: Option[UUID]): Task[List[Role]]
  def createRole(actorId: UUID, companyId: UUID, request: CreateRoleRequest): Task[Role]

object RoleService:
  val live: ZLayer[RoleRepository & PermissionService, Nothing, RoleService] =
    ZLayer.fromFunction { (roleRepo: RoleRepository, permService: PermissionService) =>
      new RoleServiceLive(roleRepo, permService)
    }

final class RoleServiceLive(
    roleRepo: RoleRepository,
    permService: PermissionService
) extends RoleService:

  override def listRoles(companyId: Option[UUID]): Task[List[Role]] =
    for {
      _ <- ZIO.logDebug(s"Запрос списка ролей: company=$companyId")
      roles <- roleRepo.findAll(companyId)
      _ <- ZIO.logDebug(s"Найдено ролей: ${roles.size}")
    } yield roles

  override def createRole(actorId: UUID, companyId: UUID, request: CreateRoleRequest): Task[Role] =
    for {
      _ <- ZIO.logInfo(s"Создание роли: actor=$actorId, company=$companyId, name=${request.name}")

      // Проверяем что актор имеет право создавать роли
      hasPerms <- permService.hasPermission(actorId, "settings:edit")
      _ <- ZIO.when(!hasPerms)(ZIO.logWarning(s"Отказ в создании роли: actor=$actorId не имеет прав settings:edit"))
      _        <- ZIO.unless(hasPerms)(ZIO.fail(UserError.PermissionDenied("settings:edit", "Нет прав")))

      // Проверяем уникальность имени
      existing <- roleRepo.findByName(Some(companyId), request.name)
      _ <- ZIO.when(existing.isDefined)(ZIO.logWarning(s"Дубликат роли: name=${request.name}, company=$companyId"))
      _        <- ZIO.when(existing.isDefined)(ZIO.fail(UserError.InvalidRequest(s"Роль '${request.name}' уже существует")))

      // Уровень кастомной роли = 25 (между manager и operator)
      now = Instant.now()
      role = Role(
        id = UUID.randomUUID(),
        companyId = Some(companyId),
        name = request.name,
        displayName = request.displayName,
        description = request.description,
        permissions = request.permissions,
        isSystem = false,
        level = 25,
        createdAt = now
      )
      _ <- roleRepo.create(role)
      _ <- ZIO.logInfo(s"Роль создана: id=${role.id}, name=${role.name}, permissions=${role.permissions.size}")
    } yield role
