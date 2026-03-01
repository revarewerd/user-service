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
    roleRepo.findAll(companyId)

  override def createRole(actorId: UUID, companyId: UUID, request: CreateRoleRequest): Task[Role] =
    for {
      // Проверяем что актор имеет право создавать роли
      hasPerms <- permService.hasPermission(actorId, "settings:edit")
      _        <- ZIO.unless(hasPerms)(ZIO.fail(UserError.PermissionDenied("settings:edit", "Нет прав")))

      // Проверяем уникальность имени
      existing <- roleRepo.findByName(Some(companyId), request.name)
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
    } yield role
