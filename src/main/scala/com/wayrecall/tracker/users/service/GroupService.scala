package com.wayrecall.tracker.users.service

import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.repository.{AuditRepository, VehicleGroupRepository}
import com.wayrecall.tracker.users.cache.PermissionCache
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Сервис управления группами транспорта
// ============================================================

trait GroupService:
  def listGroups(companyId: UUID): Task[List[VehicleGroup]]
  def createGroup(actorId: UUID, companyId: UUID, request: CreateGroupRequest): Task[VehicleGroup]
  def grantAccess(actorId: UUID, groupId: UUID, request: GrantAccessRequest): Task[Unit]
  def getUserAccessibleVehicles(userId: UUID): Task[Map[AccessLevel, Set[Long]]]

object GroupService:
  val live: ZLayer[VehicleGroupRepository & AuditRepository & PermissionService & PermissionCache, Nothing, GroupService] =
    ZLayer.fromFunction { (
        groupRepo: VehicleGroupRepository,
        auditRepo: AuditRepository,
        permService: PermissionService,
        cache: PermissionCache
    ) =>
      new GroupServiceLive(groupRepo, auditRepo, permService, cache)
    }

final class GroupServiceLive(
    groupRepo: VehicleGroupRepository,
    auditRepo: AuditRepository,
    permService: PermissionService,
    cache: PermissionCache
) extends GroupService:

  override def listGroups(companyId: UUID): Task[List[VehicleGroup]] =
    groupRepo.findByCompany(companyId)

  override def createGroup(actorId: UUID, companyId: UUID, request: CreateGroupRequest): Task[VehicleGroup] =
    for {
      hasPerms <- permService.hasPermission(actorId, "vehicles:create")
      _        <- ZIO.unless(hasPerms)(ZIO.fail(UserError.PermissionDenied("vehicles:create", "Нет прав")))

      now = Instant.now()
      group = VehicleGroup(
        id = UUID.randomUUID(),
        companyId = companyId,
        name = request.name,
        description = request.description,
        color = request.color,
        icon = request.icon,
        parentId = request.parentId,
        vehicleIds = Nil,
        createdAt = now,
        updatedAt = now
      )
      _ <- groupRepo.create(group)
    } yield group

  override def grantAccess(actorId: UUID, groupId: UUID, request: GrantAccessRequest): Task[Unit] =
    for {
      hasPerms <- permService.hasPermission(actorId, "users:edit")
      _        <- ZIO.unless(hasPerms)(ZIO.fail(UserError.PermissionDenied("users:edit", "Нет прав")))
      _        <- groupRepo.findById(groupId).someOrFail(UserError.GroupNotFound(groupId))
      _        <- groupRepo.grantAccess(request.userId, groupId, request.accessLevel, actorId)
      _        <- cache.invalidateUser(request.userId)
    } yield ()

  override def getUserAccessibleVehicles(userId: UUID): Task[Map[AccessLevel, Set[Long]]] =
    for {
      accesses <- groupRepo.getUserAccess(userId)
      // Для каждого доступа получаем vehicleIds из группы
      result <- ZIO.foldLeft(accesses)(Map.empty[AccessLevel, Set[Long]]) { (acc, access) =>
        groupRepo.getGroupVehicleIds(access.groupId).map { vehicleIds =>
          val existing = acc.getOrElse(access.accessLevel, Set.empty)
          acc.updated(access.accessLevel, existing ++ vehicleIds.toSet)
        }
      }
    } yield result
