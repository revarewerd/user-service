package com.wayrecall.tracker.users.domain

import zio.json.*
import java.util.UUID

// ============================================================
// Типизированные ошибки User Service
// ============================================================

sealed trait UserError extends Exception:
  def message: String
  override def getMessage: String = message

object UserError:

  /** Пользователь не найден */
  final case class UserNotFound(userId: UUID) extends UserError:
    val message = s"Пользователь не найден: $userId"

  /** Email уже зарегистрирован */
  final case class EmailAlreadyExists(email: String) extends UserError:
    val message = s"Email уже существует: $email"

  /** Недостаточно прав */
  final case class PermissionDenied(action: String, reason: String) extends UserError:
    val message = s"Доступ запрещён для '$action': $reason"

  /** Компания не найдена */
  final case class CompanyNotFound(companyId: UUID) extends UserError:
    val message = s"Компания не найдена: $companyId"

  /** Роль не найдена */
  final case class RoleNotFound(roleId: UUID) extends UserError:
    val message = s"Роль не найдена: $roleId"

  /** Группа не найдена */
  final case class GroupNotFound(groupId: UUID) extends UserError:
    val message = s"Группа не найдена: $groupId"

  /** Неверный пароль */
  final case class InvalidPassword(details: String) extends UserError:
    val message = s"Неверный пароль: $details"

  /** Неверные параметры запроса */
  final case class InvalidRequest(details: String) extends UserError:
    val message = s"Неверный запрос: $details"

  /** Ошибка БД */
  final case class DatabaseError(cause: String) extends UserError:
    val message = s"Ошибка БД: $cause"

  /** Стандартный ответ об ошибке */
  final case class ErrorResponse(error: String, message: String) derives JsonCodec
