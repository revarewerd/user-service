package com.wayrecall.tracker.users.infrastructure

import com.wayrecall.tracker.users.config.PostgresConfig
import doobie.hikari.HikariTransactor
import doobie.util.transactor.Transactor
import zio.*
import zio.interop.catz.*

object TransactorLayer:

  val live: ZLayer[PostgresConfig, Throwable, Transactor[Task]] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[PostgresConfig]
        xa <- HikariTransactor
          .newHikariTransactor[Task](
            driverClassName = "org.postgresql.Driver",
            url = config.url,
            user = config.user,
            pass = config.password,
            connectEC = scala.concurrent.ExecutionContext.global
          )
          .toScopedZIO
          .tap { xa =>
            ZIO.attemptBlocking {
              xa.kernel.setMaximumPoolSize(config.maxPoolSize)
            }
          }
      } yield xa
    }
