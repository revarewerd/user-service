package com.wayrecall.tracker.users.cache

import com.wayrecall.tracker.users.domain.*
import zio.*
import zio.test.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Тесты кэша прав пользователя (PermissionCache.live — Ref-based)
// ============================================================

object PermissionCacheSpec extends ZIOSpecDefault:

  val testLayer = PermissionCache.live

  def spec = suite("PermissionCache")(
    permissionsCacheSuite,
    profileCacheSuite,
    vehiclesCacheSuite,
    invalidationSuite
  )

  // === Кэш разрешений ===

  val permissionsCacheSuite = suite("Permissions")(
    test("getPermissions возвращает None для неизвестного пользователя") {
      val userId = UUID.randomUUID()
      for {
        cache  <- ZIO.service[PermissionCache]
        result <- cache.getPermissions(userId)
      } yield assertTrue(result.isEmpty)
    }.provide(testLayer),

    test("setPermissions + getPermissions сохраняет и возвращает набор разрешений") {
      val userId = UUID.randomUUID()
      val perms  = Set("users:view", "vehicles:view", "reports:export")
      for {
        cache  <- ZIO.service[PermissionCache]
        _      <- cache.setPermissions(userId, perms)
        result <- cache.getPermissions(userId)
      } yield assertTrue(result == Some(perms))
    }.provide(testLayer),

    test("setPermissions перезаписывает предыдущие значения") {
      val userId = UUID.randomUUID()
      val perms1 = Set("users:view")
      val perms2 = Set("vehicles:edit", "geozones:create")
      for {
        cache   <- ZIO.service[PermissionCache]
        _       <- cache.setPermissions(userId, perms1)
        _       <- cache.setPermissions(userId, perms2)
        result  <- cache.getPermissions(userId)
      } yield assertTrue(result == Some(perms2))
    }.provide(testLayer),

    test("разные пользователи имеют независимые кэши разрешений") {
      val user1 = UUID.randomUUID()
      val user2 = UUID.randomUUID()
      val perms1 = Set("users:view")
      val perms2 = Set("vehicles:edit")
      for {
        cache <- ZIO.service[PermissionCache]
        _     <- cache.setPermissions(user1, perms1)
        _     <- cache.setPermissions(user2, perms2)
        r1    <- cache.getPermissions(user1)
        r2    <- cache.getPermissions(user2)
      } yield assertTrue(r1 == Some(perms1), r2 == Some(perms2))
    }.provide(testLayer),

    test("пустой набор разрешений корректно кэшируется") {
      val userId = UUID.randomUUID()
      for {
        cache  <- ZIO.service[PermissionCache]
        _      <- cache.setPermissions(userId, Set.empty)
        result <- cache.getPermissions(userId)
      } yield assertTrue(result == Some(Set.empty[String]))
    }.provide(testLayer)
  )

  // === Кэш профилей ===

  val profileCacheSuite = suite("Profile")(
    test("getProfile возвращает None для неизвестного пользователя") {
      val userId = UUID.randomUUID()
      for {
        cache  <- ZIO.service[PermissionCache]
        result <- cache.getProfile(userId)
      } yield assertTrue(result.isEmpty)
    }.provide(testLayer),

    test("setProfile + getProfile сохраняет и возвращает профиль") {
      val userId = UUID.randomUUID()
      val profile = makeProfile(userId)
      for {
        cache  <- ZIO.service[PermissionCache]
        _      <- cache.setProfile(userId, profile)
        result <- cache.getProfile(userId)
      } yield assertTrue(result == Some(profile))
    }.provide(testLayer),

    test("setProfile перезаписывает предыдущий профиль") {
      val userId = UUID.randomUUID()
      val profile1 = makeProfile(userId, firstName = "Иван")
      val profile2 = makeProfile(userId, firstName = "Пётр")
      for {
        cache  <- ZIO.service[PermissionCache]
        _      <- cache.setProfile(userId, profile1)
        _      <- cache.setProfile(userId, profile2)
        result <- cache.getProfile(userId)
      } yield assertTrue(result.map(_.firstName) == Some("Пётр"))
    }.provide(testLayer)
  )

  // === Кэш транспортных средств ===

  val vehiclesCacheSuite = suite("Vehicles")(
    test("getVehicles возвращает None для неизвестного пользователя") {
      val userId = UUID.randomUUID()
      for {
        cache  <- ZIO.service[PermissionCache]
        result <- cache.getVehicles(userId)
      } yield assertTrue(result.isEmpty)
    }.provide(testLayer),

    test("setVehicles + getVehicles сохраняет и возвращает карту") {
      val userId = UUID.randomUUID()
      val vehicles = Map("view" -> List(1L, 2L, 3L), "manage" -> List(4L, 5L))
      for {
        cache  <- ZIO.service[PermissionCache]
        _      <- cache.setVehicles(userId, vehicles)
        result <- cache.getVehicles(userId)
      } yield assertTrue(result == Some(vehicles))
    }.provide(testLayer),

    test("пустая карта транспортных средств кэшируется") {
      val userId = UUID.randomUUID()
      for {
        cache  <- ZIO.service[PermissionCache]
        _      <- cache.setVehicles(userId, Map.empty)
        result <- cache.getVehicles(userId)
      } yield assertTrue(result == Some(Map.empty[String, List[Long]]))
    }.provide(testLayer)
  )

  // === Инвалидация ===

  val invalidationSuite = suite("Invalidation")(
    test("invalidateUser удаляет все 3 типа кэша для пользователя") {
      val userId = UUID.randomUUID()
      val perms = Set("users:view")
      val profile = makeProfile(userId)
      val vehicles = Map("view" -> List(1L))
      for {
        cache <- ZIO.service[PermissionCache]
        // Заполняем все 3 кэша
        _     <- cache.setPermissions(userId, perms)
        _     <- cache.setProfile(userId, profile)
        _     <- cache.setVehicles(userId, vehicles)
        // Проверяем что всё на месте
        p1    <- cache.getPermissions(userId)
        pr1   <- cache.getProfile(userId)
        v1    <- cache.getVehicles(userId)
        _     = assertTrue(p1.isDefined, pr1.isDefined, v1.isDefined)
        // Инвалидируем
        _     <- cache.invalidateUser(userId)
        // Проверяем что всё удалено
        p2    <- cache.getPermissions(userId)
        pr2   <- cache.getProfile(userId)
        v2    <- cache.getVehicles(userId)
      } yield assertTrue(p2.isEmpty, pr2.isEmpty, v2.isEmpty)
    }.provide(testLayer),

    test("invalidateUser не затрагивает кэш других пользователей") {
      val user1 = UUID.randomUUID()
      val user2 = UUID.randomUUID()
      for {
        cache <- ZIO.service[PermissionCache]
        _     <- cache.setPermissions(user1, Set("a"))
        _     <- cache.setPermissions(user2, Set("b"))
        _     <- cache.invalidateUser(user1)
        r1    <- cache.getPermissions(user1)
        r2    <- cache.getPermissions(user2)
      } yield assertTrue(r1.isEmpty, r2 == Some(Set("b")))
    }.provide(testLayer),

    test("invalidateUser для несуществующего пользователя не падает") {
      val userId = UUID.randomUUID()
      for {
        cache <- ZIO.service[PermissionCache]
        _     <- cache.invalidateUser(userId)
      } yield assertTrue(true)
    }.provide(testLayer)
  )

  // === Вспомогательные методы ===

  private def makeProfile(
      userId: UUID,
      firstName: String = "Иван",
      lastName: String = "Иванов"
  ): UserProfile =
    UserProfile(
      id = userId,
      companyId = UUID.randomUUID(),
      email = s"${firstName.toLowerCase}@wayrecall.com",
      firstName = firstName,
      lastName = lastName,
      phone = None,
      position = None,
      language = "ru",
      timezone = "Europe/Moscow",
      roles = List("operator"),
      permissions = List("vehicles:view")
    )
