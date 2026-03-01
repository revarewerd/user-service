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
// Репозиторий аудита
// ============================================================

trait AuditRepository:
  def log(entry: AuditLogEntry): Task[Unit]
  def find(companyId: UUID, filters: AuditFilters): Task[(List[AuditLogEntry], Long)]

object AuditRepository:
  val live: ZLayer[Transactor[Task], Nothing, AuditRepository] =
    ZLayer.fromFunction { (xa: Transactor[Task]) =>
      new AuditRepositoryLive(xa)
    }

final class AuditRepositoryLive(xa: Transactor[Task]) extends AuditRepository:

  override def log(entry: AuditLogEntry): Task[Unit] =
    sql"""
      INSERT INTO users.audit_log (id, company_id, user_id, user_name, action, entity_type, entity_id, details, ip_address, created_at)
      VALUES (${entry.id}, ${entry.companyId}, ${entry.userId}, ${entry.userName},
              ${entry.action}, ${entry.entityType}, ${entry.entityId}, ${entry.details},
              ${entry.ipAddress}, ${entry.createdAt})
    """.update.run.transact(xa).unit

  override def find(companyId: UUID, filters: AuditFilters): Task[(List[AuditLogEntry], Long)] =
    val offset = (filters.page - 1) * filters.pageSize

    // Базовый запрос с обязательным фильтром по компании
    val baseWhere = fr"WHERE company_id = $companyId"
    val userFilter = filters.userId.map(uid => fr"AND user_id = $uid").getOrElse(fr"")
    val actionFilter = filters.action.map(a => fr"AND action = $a").getOrElse(fr"")
    val entityFilter = filters.entityType.map(e => fr"AND entity_type = $e").getOrElse(fr"")
    val fromFilter = filters.fromDate.map(f => fr"AND created_at >= $f").getOrElse(fr"")
    val toFilter = filters.toDate.map(t => fr"AND created_at <= $t").getOrElse(fr"")

    val whereClause = baseWhere ++ userFilter ++ actionFilter ++ entityFilter ++ fromFilter ++ toFilter

    val countQuery =
      (fr"SELECT COUNT(*) FROM users.audit_log" ++ whereClause).query[Long].unique

    val listQuery =
      (fr"""SELECT id, company_id, user_id, user_name, action, entity_type, entity_id, details, ip_address, created_at
            FROM users.audit_log""" ++ whereClause ++
        fr"ORDER BY created_at DESC LIMIT ${filters.pageSize} OFFSET $offset")
        .query[AuditLogEntry].to[List]

    (for {
      total   <- countQuery
      entries <- listQuery
    } yield (entries, total)).transact(xa)

  given Read[AuditLogEntry] = Read[
    (UUID, UUID, UUID, String, String, String, Option[String], Option[String], Option[String], Instant)
  ].map { case (id, companyId, userId, userName, action, entityType, entityId, details, ipAddress, createdAt) =>
    AuditLogEntry(id, companyId, userId, userName, action, entityType, entityId, details, ipAddress, createdAt)
  }
