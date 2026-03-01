package com.wayrecall.tracker.users.config

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

final case class PostgresConfig(
    url: String,
    user: String,
    password: String,
    maxPoolSize: Int
)

final case class RedisConfig(
    host: String,
    port: Int
)

final case class CacheConfig(
    permissionsTtlSeconds: Int,
    profileTtlSeconds: Int,
    groupsTtlSeconds: Int
)

final case class BcryptConfig(
    rounds: Int
)

final case class ServerConfig(
    port: Int
)

final case class AppConfig(
    postgres: PostgresConfig,
    redis: RedisConfig,
    cache: CacheConfig,
    bcrypt: BcryptConfig,
    server: ServerConfig
)

object AppConfig:
  val live: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO(
      ZIO.config[AppConfig](
        deriveConfig[AppConfig].mapKey(toKebabCase).nested("user-service")
      )
    )
