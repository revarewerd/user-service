package com.wayrecall.tracker.users.service

import com.wayrecall.tracker.users.cache.PermissionCache
import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.repository.RoleRepository
import zio.*
import zio.test.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Тесты PermissionService — RBAC, проверка прав, wildcard matching
// ============================================================

object PermissionServiceSpec extends ZIOSpecDefault:

  def spec = suite("PermissionService")(
    hasPermissionSuite,
    getUserPermissionsSuite,
    canAssignRoleSuite,
    getUserRoleLevelSuite
  )

  // === hasPermission ===

  val hasPermissionSuite = suite("hasPermission")(
    test("возвращает true для точного совпадения разрешения") {
      val userId = UUID.randomUUID()
      val roles = List(makeRole(permissions = List("users:view", "vehicles:edit")))
      for {
        service <- makeService(userRoles = Map(userId -> roles))
        result  <- service.hasPermission(userId, "users:view")
      } yield assertTrue(result)
    },

    test("возвращает false для отсутствующего разрешения") {
      val userId = UUID.randomUUID()
      val roles = List(makeRole(permissions = List("users:view")))
      for {
        service <- makeService(userRoles = Map(userId -> roles))
        result  <- service.hasPermission(userId, "users:delete")
      } yield assertTrue(!result)
    },

    test("wildcard :* матчит все actions в категории") {
      val userId = UUID.randomUUID()
      val roles = List(makeRole(permissions = List("users:*")))
      for {
        service <- makeService(userRoles = Map(userId -> roles))
        r1      <- service.hasPermission(userId, "users:view")
        r2      <- service.hasPermission(userId, "users:create")
        r3      <- service.hasPermission(userId, "users:delete")
        r4      <- service.hasPermission(userId, "vehicles:view")
      } yield assertTrue(r1, r2, r3, !r4)
    },

    test("пользователь без ролей не имеет никаких разрешений") {
      val userId = UUID.randomUUID()
      for {
        service <- makeService(userRoles = Map.empty)
        result  <- service.hasPermission(userId, "users:view")
      } yield assertTrue(!result)
    },

    test("права из нескольких ролей объединяются") {
      val userId = UUID.randomUUID()
      val role1 = makeRole(permissions = List("users:view"))
      val role2 = makeRole(permissions = List("vehicles:edit"))
      for {
        service <- makeService(userRoles = Map(userId -> List(role1, role2)))
        r1      <- service.hasPermission(userId, "users:view")
        r2      <- service.hasPermission(userId, "vehicles:edit")
      } yield assertTrue(r1, r2)
    }
  )

  // === getUserPermissions ===

  val getUserPermissionsSuite = suite("getUserPermissions")(
    test("кэширует разрешения после первого запроса") {
      val userId = UUID.randomUUID()
      val roles = List(makeRole(permissions = List("users:view", "reports:create")))
      for {
        service <- makeService(userRoles = Map(userId -> roles))
        // Первый вызов — загрузка из roleRepo
        perms1  <- service.getUserPermissions(userId)
        // Второй вызов — из кэша (тот же результат)
        perms2  <- service.getUserPermissions(userId)
      } yield assertTrue(
        perms1 == Set("users:view", "reports:create"),
        perms2 == perms1
      )
    },

    test("пустой список ролей возвращает пустой набор разрешений") {
      val userId = UUID.randomUUID()
      for {
        service <- makeService(userRoles = Map.empty)
        perms   <- service.getUserPermissions(userId)
      } yield assertTrue(perms.isEmpty)
    },

    test("дубликаты разрешений из разных ролей дедуплицируются") {
      val userId = UUID.randomUUID()
      val role1 = makeRole(permissions = List("users:view", "vehicles:view"))
      val role2 = makeRole(permissions = List("vehicles:view", "reports:view"))
      for {
        service <- makeService(userRoles = Map(userId -> List(role1, role2)))
        perms   <- service.getUserPermissions(userId)
      } yield assertTrue(
        perms == Set("users:view", "vehicles:view", "reports:view"),
        perms.size == 3
      )
    }
  )

  // === canAssignRole ===

  val canAssignRoleSuite = suite("canAssignRole")(
    test("может назначить роль если уровень актора <= уровня роли") {
      val actorId = UUID.randomUUID()
      for {
        service <- makeService(userRoleLevels = Map(actorId -> Some(10)))
        // Актор уровня 10 (Admin) может назначить роль уровня 20 (Manager)
        result  <- service.canAssignRole(actorId, 20)
      } yield assertTrue(result)
    },

    test("может назначить роль своего уровня") {
      val actorId = UUID.randomUUID()
      for {
        service <- makeService(userRoleLevels = Map(actorId -> Some(20)))
        result  <- service.canAssignRole(actorId, 20)
      } yield assertTrue(result)
    },

    test("не может назначить роль выше своего уровня") {
      val actorId = UUID.randomUUID()
      for {
        service <- makeService(userRoleLevels = Map(actorId -> Some(30)))
        // Актор уровня 30 (Operator) не может назначить роль уровня 10 (Admin)
        result  <- service.canAssignRole(actorId, 10)
      } yield assertTrue(!result)
    },

    test("пользователь без ролей получает Int.MaxValue уровень") {
      val actorId = UUID.randomUUID()
      for {
        service <- makeService(userRoleLevels = Map(actorId -> None))
        level   <- service.getUserRoleLevel(actorId)
      } yield assertTrue(level == Int.MaxValue)
    }
  )

  // === getUserRoleLevel ===

  val getUserRoleLevelSuite = suite("getUserRoleLevel")(
    test("возвращает минимальный уровень из ролей пользователя") {
      val userId = UUID.randomUUID()
      for {
        service <- makeService(userRoleLevels = Map(userId -> Some(10)))
        level   <- service.getUserRoleLevel(userId)
      } yield assertTrue(level == 10)
    },

    test("возвращает Int.MaxValue для пользователя без ролей") {
      val userId = UUID.randomUUID()
      for {
        service <- makeService(userRoleLevels = Map(userId -> None))
        level   <- service.getUserRoleLevel(userId)
      } yield assertTrue(level == Int.MaxValue)
    }
  )

  // === Вспомогательные методы ===

  private def makeRole(
      permissions: List[String] = Nil,
      name: String = "test_role",
      level: Int = 20
  ): Role = Role(
    id = UUID.randomUUID(),
    companyId = None,
    name = name,
    displayName = "Test Role",
    description = None,
    permissions = permissions,
    isSystem = false,
    level = level,
    createdAt = Instant.now()
  )

  /** Создаёт PermissionService с мок-зависимостями */
  private def makeService(
      userRoles: Map[UUID, List[Role]] = Map.empty,
      userRoleLevels: Map[UUID, Option[Int]] = Map.empty
  ): UIO[PermissionService] =
    ZIO.scoped {
      for {
        cache <- PermissionCache.live.build.map(_.get[PermissionCache])
      } yield {
        val roleRepo = new RoleRepository:
          def findById(id: UUID): Task[Option[Role]] = ZIO.succeed(None)
          def findByName(companyId: Option[UUID], name: String): Task[Option[Role]] = ZIO.succeed(None)
          def findAll(companyId: Option[UUID]): Task[List[Role]] = ZIO.succeed(Nil)
          def findSystemRoles: Task[List[Role]] = ZIO.succeed(Nil)
          def create(role: Role): Task[UUID] = ZIO.succeed(role.id)
          def assignRole(userId: UUID, roleId: UUID, assignedBy: UUID): Task[Unit] = ZIO.unit
          def removeRole(userId: UUID, roleId: UUID): Task[Unit] = ZIO.unit
          def getUserRoles(userId: UUID): Task[List[Role]] =
            ZIO.succeed(userRoles.getOrElse(userId, Nil))
          def getUserRoleLevel(userId: UUID): Task[Option[Int]] =
            ZIO.succeed(userRoleLevels.getOrElse(userId, None))

        new PermissionServiceLive(roleRepo, cache)
      }
    }
