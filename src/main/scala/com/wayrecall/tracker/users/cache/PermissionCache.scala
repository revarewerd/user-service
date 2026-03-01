package com.wayrecall.tracker.users.cache

import com.wayrecall.tracker.users.domain.*
import zio.*
import zio.json.*
import zio.redis.*
import java.util.UUID

// ============================================================
// Кэш прав пользователя в Redis
// ============================================================

trait PermissionCache:
  /** Получить кэшированные разрешения */
  def getPermissions(userId: UUID): UIO[Option[Set[String]]]
  /** Сохранить разрешения в кэш (TTL 1 час) */
  def setPermissions(userId: UUID, permissions: Set[String]): UIO[Unit]
  /** Получить профиль из кэша */
  def getProfile(userId: UUID): UIO[Option[UserProfile]]
  /** Сохранить профиль (TTL 5 мин) */
  def setProfile(userId: UUID, profile: UserProfile): UIO[Unit]
  /** Получить доступные транспортные средства */
  def getVehicles(userId: UUID): UIO[Option[Map[String, List[Long]]]]
  /** Сохранить доступные транспортные средства (TTL 1 час) */
  def setVehicles(userId: UUID, vehicles: Map[String, List[Long]]): UIO[Unit]
  /** Инвалидировать все кэши пользователя */
  def invalidateUser(userId: UUID): UIO[Unit]

object PermissionCache:
  val live: ZLayer[Redis, Nothing, PermissionCache] =
    ZLayer.fromFunction { (redis: Redis) =>
      new PermissionCacheLive(redis)
    }

final class PermissionCacheLive(redis: Redis) extends PermissionCache:

  private val permsTtl   = 3600.seconds  // 1 час
  private val profileTtl = 300.seconds   // 5 минут
  private val vehiclesTtl = 3600.seconds // 1 час

  override def getPermissions(userId: UUID): UIO[Option[Set[String]]] =
    redis
      .smembers(s"user:perms:$userId")
      .returning[String]
      .map { chunk =>
        if chunk.isEmpty then None
        else Some(chunk.toSet)
      }
      .orElseSucceed(None)

  override def setPermissions(userId: UUID, permissions: Set[String]): UIO[Unit] =
    val key = s"user:perms:$userId"
    (for {
      _ <- redis.del(key)
      _ <- ZIO.foreachDiscard(permissions)(p => redis.sAdd(key, p))
      _ <- redis.expire(key, permsTtl)
    } yield ()).orElseSucceed(())

  override def getProfile(userId: UUID): UIO[Option[UserProfile]] =
    redis
      .get(s"user:profile:$userId")
      .returning[String]
      .map(_.flatMap(_.fromJson[UserProfile].toOption))
      .orElseSucceed(None)

  override def setProfile(userId: UUID, profile: UserProfile): UIO[Unit] =
    (for {
      _ <- redis.set(s"user:profile:$userId", profile.toJson)
      _ <- redis.expire(s"user:profile:$userId", profileTtl)
    } yield ()).orElseSucceed(())

  override def getVehicles(userId: UUID): UIO[Option[Map[String, List[Long]]]] =
    redis
      .get(s"user:vehicles:$userId")
      .returning[String]
      .map(_.flatMap(_.fromJson[Map[String, List[Long]]].toOption))
      .orElseSucceed(None)

  override def setVehicles(userId: UUID, vehicles: Map[String, List[Long]]): UIO[Unit] =
    (for {
      _ <- redis.set(s"user:vehicles:$userId", vehicles.toJson)
      _ <- redis.expire(s"user:vehicles:$userId", vehiclesTtl)
    } yield ()).orElseSucceed(())

  override def invalidateUser(userId: UUID): UIO[Unit] =
    redis.del(
      s"user:perms:$userId",
      s"user:profile:$userId",
      s"user:groups:$userId",
      s"user:vehicles:$userId"
    ).orElseSucceed(0L).unit
