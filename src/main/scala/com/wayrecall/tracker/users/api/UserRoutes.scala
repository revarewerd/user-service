package com.wayrecall.tracker.users.api

import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.service.{UserService, PermissionService}
import zio.*
import zio.http.*
import zio.json.*
import java.util.UUID

// ============================================================
// REST API: Управление пользователями
// ============================================================

object UserRoutes:

  val routes: Routes[UserService & PermissionService, Nothing] = Routes(

    // === Профиль текущего пользователя ===

    // GET /api/v1/users/me — получить свой профиль
    Method.GET / "api" / "v1" / "users" / "me" -> handler { (req: Request) =>
      // В реальности userId берётся из JWT-токена через middleware
      val userId = extractUserId(req)
      ZIO.serviceWithZIO[UserService](_.getProfile(userId))
        .map(p => Response.json(p.toJson))
        .catchAll(handleError)
    },

    // PUT /api/v1/users/me — обновить профиль
    Method.PUT / "api" / "v1" / "users" / "me" -> handler { (req: Request) =>
      val userId = extractUserId(req)
      (for {
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[UpdateProfileRequest]).mapError(e => UserError.InvalidRequest(e))
        _       <- ZIO.serviceWithZIO[UserService](_.updateProfile(userId, request))
      } yield Response.ok)
        .catchAll(handleError)
    },

    // PUT /api/v1/users/me/password — сменить пароль
    Method.PUT / "api" / "v1" / "users" / "me" / "password" -> handler { (req: Request) =>
      val userId = extractUserId(req)
      (for {
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[ChangePasswordRequest]).mapError(e => UserError.InvalidRequest(e))
        _       <- ZIO.serviceWithZIO[UserService](_.changePassword(userId, request))
      } yield Response(status = Status.NoContent))
        .catchAll(handleError)
    },

    // === Управление пользователями (admin) ===

    // GET /api/v1/users — список пользователей
    Method.GET / "api" / "v1" / "users" -> handler { (req: Request) =>
      val companyId = extractCompanyId(req)
      val page      = req.url.queryParams.get("page").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(1)
      val pageSize  = req.url.queryParams.get("pageSize").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(20)
      val search    = req.url.queryParams.get("search").flatMap(_.headOption)

      ZIO.serviceWithZIO[UserService](_.listUsers(companyId, page, pageSize, search))
        .map(r => Response.json(r.toJson))
        .catchAll(handleError)
    },

    // POST /api/v1/users — создать пользователя
    Method.POST / "api" / "v1" / "users" -> handler { (req: Request) =>
      val actorId   = extractUserId(req)
      val companyId = extractCompanyId(req)
      (for {
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[CreateUserRequest]).mapError(e => UserError.InvalidRequest(e))
        created <- ZIO.serviceWithZIO[UserService](_.createUser(actorId, companyId, request))
      } yield Response.json(created.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    // GET /api/v1/users/:id — получить пользователя
    Method.GET / "api" / "v1" / "users" / string("userId") -> handler { (userId: String, req: Request) =>
      val uid = UUID.fromString(userId)
      ZIO.serviceWithZIO[UserService](_.getUser(uid))
        .map(p => Response.json(p.toJson))
        .catchAll(handleError)
    },

    // DELETE /api/v1/users/:id — деактивировать
    Method.DELETE / "api" / "v1" / "users" / string("userId") -> handler { (userId: String, req: Request) =>
      val actorId = extractUserId(req)
      val uid     = UUID.fromString(userId)
      ZIO.serviceWithZIO[UserService](_.deactivateUser(actorId, uid))
        .as(Response(status = Status.NoContent))
        .catchAll(handleError)
    },

    // PUT /api/v1/users/:id/role — назначить роль
    Method.PUT / "api" / "v1" / "users" / string("userId") / "role" -> handler { (userId: String, req: Request) =>
      val actorId = extractUserId(req)
      val uid     = UUID.fromString(userId)
      (for {
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[AssignRoleRequest]).mapError(e => UserError.InvalidRequest(e))
        _       <- ZIO.serviceWithZIO[UserService](_.assignRole(actorId, uid, request.roleId))
      } yield Response.ok)
        .catchAll(handleError)
    },

    // POST /api/v1/users/:id/reset-password — сбросить пароль
    Method.POST / "api" / "v1" / "users" / string("userId") / "reset-password" -> handler { (userId: String, req: Request) =>
      val actorId = extractUserId(req)
      val uid     = UUID.fromString(userId)
      ZIO.serviceWithZIO[UserService](_.resetPassword(actorId, uid))
        .as(Response(status = Status.NoContent))
        .catchAll(handleError)
    }
  )

  // === Вспомогательные методы ===

  /** Извлечь userId из заголовка (заглушка до Auth Service) */
  private def extractUserId(req: Request): UUID =
    req.headers.get("X-User-Id")
      .flatMap(v => scala.util.Try(UUID.fromString(v)).toOption)
      .getOrElse(UUID.randomUUID())

  /** Извлечь companyId из заголовка (заглушка до Auth Service) */
  private def extractCompanyId(req: Request): UUID =
    req.headers.get("X-Company-Id")
      .flatMap(v => scala.util.Try(UUID.fromString(v)).toOption)
      .getOrElse(UUID.randomUUID())

  /** Обработка ошибок */
  private def handleError(error: Throwable): UIO[Response] = error match
    case e: UserError.UserNotFound =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("not_found", e.message).toJson).status(Status.NotFound))
    case e: UserError.EmailAlreadyExists =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("conflict", e.message).toJson).status(Status.Conflict))
    case e: UserError.PermissionDenied =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("forbidden", e.message).toJson).status(Status.Forbidden))
    case e: UserError.InvalidPassword =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("bad_request", e.message).toJson).status(Status.BadRequest))
    case e: UserError.InvalidRequest =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("bad_request", e.message).toJson).status(Status.BadRequest))
    case e: UserError.RoleNotFound =>
      ZIO.succeed(Response.json(UserError.ErrorResponse("not_found", e.message).toJson).status(Status.NotFound))
    case e =>
      ZIO.logError(s"Неожиданная ошибка: ${e.getMessage}") *>
        ZIO.succeed(Response.json(UserError.ErrorResponse("internal_error", "Внутренняя ошибка").toJson).status(Status.InternalServerError))
