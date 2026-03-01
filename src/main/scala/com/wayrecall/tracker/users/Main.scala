package com.wayrecall.tracker.users

import com.wayrecall.tracker.users.api.*
import com.wayrecall.tracker.users.cache.PermissionCache
import com.wayrecall.tracker.users.config.AppConfig
import com.wayrecall.tracker.users.infrastructure.TransactorLayer
import com.wayrecall.tracker.users.repository.*
import com.wayrecall.tracker.users.service.*
import zio.*
import zio.http.*
import zio.logging.backend.SLF4J

// ============================================================
// Main — точка входа User Service (порт 8091)
// RBAC, управление пользователями, компаниями, группами ТС
// ============================================================

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  override def run: ZIO[Any, Any, Any] =
    val program = for {
      config <- ZIO.service[AppConfig]
      _      <- ZIO.logInfo(s"=== User Service запускается на порту ${config.server.port} ===")

      // Собираем все маршруты
      allRoutes = HealthRoutes.routes ++
                  UserRoutes.routes ++
                  ManagementRoutes.routes

      // Запускаем HTTP-сервер
      _      <- Server.serve(allRoutes)
    } yield ()

    program.provide(
      // Конфигурация
      AppConfig.live,

      // БД транзактор
      TransactorLayer.live,

      // Redis
      zio.redis.Redis.local,
      zio.redis.RedisExecutor.local,
      zio.redis.CodecSupplier.utf8,

      // Репозитории
      UserRepository.live,
      CompanyRepository.live,
      RoleRepository.live,
      VehicleGroupRepository.live,
      AuditRepository.live,

      // Кэш
      PermissionCache.live,

      // Сервисы
      PermissionService.live,
      UserService.live,
      CompanyService.live,
      RoleService.live,
      GroupService.live,
      AuditService.live,

      // HTTP-сервер
      Server.defaultWithPort(8091)
    )
