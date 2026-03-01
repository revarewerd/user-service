package com.wayrecall.tracker.users.repository

import com.wayrecall.tracker.users.domain.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import zio.*
import zio.interop.catz.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Репозиторий групп транспорта
// ============================================================

trait VehicleGroupRepository:
  def findByCompany(companyId: UUID): Task[List[VehicleGroup]]
  def findById(id: UUID): Task[Option[VehicleGroup]]
  def create(group: VehicleGroup): Task[UUID]
  def grantAccess(userId: UUID, groupId: UUID, level: AccessLevel, grantedBy: UUID): Task[Unit]
  def revokeAccess(userId: UUID, groupId: UUID): Task[Unit]
  def getUserAccess(userId: UUID): Task[List[UserGroupAccess]]
  def getGroupVehicleIds(groupId: UUID): Task[List[Long]]

object VehicleGroupRepository:
  val live: ZLayer[Transactor[Task], Nothing, VehicleGroupRepository] =
    ZLayer.fromFunction { (xa: Transactor[Task]) =>
      new VehicleGroupRepositoryLive(xa)
    }

final class VehicleGroupRepositoryLive(xa: Transactor[Task]) extends VehicleGroupRepository:

  override def findByCompany(companyId: UUID): Task[List[VehicleGroup]] =
    sql"""
      SELECT id, company_id, name, description, color, icon, parent_id, vehicle_ids, created_at, updated_at
      FROM users.vehicle_groups WHERE company_id = $companyId
      ORDER BY name ASC
    """.query[VehicleGroup].to[List].transact(xa)

  override def findById(id: UUID): Task[Option[VehicleGroup]] =
    sql"""
      SELECT id, company_id, name, description, color, icon, parent_id, vehicle_ids, created_at, updated_at
      FROM users.vehicle_groups WHERE id = $id
    """.query[VehicleGroup].option.transact(xa)

  override def create(group: VehicleGroup): Task[UUID] =
    sql"""
      INSERT INTO users.vehicle_groups (id, company_id, name, description, color, icon, parent_id, vehicle_ids, created_at, updated_at)
      VALUES (${group.id}, ${group.companyId}, ${group.name}, ${group.description},
              ${group.color}, ${group.icon}, ${group.parentId}, ${group.vehicleIds.toArray}::bigint[],
              ${group.createdAt}, ${group.updatedAt})
      RETURNING id
    """.query[UUID].unique.transact(xa)

  override def grantAccess(userId: UUID, groupId: UUID, level: AccessLevel, grantedBy: UUID): Task[Unit] =
    val now       = Instant.now()
    val levelStr  = level.toString.toLowerCase
    sql"""
      INSERT INTO users.user_group_access (user_id, group_id, access_level, granted_at, granted_by)
      VALUES ($userId, $groupId, $levelStr::users.access_level_type, $now, $grantedBy)
      ON CONFLICT (user_id, group_id)
      DO UPDATE SET access_level = $levelStr::users.access_level_type, granted_at = $now, granted_by = $grantedBy
    """.update.run.transact(xa).unit

  override def revokeAccess(userId: UUID, groupId: UUID): Task[Unit] =
    sql"DELETE FROM users.user_group_access WHERE user_id = $userId AND group_id = $groupId"
      .update.run.transact(xa).unit

  override def getUserAccess(userId: UUID): Task[List[UserGroupAccess]] =
    sql"""
      SELECT user_id, group_id, access_level::text, granted_at, granted_by
      FROM users.user_group_access WHERE user_id = $userId
    """.query[UserGroupAccess].to[List].transact(xa)

  override def getGroupVehicleIds(groupId: UUID): Task[List[Long]] =
    sql"""
      SELECT unnest(vehicle_ids) FROM users.vehicle_groups WHERE id = $groupId
    """.query[Long].to[List].transact(xa)

  given Read[VehicleGroup] = Read[
    (UUID, UUID, String, Option[String], Option[String], Option[String], Option[UUID], Array[Long], Instant, Instant)
  ].map { case (id, companyId, name, description, color, icon, parentId, vehicleIds, createdAt, updatedAt) =>
    VehicleGroup(id, companyId, name, description, color, icon, parentId, vehicleIds.toList, createdAt, updatedAt)
  }

  given Read[UserGroupAccess] = Read[(UUID, UUID, String, Instant, UUID)].map {
    case (userId, groupId, accessLevelStr, grantedAt, grantedBy) =>
      val level = AccessLevel.values.find(_.toString.equalsIgnoreCase(accessLevelStr)).getOrElse(AccessLevel.View)
      UserGroupAccess(userId, groupId, level, grantedAt, grantedBy)
  }
