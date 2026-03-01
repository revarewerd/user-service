package com.wayrecall.tracker.users.repository

import com.wayrecall.tracker.users.domain.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import zio.*
import zio.interop.catz.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Репозиторий компаний
// ============================================================

trait CompanyRepository:
  def findById(id: UUID): Task[Option[Company]]
  def create(company: Company): Task[UUID]
  def update(company: Company): Task[Unit]
  def countUsers(companyId: UUID): Task[Long]
  def countVehicles(companyId: UUID): Task[Long]

object CompanyRepository:
  val live: ZLayer[Transactor[Task], Nothing, CompanyRepository] =
    ZLayer.fromFunction { (xa: Transactor[Task]) =>
      new CompanyRepositoryLive(xa)
    }

final class CompanyRepositoryLive(xa: Transactor[Task]) extends CompanyRepository:

  override def findById(id: UUID): Task[Option[Company]] =
    sql"""
      SELECT id, name, inn, address, phone, email, website, logo_url,
             timezone, language, max_users, max_vehicles, subscription_plan,
             subscription_expires_at, is_active, created_at, updated_at
      FROM users.companies WHERE id = $id
    """.query[Company].option.transact(xa)

  override def create(company: Company): Task[UUID] =
    sql"""
      INSERT INTO users.companies (id, name, inn, address, phone, email, website, logo_url,
             timezone, language, max_users, max_vehicles, subscription_plan,
             subscription_expires_at, is_active, created_at, updated_at)
      VALUES (${company.id}, ${company.name}, ${company.inn}, ${company.address},
              ${company.phone}, ${company.email}, ${company.website}, ${company.logoUrl},
              ${company.timezone}, ${company.language}, ${company.maxUsers}, ${company.maxVehicles},
              ${company.subscriptionPlan}, ${company.subscriptionExpiresAt}, ${company.isActive},
              ${company.createdAt}, ${company.updatedAt})
      RETURNING id
    """.query[UUID].unique.transact(xa)

  override def update(company: Company): Task[Unit] =
    val now = Instant.now()
    sql"""
      UPDATE users.companies SET
        name = ${company.name}, inn = ${company.inn}, address = ${company.address},
        phone = ${company.phone}, email = ${company.email}, website = ${company.website},
        logo_url = ${company.logoUrl}, timezone = ${company.timezone}, language = ${company.language},
        updated_at = $now
      WHERE id = ${company.id}
    """.update.run.transact(xa).unit

  override def countUsers(companyId: UUID): Task[Long] =
    sql"SELECT COUNT(*) FROM users.users WHERE company_id = $companyId AND is_active = true"
      .query[Long].unique.transact(xa)

  override def countVehicles(companyId: UUID): Task[Long] =
    // Заглушка — в реальности запрос в Device Manager через API
    ZIO.succeed(0L)

  given Read[Company] = Read[
    (UUID, String, Option[String], Option[String], Option[String], Option[String], Option[String], Option[String],
     String, String, Int, Int, String, Option[Instant], Boolean, Instant, Instant)
  ].map { case (id, name, inn, address, phone, email, website, logoUrl,
                timezone, language, maxUsers, maxVehicles, plan, expiresAt, isActive, createdAt, updatedAt) =>
    Company(id, name, inn, address, phone, email, website, logoUrl,
            timezone, language, maxUsers, maxVehicles, plan, expiresAt, isActive, createdAt, updatedAt)
  }
