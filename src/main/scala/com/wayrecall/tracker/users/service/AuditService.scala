package com.wayrecall.tracker.users.service

import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.repository.AuditRepository
import zio.*
import java.util.UUID

// ============================================================
// Сервис аудита
// ============================================================

trait AuditService:
  def getLog(companyId: UUID, filters: AuditFilters): Task[AuditListResponse]

object AuditService:
  val live: ZLayer[AuditRepository, Nothing, AuditService] =
    ZLayer.fromFunction { (auditRepo: AuditRepository) =>
      new AuditServiceLive(auditRepo)
    }

final class AuditServiceLive(auditRepo: AuditRepository) extends AuditService:

  override def getLog(companyId: UUID, filters: AuditFilters): Task[AuditListResponse] =
    for {
      (entries, total) <- auditRepo.find(companyId, filters)
    } yield AuditListResponse(total, entries)
