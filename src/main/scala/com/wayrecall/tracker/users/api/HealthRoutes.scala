package com.wayrecall.tracker.users.api

import zio.*
import zio.http.*
import zio.json.*

// ============================================================
// Health Check маршруты
// ============================================================

object HealthRoutes:
  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "health" -> handler {
      Response.json("""{"status":"ok","service":"user-service"}""")
    },
    Method.GET / "ready" -> handler {
      Response.json("""{"status":"ready","service":"user-service"}""")
    }
  )
