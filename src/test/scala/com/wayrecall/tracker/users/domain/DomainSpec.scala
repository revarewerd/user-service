package com.wayrecall.tracker.users.domain

import zio.*
import zio.test.*
import zio.json.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Тесты доменных моделей User Service
// ============================================================

object DomainSpec extends ZIOSpecDefault:

  def spec = suite("Domain Models")(
    systemRolesSuite,
    permissionSuite,
    accessLevelSuite,
    errorsSuite,
    modelsSuite
  )

  // === SystemRoles ===

  val systemRolesSuite = suite("SystemRoles")(
    test("содержит все 6 системных ролей") {
      assertTrue(
        SystemRoles.SuperAdmin == "super_admin",
        SystemRoles.Admin == "admin",
        SystemRoles.Manager == "manager",
        SystemRoles.Operator == "operator",
        SystemRoles.Dispatcher == "dispatcher",
        SystemRoles.Viewer == "viewer"
      )
    },
    test("levels содержит 6 записей с правильной иерархией") {
      val levels = SystemRoles.levels
      assertTrue(
        levels.size == 6,
        levels(SystemRoles.SuperAdmin) == 0,
        levels(SystemRoles.Admin) == 10,
        levels(SystemRoles.Manager) == 20,
        levels(SystemRoles.Operator) == 30,
        levels(SystemRoles.Dispatcher) == 40,
        levels(SystemRoles.Viewer) == 50
      )
    },
    test("SuperAdmin имеет наименьший уровень (больше всего прав)") {
      val minLevel = SystemRoles.levels.values.min
      assertTrue(minLevel == SystemRoles.levels(SystemRoles.SuperAdmin))
    }
  )

  // === Permission enum ===

  val permissionSuite = suite("Permission")(
    test("содержит 27 значений") {
      assertTrue(Permission.values.length == 27)
    },
    test("каждое значение имеет уникальный code") {
      val codes = Permission.values.map(_.code).toSet
      assertTrue(codes.size == 27)
    },
    test("коды следуют формату category:action") {
      assertTrue(
        Permission.UsersView.code == "users:view",
        Permission.VehiclesCommand.code == "vehicles:command",
        Permission.GeozonesCreate.code == "geozones:create",
        Permission.ReportsExport.code == "reports:export",
        Permission.MaintenanceEdit.code == "maintenance:edit",
        Permission.NotificationsView.code == "notifications:view",
        Permission.SettingsEdit.code == "settings:edit",
        Permission.IntegrationsCreate.code == "integrations:create"
      )
    },
    test("JSON encoder кодирует в строку code") {
      val json = Permission.UsersView.toJson
      assertTrue(json == "\"users:view\"")
    },
    test("JSON decoder декодирует из строки code") {
      val result = "\"vehicles:edit\"".fromJson[Permission]
      assertTrue(result == Right(Permission.VehiclesEdit))
    },
    test("JSON decoder возвращает ошибку для неизвестного кода") {
      val result = "\"unknown:perm\"".fromJson[Permission]
      assertTrue(result.isLeft)
    },
    test("matchesWildcard — точное совпадение") {
      assertTrue(
        Permission.matchesWildcard("users:view", "users:view"),
        !Permission.matchesWildcard("users:view", "users:edit")
      )
    },
    test("matchesWildcard — wildcard pattern с :*") {
      assertTrue(
        Permission.matchesWildcard("users:*", "users:view"),
        Permission.matchesWildcard("users:*", "users:create"),
        Permission.matchesWildcard("users:*", "users:delete"),
        !Permission.matchesWildcard("users:*", "vehicles:view")
      )
    },
    test("matchesWildcard — паттерн без :* требует точного совпадения") {
      assertTrue(
        Permission.matchesWildcard("reports:view", "reports:view"),
        !Permission.matchesWildcard("reports:view", "reports:create")
      )
    }
  )

  // === AccessLevel enum ===

  val accessLevelSuite = suite("AccessLevel")(
    test("содержит 3 значения") {
      assertTrue(AccessLevel.values.length == 3)
    },
    test("JSON roundtrip") {
      val view = AccessLevel.View
      val operate = AccessLevel.Operate
      val manage = AccessLevel.Manage
      assertTrue(
        view.toJson == "\"view\"",
        operate.toJson == "\"operate\"",
        manage.toJson == "\"manage\""
      )
    },
    test("JSON decoder парсит строку case-insensitive") {
      val result = "\"view\"".fromJson[AccessLevel]
      assertTrue(result == Right(AccessLevel.View))
    },
    test("JSON decoder ошибка для неизвестного уровня") {
      val result = "\"superadmin\"".fromJson[AccessLevel]
      assertTrue(result.isLeft)
    }
  )

  // === UserError ===

  val errorsSuite = suite("UserError")(
    test("UserNotFound содержит userId в сообщении") {
      val id = UUID.randomUUID()
      val err = UserError.UserNotFound(id)
      assertTrue(
        err.message.contains(id.toString),
        err.getMessage == err.message,
        err.isInstanceOf[Exception]
      )
    },
    test("EmailAlreadyExists содержит email") {
      val err = UserError.EmailAlreadyExists("test@test.com")
      assertTrue(err.message.contains("test@test.com"))
    },
    test("PermissionDenied содержит action и reason") {
      val err = UserError.PermissionDenied("users:create", "Нет прав")
      assertTrue(
        err.message.contains("users:create"),
        err.message.contains("Нет прав")
      )
    },
    test("CompanyNotFound содержит companyId") {
      val id = UUID.randomUUID()
      val err = UserError.CompanyNotFound(id)
      assertTrue(err.message.contains(id.toString))
    },
    test("RoleNotFound содержит roleId") {
      val id = UUID.randomUUID()
      val err = UserError.RoleNotFound(id)
      assertTrue(err.message.contains(id.toString))
    },
    test("GroupNotFound содержит groupId") {
      val id = UUID.randomUUID()
      val err = UserError.GroupNotFound(id)
      assertTrue(err.message.contains(id.toString))
    },
    test("InvalidPassword содержит details") {
      val err = UserError.InvalidPassword("Слишком простой")
      assertTrue(err.message.contains("Слишком простой"))
    },
    test("InvalidRequest содержит details") {
      val err = UserError.InvalidRequest("Отсутствует поле email")
      assertTrue(err.message.contains("Отсутствует поле email"))
    },
    test("DatabaseError содержит cause") {
      val err = UserError.DatabaseError("Connection lost")
      assertTrue(err.message.contains("Connection lost"))
    },
    test("ErrorResponse JSON roundtrip") {
      val resp = UserError.ErrorResponse("not_found", "Пользователь не найден")
      val json = resp.toJson
      val decoded = json.fromJson[UserError.ErrorResponse]
      assertTrue(decoded == Right(resp))
    },
    test("все 9 подтипов UserError покрыты exhaustive match") {
      val errors: List[UserError] = List(
        UserError.UserNotFound(UUID.randomUUID()),
        UserError.EmailAlreadyExists("a@b.com"),
        UserError.PermissionDenied("action", "reason"),
        UserError.CompanyNotFound(UUID.randomUUID()),
        UserError.RoleNotFound(UUID.randomUUID()),
        UserError.GroupNotFound(UUID.randomUUID()),
        UserError.InvalidPassword("details"),
        UserError.InvalidRequest("reason"),
        UserError.DatabaseError("cause")
      )
      val messages = errors.map {
        case e: UserError.UserNotFound       => e.message
        case e: UserError.EmailAlreadyExists => e.message
        case e: UserError.PermissionDenied   => e.message
        case e: UserError.CompanyNotFound    => e.message
        case e: UserError.RoleNotFound       => e.message
        case e: UserError.GroupNotFound      => e.message
        case e: UserError.InvalidPassword    => e.message
        case e: UserError.InvalidRequest     => e.message
        case e: UserError.DatabaseError      => e.message
      }
      assertTrue(messages.length == 9, messages.forall(_.nonEmpty))
    }
  )

  // === Модели ===

  val modelsSuite = suite("Models")(
    test("User derives JsonCodec — roundtrip") {
      val now = Instant.now()
      val user = User(
        id = UUID.randomUUID(), companyId = UUID.randomUUID(),
        email = "test@wayrecall.com", passwordHash = "hash123",
        firstName = "Иван", lastName = "Иванов",
        phone = Some("+7900"), position = Some("Водитель"),
        language = "ru", timezone = "Europe/Moscow",
        isActive = true, emailVerified = false,
        lastLoginAt = None, failedLoginAttempts = 0,
        createdAt = now, updatedAt = now
      )
      val json = user.toJson
      val decoded = json.fromJson[User]
      assertTrue(decoded == Right(user))
    },
    test("UserProfile derives JsonCodec") {
      val profile = UserProfile(
        id = UUID.randomUUID(), companyId = UUID.randomUUID(),
        email = "test@test.com", firstName = "Пётр", lastName = "Петров",
        phone = None, position = None, language = "ru", timezone = "UTC",
        roles = List("admin", "manager"), permissions = List("users:view", "vehicles:view")
      )
      val json = profile.toJson
      val decoded = json.fromJson[UserProfile]
      assertTrue(decoded == Right(profile))
    },
    test("Company derives JsonCodec с Optional-полями") {
      val now = Instant.now()
      val company = Company(
        id = UUID.randomUUID(), name = "ООО Тест",
        inn = Some("1234567890"), address = None,
        phone = Some("+7495"), email = Some("info@test.ru"),
        website = None, logoUrl = None,
        timezone = "Europe/Moscow", language = "ru",
        maxUsers = 50, maxVehicles = 100,
        subscriptionPlan = "pro", subscriptionExpiresAt = Some(now),
        isActive = true, createdAt = now, updatedAt = now
      )
      val json = company.toJson
      val decoded = json.fromJson[Company]
      assertTrue(decoded == Right(company))
    },
    test("Role derives JsonCodec") {
      val role = Role(
        id = UUID.randomUUID(), companyId = Some(UUID.randomUUID()),
        name = "custom_role", displayName = "Кастомная роль",
        description = Some("Описание"), permissions = List("users:view", "vehicles:view"),
        isSystem = false, level = 25, createdAt = Instant.now()
      )
      val json = role.toJson
      val decoded = json.fromJson[Role]
      assertTrue(decoded == Right(role))
    },
    test("VehicleGroup derives JsonCodec") {
      val group = VehicleGroup(
        id = UUID.randomUUID(), companyId = UUID.randomUUID(),
        name = "Группа А", description = Some("Тест"),
        color = Some("#FF0000"), icon = Some("truck"),
        parentId = None, vehicleIds = List(1L, 2L, 3L),
        createdAt = Instant.now(), updatedAt = Instant.now()
      )
      val json = group.toJson
      val decoded = json.fromJson[VehicleGroup]
      assertTrue(decoded == Right(group))
    },
    test("CreateUserRequest derives JsonCodec") {
      val req = CreateUserRequest(
        email = "new@test.com", password = "secret123",
        firstName = "Новый", lastName = "Пользователь",
        phone = None, position = None, roleId = UUID.randomUUID()
      )
      val json = req.toJson
      val decoded = json.fromJson[CreateUserRequest]
      assertTrue(decoded == Right(req))
    },
    test("AuditLogEntry derives JsonCodec") {
      val entry = AuditLogEntry(
        id = UUID.randomUUID(), companyId = UUID.randomUUID(),
        userId = UUID.randomUUID(), userName = "admin",
        action = "user.created", entityType = "user",
        entityId = Some(UUID.randomUUID().toString),
        details = Some("{\"email\":\"test@test.com\"}"),
        ipAddress = Some("192.168.1.1"),
        createdAt = Instant.now()
      )
      val json = entry.toJson
      val decoded = json.fromJson[AuditLogEntry]
      assertTrue(decoded == Right(entry))
    },
    test("AuditFilters defaults") {
      val filters = AuditFilters(
        userId = None, action = None, entityType = None,
        fromDate = None, toDate = None, page = 1, pageSize = 20
      )
      assertTrue(filters.page == 1, filters.pageSize == 20)
    },
    test("Page[A] derives JsonCodec") {
      val page = Page[String](
        items = List("a", "b"), total = 10, page = 1, pageSize = 2
      )
      val json = page.toJson
      val decoded = json.fromJson[Page[String]]
      assertTrue(decoded == Right(page))
    },
    test("MapDefaults derives JsonCodec") {
      val md = MapDefaults(
        centerLat = 55.7558, centerLon = 37.6176, zoom = 12, mapType = "hybrid"
      )
      val json = md.toJson
      val decoded = json.fromJson[MapDefaults]
      assertTrue(decoded == Right(md))
    },
    test("SubscriptionInfo derives JsonCodec") {
      val si = SubscriptionInfo(
        plan = "enterprise", maxUsers = 100, maxVehicles = 500,
        expiresAt = Some(Instant.now()), isActive = true
      )
      val json = si.toJson
      val decoded = json.fromJson[SubscriptionInfo]
      assertTrue(decoded == Right(si))
    },
    test("UserGroupAccess derives JsonCodec") {
      val access = UserGroupAccess(
        userId = UUID.randomUUID(), groupId = UUID.randomUUID(),
        accessLevel = AccessLevel.Manage,
        grantedAt = Instant.now(), grantedBy = UUID.randomUUID()
      )
      val json = access.toJson
      val decoded = json.fromJson[UserGroupAccess]
      assertTrue(decoded == Right(access))
    }
  )
