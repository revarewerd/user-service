package com.wayrecall.tracker.users.cache

import com.wayrecall.tracker.users.domain.*
import zio.*
import java.util.UUID

// ============================================================
// Кэш прав пользователя (in-memory на Ref, без Redis)
// ============================================================

trait PermissionCache:
  /** Получить кэшированные разрешения */
  def getPermissions(userId: UUID): UIO[Option[Set[String]]]
  /** Сохранить разрешения в кэш */
  def setPermissions(userId: UUID, permissions: Set[String]): UIO[Unit]
  /** Получить профиль из кэша */
  def getProfile(userId: UUID): UIO[Option[UserProfile]]
  /** Сохранить профиль */
  def setProfile(userId: UUID, profile: UserProfile): UIO[Unit]
  /** Получить доступные транспортные средства */
  def getVehicles(userId: UUID): UIO[Option[Map[String, List[Long]]]]
  /** Сохранить доступные транспортные средства */
  def setVehicles(userId: UUID, vehicles: Map[String, List[Long]]): UIO[Unit]
  /** Инвалидировать все кэши пользователя */
  def invalidateUser(userId: UUID): UIO[Unit]

object PermissionCache:
  val live: ZLayer[Any, Nothing, PermissionCache] =
    ZLayer {
      for {
        permsRef    <- Ref.make(Map.empty[UUID, Set[String]])
        profileRef  <- Ref.make(Map.empty[UUID, UserProfile])
        vehiclesRef <- Ref.make(Map.empty[UUID, Map[String, List[Long]]])
      } yield new PermissionCacheLive(permsRef, profileRef, vehiclesRef)
    }

final class PermissionCacheLive(
    permsRef: Ref[Map[UUID, Set[String]]],
    profileRef: Ref[Map[UUID, UserProfile]],
    vehiclesRef: Ref[Map[UUID, Map[String, List[Long]]]]
) extends PermissionCache:

  override def getPermissions(userId: UUID): UIO[Option[Set[String]]] =
    permsRef.get.map(_.get(userId))

  override def setPermissions(userId: UUID, permissions: Set[String]): UIO[Unit] =
    permsRef.update(_.updated(userId, permissions))

  override def getProfile(userId: UUID): UIO[Option[UserProfile]] =
    profileRef.get.map(_.get(userId))

  override def setProfile(userId: UUID, profile: UserProfile): UIO[Unit] =
    profileRef.update(_.updated(userId, profile))

  override def getVehicles(userId: UUID): UIO[Option[Map[String, List[Long]]]] =
    vehiclesRef.get.map(_.get(userId))

  override def setVehicles(userId: UUID, vehicles: Map[String, List[Long]]): UIO[Unit] =
    vehiclesRef.update(_.updated(userId, vehicles))

  override def invalidateUser(userId: UUID): UIO[Unit] =
    for {
      _ <- permsRef.update(_ - userId)
      _ <- profileRef.update(_ - userId)
      _ <- vehiclesRef.update(_ - userId)
    } yield ()
