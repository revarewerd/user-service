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
// Репозиторий пользователей
// ============================================================

trait UserRepository:
  def findById(id: UUID): Task[Option[User]]
  def findByEmail(email: String): Task[Option[User]]
  def findByCompany(companyId: UUID, page: Int, pageSize: Int, search: Option[String]): Task[(List[User], Long)]
  def create(user: User): Task[UUID]
  def update(user: User): Task[Unit]
  def deactivate(id: UUID): Task[Unit]
  def updateLastLogin(id: UUID): Task[Unit]
  def incrementFailedLogins(id: UUID): Task[Unit]
  def resetFailedLogins(id: UUID): Task[Unit]

object UserRepository:
  val live: ZLayer[Transactor[Task], Nothing, UserRepository] =
    ZLayer.fromFunction { (xa: Transactor[Task]) =>
      new UserRepositoryLive(xa)
    }

final class UserRepositoryLive(xa: Transactor[Task]) extends UserRepository:

  override def findById(id: UUID): Task[Option[User]] =
    sql"""
      SELECT id, company_id, email, password_hash, first_name, last_name,
             phone, position, language, timezone, is_active, email_verified,
             last_login_at, failed_login_attempts, created_at, updated_at
      FROM users.users WHERE id = $id
    """.query[User].option.transact(xa)

  override def findByEmail(email: String): Task[Option[User]] =
    sql"""
      SELECT id, company_id, email, password_hash, first_name, last_name,
             phone, position, language, timezone, is_active, email_verified,
             last_login_at, failed_login_attempts, created_at, updated_at
      FROM users.users WHERE email = $email
    """.query[User].option.transact(xa)

  override def findByCompany(companyId: UUID, page: Int, pageSize: Int, search: Option[String]): Task[(List[User], Long)] =
    val offset = (page - 1) * pageSize
    val searchFilter = search.map(s => s"%${s.toLowerCase}%")

    val countQuery = searchFilter match
      case Some(pattern) =>
        sql"""
          SELECT COUNT(*) FROM users.users
          WHERE company_id = $companyId
            AND (LOWER(first_name) LIKE $pattern OR LOWER(last_name) LIKE $pattern OR LOWER(email) LIKE $pattern)
        """.query[Long].unique
      case None =>
        sql"SELECT COUNT(*) FROM users.users WHERE company_id = $companyId".query[Long].unique

    val listQuery = searchFilter match
      case Some(pattern) =>
        sql"""
          SELECT id, company_id, email, password_hash, first_name, last_name,
                 phone, position, language, timezone, is_active, email_verified,
                 last_login_at, failed_login_attempts, created_at, updated_at
          FROM users.users
          WHERE company_id = $companyId
            AND (LOWER(first_name) LIKE $pattern OR LOWER(last_name) LIKE $pattern OR LOWER(email) LIKE $pattern)
          ORDER BY created_at DESC
          LIMIT $pageSize OFFSET $offset
        """.query[User].to[List]
      case None =>
        sql"""
          SELECT id, company_id, email, password_hash, first_name, last_name,
                 phone, position, language, timezone, is_active, email_verified,
                 last_login_at, failed_login_attempts, created_at, updated_at
          FROM users.users
          WHERE company_id = $companyId
          ORDER BY created_at DESC
          LIMIT $pageSize OFFSET $offset
        """.query[User].to[List]

    (for {
      total <- countQuery
      users <- listQuery
    } yield (users, total)).transact(xa)

  override def create(user: User): Task[UUID] =
    sql"""
      INSERT INTO users.users (id, company_id, email, password_hash, first_name, last_name,
             phone, position, language, timezone, is_active, email_verified,
             failed_login_attempts, created_at, updated_at)
      VALUES (${user.id}, ${user.companyId}, ${user.email}, ${user.passwordHash},
              ${user.firstName}, ${user.lastName}, ${user.phone}, ${user.position},
              ${user.language}, ${user.timezone}, ${user.isActive}, ${user.emailVerified},
              ${user.failedLoginAttempts}, ${user.createdAt}, ${user.updatedAt})
      RETURNING id
    """.query[UUID].unique.transact(xa)

  override def update(user: User): Task[Unit] =
    val now = Instant.now()
    sql"""
      UPDATE users.users SET
        first_name = ${user.firstName}, last_name = ${user.lastName},
        phone = ${user.phone}, position = ${user.position},
        language = ${user.language}, timezone = ${user.timezone},
        updated_at = $now
      WHERE id = ${user.id}
    """.update.run.transact(xa).unit

  override def deactivate(id: UUID): Task[Unit] =
    val now = Instant.now()
    sql"""
      UPDATE users.users SET is_active = false, updated_at = $now WHERE id = $id
    """.update.run.transact(xa).unit

  override def updateLastLogin(id: UUID): Task[Unit] =
    val now = Instant.now()
    sql"""
      UPDATE users.users SET last_login_at = $now, failed_login_attempts = 0, updated_at = $now WHERE id = $id
    """.update.run.transact(xa).unit

  override def incrementFailedLogins(id: UUID): Task[Unit] =
    val now = Instant.now()
    sql"""
      UPDATE users.users SET failed_login_attempts = failed_login_attempts + 1, updated_at = $now WHERE id = $id
    """.update.run.transact(xa).unit

  override def resetFailedLogins(id: UUID): Task[Unit] =
    val now = Instant.now()
    sql"""
      UPDATE users.users SET failed_login_attempts = 0, updated_at = $now WHERE id = $id
    """.update.run.transact(xa).unit

  // Doobie Read для User
  given Read[User] = Read[
    (UUID, UUID, String, String, String, String, Option[String], Option[String],
     String, String, Boolean, Boolean, Option[Instant], Int, Instant, Instant)
  ].map { case (id, companyId, email, passwordHash, firstName, lastName, phone, position,
                language, timezone, isActive, emailVerified, lastLoginAt, failedLogins, createdAt, updatedAt) =>
    User(id, companyId, email, passwordHash, firstName, lastName, phone, position,
         language, timezone, isActive, emailVerified, lastLoginAt, failedLogins, createdAt, updatedAt)
  }
