package com.wayrecall.tracker.users.api

import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.service.{RoleService, CompanyService, GroupService, AuditService}
import zio.*
import zio.http.*
import zio.json.*
import java.time.Instant
import java.util.UUID

// ============================================================
// REST API: Роли, группы, компания, аудит
// ============================================================

object ManagementRoutes:

  val routes: Routes[RoleService & CompanyService & GroupService & AuditService, Nothing] = Routes(

    // === Роли ===

    // GET /api/v1/roles — список ролей
    Method.GET / "api" / "v1" / "roles" -> handler { (req: Request) =>
      val companyId = extractCompanyId(req)
      ZIO.serviceWithZIO[RoleService](_.listRoles(Some(companyId)))
        .map(roles => Response.json(roles.toJson))
        .catchAll(handleError)
    },

    // POST /api/v1/roles — создать кастомную роль
    Method.POST / "api" / "v1" / "roles" -> handler { (req: Request) =>
      val actorId   = extractUserId(req)
      val companyId = extractCompanyId(req)
      (for {
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[CreateRoleRequest]).mapError(e => UserError.InvalidRequest(e))
        role    <- ZIO.serviceWithZIO[RoleService](_.createRole(actorId, companyId, request))
      } yield Response.json(role.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    // === Группы транспорта ===

    // GET /api/v1/groups — список групп
    Method.GET / "api" / "v1" / "groups" -> handler { (req: Request) =>
      val companyId = extractCompanyId(req)
      ZIO.serviceWithZIO[GroupService](_.listGroups(companyId))
        .map(groups => Response.json(groups.toJson))
        .catchAll(handleError)
    },

    // POST /api/v1/groups — создать группу
    Method.POST / "api" / "v1" / "groups" -> handler { (req: Request) =>
      val actorId   = extractUserId(req)
      val companyId = extractCompanyId(req)
      (for {
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[CreateGroupRequest]).mapError(e => UserError.InvalidRequest(e))
        group   <- ZIO.serviceWithZIO[GroupService](_.createGroup(actorId, companyId, request))
      } yield Response.json(group.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    // POST /api/v1/groups/:id/access — назначить доступ
    Method.POST / "api" / "v1" / "groups" / string("groupId") / "access" -> handler { (groupId: String, req: Request) =>
      val actorId = extractUserId(req)
      val gid     = UUID.fromString(groupId)
      (for {
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[GrantAccessRequest]).mapError(e => UserError.InvalidRequest(e))
        _       <- ZIO.serviceWithZIO[GroupService](_.grantAccess(actorId, gid, request))
      } yield Response.ok)
        .catchAll(handleError)
    },

    // === Компания ===

    // GET /api/v1/company — информация о компании
    Method.GET / "api" / "v1" / "company" -> handler { (req: Request) =>
      val companyId = extractCompanyId(req)
      ZIO.serviceWithZIO[CompanyService](_.getCompany(companyId))
        .map(c => Response.json(c.toJson))
        .catchAll(handleError)
    },

    // PUT /api/v1/company — обновить
    Method.PUT / "api" / "v1" / "company" -> handler { (req: Request) =>
      val actorId   = extractUserId(req)
      val companyId = extractCompanyId(req)
      (for {
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[UpdateCompanyRequest]).mapError(e => UserError.InvalidRequest(e))
        _       <- ZIO.serviceWithZIO[CompanyService](_.updateCompany(actorId, companyId, request))
      } yield Response.ok)
        .catchAll(handleError)
    },

    // === Аудит ===

    // GET /api/v1/audit — лог аудита
    Method.GET / "api" / "v1" / "audit" -> handler { (req: Request) =>
      val companyId = extractCompanyId(req)
      val filters = AuditFilters(
        userId = req.url.queryParams.get("userId").flatMap(s => scala.util.Try(UUID.fromString(s)).toOption),
        action = req.url.queryParams.get("action"),
        entityType = req.url.queryParams.get("entityType"),
        fromDate = req.url.queryParams.get("fromDate").flatMap(s => scala.util.Try(Instant.parse(s)).toOption),
        toDate = req.url.queryParams.get("toDate").flatMap(s => scala.util.Try(Instant.parse(s)).toOption),
        page = req.url.queryParams.get("page").flatMap(_.toIntOption).getOrElse(1),
        pageSize = req.url.queryParams.get("pageSize").flatMap(_.toIntOption).getOrElse(20)
      )
      ZIO.serviceWithZIO[AuditService](_.getLog(companyId, filters))
        .map(r => Response.json(r.toJson))
        .catchAll(handleError)
    }
  )

  // === Вспомогательные методы (продублированы, в реале — в общем middleware) ===

  private def extractUserId(req: Request): UUID =
    req.headers.get("X-User-Id")
      .flatMap(v => scala.util.Try(UUID.fromString(v)).toOption)
      .getOrElse(UUID.randomUUID())

  private def extractCompanyId(req: Request): UUID =
    req.headers.get("X-Company-Id")
      .flatMap(v => scala.util.Try(UUID.fromString(v)).toOption)
      .getOrElse(UUID.randomUUID())

  private def handleError(error: Throwable): UIO[Response] = error match
    case e: UserError.PermissionDenied =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("forbidden", e.message).toJson).status(Status.Forbidden))
    case e: UserError.CompanyNotFound =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("not_found", e.message).toJson).status(Status.NotFound))
    case e: UserError.GroupNotFound =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("not_found", e.message).toJson).status(Status.NotFound))
    case e: UserError.InvalidRequest =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("bad_request", e.message).toJson).status(Status.BadRequest))
    case e =>
      ZIO.logError(s"Неожиданная ошибка: ${e.getMessage}") *>
        ZIO.succeed(Response.json(UserError.ErrorResponse("internal_error", "Внутренняя ошибка").toJson).status(Status.InternalServerError))
