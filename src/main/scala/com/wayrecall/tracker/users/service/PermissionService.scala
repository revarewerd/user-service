package com.wayrecall.tracker.users.service

import com.wayrecall.tracker.users.cache.PermissionCache
import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.repository.RoleRepository
import zio.*
import java.util.UUID

// ============================================================
// Сервис проверки прав (RBAC)
// ============================================================

trait PermissionService:
  /** Проверить, есть ли у пользователя конкретное разрешение */
  def hasPermission(userId: UUID, permission: String): Task[Boolean]
  /** Получить все разрешения пользователя (из кэша или БД) */
  def getUserPermissions(userId: UUID): Task[Set[String]]
  /** Может ли актор назначить целевую роль (проверка уровня) */
  def canAssignRole(actorId: UUID, targetRoleLevel: Int): Task[Boolean]
  /** Получить уровень роли пользователя */
  def getUserRoleLevel(userId: UUID): Task[Int]

object PermissionService:
  val live: ZLayer[RoleRepository & PermissionCache, Nothing, PermissionService] =
    ZLayer.fromFunction { (roleRepo: RoleRepository, cache: PermissionCache) =>
      new PermissionServiceLive(roleRepo, cache)
    }

final class PermissionServiceLive(
    roleRepo: RoleRepository,
    cache: PermissionCache
) extends PermissionService:

  override def hasPermission(userId: UUID, permission: String): Task[Boolean] =
    getUserPermissions(userId).map { perms =>
      perms.exists(p => Permission.matchesWildcard(p, permission))
    }

  override def getUserPermissions(userId: UUID): Task[Set[String]] =
    for {
      // Пробуем из кэша
      cached <- cache.getPermissions(userId)
      perms <- cached match
        case Some(p) => ZIO.succeed(p)
        case None =>
          for {
            // Загружаем из БД
            roles <- roleRepo.getUserRoles(userId)
            allPerms = roles.flatMap(_.permissions).toSet
            // Записываем в кэш
            _ <- cache.setPermissions(userId, allPerms)
          } yield allPerms
    } yield perms

  override def canAssignRole(actorId: UUID, targetRoleLevel: Int): Task[Boolean] =
    getUserRoleLevel(actorId).map(_ <= targetRoleLevel)

  override def getUserRoleLevel(userId: UUID): Task[Int] =
    roleRepo.getUserRoleLevel(userId).map(_.getOrElse(Int.MaxValue))
