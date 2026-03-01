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
// Репозиторий ролей
// ============================================================

trait RoleRepository:
  def findById(id: UUID): Task[Option[Role]]
  def findByName(companyId: Option[UUID], name: String): Task[Option[Role]]
  def findAll(companyId: Option[UUID]): Task[List[Role]]
  def findSystemRoles: Task[List[Role]]
  def create(role: Role): Task[UUID]
  def assignRole(userId: UUID, roleId: UUID, assignedBy: UUID): Task[Unit]
  def removeRole(userId: UUID, roleId: UUID): Task[Unit]
  def getUserRoles(userId: UUID): Task[List[Role]]
  def getUserRoleLevel(userId: UUID): Task[Option[Int]]

object RoleRepository:
  val live: ZLayer[Transactor[Task], Nothing, RoleRepository] =
    ZLayer.fromFunction { (xa: Transactor[Task]) =>
      new RoleRepositoryLive(xa)
    }

final class RoleRepositoryLive(xa: Transactor[Task]) extends RoleRepository:

  override def findById(id: UUID): Task[Option[Role]] =
    sql"""
      SELECT id, company_id, name, display_name, description, permissions, is_system, level, created_at
      FROM users.roles WHERE id = $id
    """.query[Role].option.transact(xa)

  override def findByName(companyId: Option[UUID], name: String): Task[Option[Role]] =
    companyId match
      case Some(cid) =>
        sql"""
          SELECT id, company_id, name, display_name, description, permissions, is_system, level, created_at
          FROM users.roles WHERE (company_id = $cid OR company_id IS NULL) AND name = $name
          ORDER BY company_id NULLS LAST LIMIT 1
        """.query[Role].option.transact(xa)
      case None =>
        sql"""
          SELECT id, company_id, name, display_name, description, permissions, is_system, level, created_at
          FROM users.roles WHERE company_id IS NULL AND name = $name
        """.query[Role].option.transact(xa)

  override def findAll(companyId: Option[UUID]): Task[List[Role]] =
    companyId match
      case Some(cid) =>
        sql"""
          SELECT id, company_id, name, display_name, description, permissions, is_system, level, created_at
          FROM users.roles WHERE company_id = $cid OR company_id IS NULL
          ORDER BY level ASC
        """.query[Role].to[List].transact(xa)
      case None =>
        sql"""
          SELECT id, company_id, name, display_name, description, permissions, is_system, level, created_at
          FROM users.roles WHERE company_id IS NULL
          ORDER BY level ASC
        """.query[Role].to[List].transact(xa)

  override def findSystemRoles: Task[List[Role]] =
    sql"""
      SELECT id, company_id, name, display_name, description, permissions, is_system, level, created_at
      FROM users.roles WHERE is_system = true ORDER BY level ASC
    """.query[Role].to[List].transact(xa)

  override def create(role: Role): Task[UUID] =
    sql"""
      INSERT INTO users.roles (id, company_id, name, display_name, description, permissions, is_system, level, created_at)
      VALUES (${role.id}, ${role.companyId}, ${role.name}, ${role.displayName}, ${role.description},
              ${role.permissions.toArray}::text[], ${role.isSystem}, ${role.level}, ${role.createdAt})
      RETURNING id
    """.query[UUID].unique.transact(xa)

  override def assignRole(userId: UUID, roleId: UUID, assignedBy: UUID): Task[Unit] =
    val now = Instant.now()
    sql"""
      INSERT INTO users.user_roles (user_id, role_id, assigned_at, assigned_by)
      VALUES ($userId, $roleId, $now, $assignedBy)
      ON CONFLICT (user_id, role_id) DO NOTHING
    """.update.run.transact(xa).unit

  override def removeRole(userId: UUID, roleId: UUID): Task[Unit] =
    sql"DELETE FROM users.user_roles WHERE user_id = $userId AND role_id = $roleId"
      .update.run.transact(xa).unit

  override def getUserRoles(userId: UUID): Task[List[Role]] =
    sql"""
      SELECT r.id, r.company_id, r.name, r.display_name, r.description, r.permissions, r.is_system, r.level, r.created_at
      FROM users.roles r
      JOIN users.user_roles ur ON ur.role_id = r.id
      WHERE ur.user_id = $userId
      ORDER BY r.level ASC
    """.query[Role].to[List].transact(xa)

  override def getUserRoleLevel(userId: UUID): Task[Option[Int]] =
    sql"""
      SELECT MIN(r.level) FROM users.roles r
      JOIN users.user_roles ur ON ur.role_id = r.id
      WHERE ur.user_id = $userId
    """.query[Option[Int]].unique.transact(xa)

  given Read[Role] = Read[
    (UUID, Option[UUID], String, String, Option[String], Array[String], Boolean, Int, Instant)
  ].map { case (id, companyId, name, displayName, description, permissions, isSystem, level, createdAt) =>
    Role(id, companyId, name, displayName, description, permissions.toList, isSystem, level, createdAt)
  }
