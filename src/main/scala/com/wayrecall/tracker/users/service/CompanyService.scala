package com.wayrecall.tracker.users.service

import com.wayrecall.tracker.users.domain.*
import com.wayrecall.tracker.users.repository.CompanyRepository
import zio.*
import java.time.Instant
import java.util.UUID

// ============================================================
// Сервис управления компаниями
// ============================================================

trait CompanyService:
  def createCompany(request: CreateCompanyRequest): Task[Company]
  def getCompany(id: UUID): Task[Company]
  def updateCompany(actorId: UUID, companyId: UUID, request: UpdateCompanyRequest): Task[Unit]

object CompanyService:
  val live: ZLayer[CompanyRepository & PermissionService, Nothing, CompanyService] =
    ZLayer.fromFunction { (companyRepo: CompanyRepository, permService: PermissionService) =>
      new CompanyServiceLive(companyRepo, permService)
    }

final class CompanyServiceLive(
    companyRepo: CompanyRepository,
    permService: PermissionService
) extends CompanyService:

  override def createCompany(request: CreateCompanyRequest): Task[Company] =
    for {
      _ <- ZIO.logInfo(s"Создание компании: name=${request.name}, inn=${request.inn}")
      now = Instant.now()
      company = Company(
        id = UUID.randomUUID(),
        name = request.name,
        inn = request.inn,
        address = request.address,
        phone = request.phone,
        email = request.email,
        website = None,
        logoUrl = None,
        timezone = request.timezone.getOrElse("Europe/Moscow"),
        language = "ru",
        maxUsers = request.maxUsers,
        maxVehicles = request.maxVehicles,
        subscriptionPlan = request.subscriptionPlan,
        subscriptionExpiresAt = None,
        isActive = true,
        createdAt = now,
        updatedAt = now
      )
      _ <- companyRepo.create(company)
      _ <- ZIO.logInfo(s"Компания создана: id=${company.id}, name=${company.name}")
    } yield company

  override def getCompany(id: UUID): Task[Company] =
    companyRepo.findById(id).someOrFail(UserError.CompanyNotFound(id))

  override def updateCompany(actorId: UUID, companyId: UUID, request: UpdateCompanyRequest): Task[Unit] =
    for {
      _ <- ZIO.logInfo(s"Обновление компании: actor=$actorId, company=$companyId")
      hasPerms <- permService.hasPermission(actorId, "settings:edit")
      _ <- ZIO.when(!hasPerms)(ZIO.logWarning(s"Отказ в обновлении компании: actor=$actorId не имеет прав settings:edit"))
      _        <- ZIO.unless(hasPerms)(ZIO.fail(UserError.PermissionDenied("settings:edit", "Нет прав")))
      company  <- companyRepo.findById(companyId).someOrFail(UserError.CompanyNotFound(companyId))
      updated = company.copy(
        name = request.name.getOrElse(company.name),
        inn = request.inn.orElse(company.inn),
        address = request.address.orElse(company.address),
        phone = request.phone.orElse(company.phone),
        email = request.email.orElse(company.email),
        website = request.website.orElse(company.website),
        timezone = request.timezone.getOrElse(company.timezone),
        language = request.language.getOrElse(company.language)
      )
      _ <- companyRepo.update(updated)
      _ <- ZIO.logInfo(s"Компания обновлена: id=$companyId")
    } yield ()
