package com.wayrecall.tracker.users.domain

import zio.json.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Пользователь
// ============================================================

final case class User(
    id: UUID,
    companyId: UUID,
    email: String,
    passwordHash: String,
    firstName: String,
    lastName: String,
    phone: Option[String],
    position: Option[String],
    language: String,
    timezone: String,
    isActive: Boolean,
    emailVerified: Boolean,
    lastLoginAt: Option[Instant],
    failedLoginAttempts: Int,
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec

// ============================================================
// Профиль пользователя (без passwordHash)
// ============================================================

final case class UserProfile(
    id: UUID,
    companyId: UUID,
    email: String,
    firstName: String,
    lastName: String,
    phone: Option[String],
    position: Option[String],
    language: String,
    timezone: String,
    roles: List[String],
    permissions: List[String]
) derives JsonCodec

// ============================================================
// Настройки пользователя
// ============================================================

final case class UserSettings(
    userId: UUID,
    notificationEmail: Boolean,
    notificationPush: Boolean,
    notificationSms: Boolean,
    mapDefaults: MapDefaults,
    dashboardLayout: Option[String],
    updatedAt: Instant
) derives JsonCodec

final case class MapDefaults(
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    mapType: String
) derives JsonCodec

// ============================================================
// Компания
// ============================================================

final case class Company(
    id: UUID,
    name: String,
    inn: Option[String],
    address: Option[String],
    phone: Option[String],
    email: Option[String],
    website: Option[String],
    logoUrl: Option[String],
    timezone: String,
    language: String,
    maxUsers: Int,
    maxVehicles: Int,
    subscriptionPlan: String,
    subscriptionExpiresAt: Option[Instant],
    isActive: Boolean,
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec

final case class SubscriptionInfo(
    plan: String,
    maxUsers: Int,
    maxVehicles: Int,
    expiresAt: Option[Instant],
    isActive: Boolean
) derives JsonCodec

// ============================================================
// Роли
// ============================================================

final case class Role(
    id: UUID,
    companyId: Option[UUID],
    name: String,
    displayName: String,
    description: Option[String],
    permissions: List[String],
    isSystem: Boolean,
    level: Int,
    createdAt: Instant
) derives JsonCodec

/** Системные роли с фиксированными уровнями */
object SystemRoles:
  val SuperAdmin = "super_admin"
  val Admin      = "admin"
  val Manager    = "manager"
  val Operator   = "operator"
  val Dispatcher = "dispatcher"
  val Viewer     = "viewer"

  /** Уровни: чем меньше число — тем больше прав */
  val levels: Map[String, Int] = Map(
    SuperAdmin -> 0,
    Admin      -> 10,
    Manager    -> 20,
    Operator   -> 30,
    Dispatcher -> 40,
    Viewer     -> 50
  )

// ============================================================
// Разрешения (27 штук по 8 категориям)
// ============================================================

enum Permission(val code: String):
  // Пользователи
  case UsersView       extends Permission("users:view")
  case UsersCreate     extends Permission("users:create")
  case UsersEdit       extends Permission("users:edit")
  case UsersDelete     extends Permission("users:delete")
  // Транспорт
  case VehiclesView    extends Permission("vehicles:view")
  case VehiclesCreate  extends Permission("vehicles:create")
  case VehiclesEdit    extends Permission("vehicles:edit")
  case VehiclesDelete  extends Permission("vehicles:delete")
  case VehiclesCommand extends Permission("vehicles:command")
  // Геозоны
  case GeozonesView    extends Permission("geozones:view")
  case GeozonesCreate  extends Permission("geozones:create")
  case GeozonesEdit    extends Permission("geozones:edit")
  case GeozonesDelete  extends Permission("geozones:delete")
  // Отчёты
  case ReportsView     extends Permission("reports:view")
  case ReportsCreate   extends Permission("reports:create")
  case ReportsExport   extends Permission("reports:export")
  // ТО
  case MaintenanceView   extends Permission("maintenance:view")
  case MaintenanceCreate extends Permission("maintenance:create")
  case MaintenanceEdit   extends Permission("maintenance:edit")
  // Уведомления
  case NotificationsView   extends Permission("notifications:view")
  case NotificationsCreate extends Permission("notifications:create")
  case NotificationsEdit   extends Permission("notifications:edit")
  // Настройки
  case SettingsView extends Permission("settings:view")
  case SettingsEdit extends Permission("settings:edit")
  // Интеграции
  case IntegrationsView   extends Permission("integrations:view")
  case IntegrationsCreate extends Permission("integrations:create")
  case IntegrationsEdit   extends Permission("integrations:edit")

object Permission:
  given JsonEncoder[Permission] = JsonEncoder[String].contramap(_.code)
  given JsonDecoder[Permission] = JsonDecoder[String].mapOrFail { s =>
    Permission.values.find(_.code == s).toRight(s"Неизвестное разрешение: $s")
  }

  /** Все разрешения в виде wildcard-паттерна (например "reports:*") */
  def matchesWildcard(pattern: String, code: String): Boolean =
    if pattern.endsWith(":*") then code.startsWith(pattern.dropRight(1))
    else pattern == code

// ============================================================
// Связь пользователь-роль
// ============================================================

final case class UserRole(
    userId: UUID,
    roleId: UUID,
    assignedAt: Instant,
    assignedBy: UUID
) derives JsonCodec

// ============================================================
// Группы транспорта
// ============================================================

final case class VehicleGroup(
    id: UUID,
    companyId: UUID,
    name: String,
    description: Option[String],
    color: Option[String],
    icon: Option[String],
    parentId: Option[UUID],
    vehicleIds: List[Long],
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec

// ============================================================
// Доступ к группам
// ============================================================

enum AccessLevel:
  case View, Operate, Manage

object AccessLevel:
  given JsonEncoder[AccessLevel] = JsonEncoder[String].contramap(_.toString.toLowerCase)
  given JsonDecoder[AccessLevel] = JsonDecoder[String].mapOrFail { s =>
    AccessLevel.values.find(_.toString.equalsIgnoreCase(s)).toRight(s"Неизвестный уровень доступа: $s")
  }

final case class UserGroupAccess(
    userId: UUID,
    groupId: UUID,
    accessLevel: AccessLevel,
    grantedAt: Instant,
    grantedBy: UUID
) derives JsonCodec

// ============================================================
// Запросы API
// ============================================================

final case class CreateUserRequest(
    email: String,
    password: String,
    firstName: String,
    lastName: String,
    phone: Option[String],
    position: Option[String],
    roleId: UUID
) derives JsonCodec

final case class UpdateProfileRequest(
    firstName: Option[String],
    lastName: Option[String],
    phone: Option[String],
    position: Option[String],
    language: Option[String],
    timezone: Option[String]
) derives JsonCodec

final case class ChangePasswordRequest(
    currentPassword: String,
    newPassword: String
) derives JsonCodec

final case class CreateCompanyRequest(
    name: String,
    inn: Option[String],
    address: Option[String],
    phone: Option[String],
    email: Option[String],
    timezone: Option[String],
    subscriptionPlan: String,
    maxUsers: Int,
    maxVehicles: Int
) derives JsonCodec

final case class UpdateCompanyRequest(
    name: Option[String],
    inn: Option[String],
    address: Option[String],
    phone: Option[String],
    email: Option[String],
    website: Option[String],
    timezone: Option[String],
    language: Option[String]
) derives JsonCodec

final case class CreateGroupRequest(
    name: String,
    description: Option[String],
    color: Option[String],
    icon: Option[String],
    parentId: Option[UUID]
) derives JsonCodec

final case class CreateRoleRequest(
    name: String,
    displayName: String,
    description: Option[String],
    permissions: List[String]
) derives JsonCodec

final case class AssignRoleRequest(
    roleId: UUID
) derives JsonCodec

final case class GrantAccessRequest(
    userId: UUID,
    accessLevel: AccessLevel
) derives JsonCodec

// ============================================================
// Ответы API
// ============================================================

final case class UsersListResponse(
    total: Long,
    page: Int,
    pageSize: Int,
    users: List[UserSummary]
) derives JsonCodec

final case class UserSummary(
    id: UUID,
    email: String,
    firstName: String,
    lastName: String,
    roles: List[String],
    isActive: Boolean,
    lastLoginAt: Option[Instant]
) derives JsonCodec

final case class UserCreated(
    id: UUID,
    email: String
) derives JsonCodec

// ============================================================
// Аудит
// ============================================================

final case class AuditLogEntry(
    id: UUID,
    companyId: UUID,
    userId: UUID,
    userName: String,
    action: String,
    entityType: String,
    entityId: Option[String],
    details: Option[String],
    ipAddress: Option[String],
    createdAt: Instant
) derives JsonCodec

final case class AuditFilters(
    userId: Option[UUID],
    action: Option[String],
    entityType: Option[String],
    fromDate: Option[Instant],
    toDate: Option[Instant],
    page: Int,
    pageSize: Int
) derives JsonCodec

final case class AuditListResponse(
    total: Long,
    entries: List[AuditLogEntry]
) derives JsonCodec

// ============================================================
// Пагинация
// ============================================================

final case class Page[A](
    items: List[A],
    total: Long,
    page: Int,
    pageSize: Int
) derives JsonCodec
