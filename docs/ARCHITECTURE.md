> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

# User Service — Архитектура

## Компоненты

```mermaid
flowchart LR
  API[REST API<br>zio-http] --> US[UserService]
  API --> CS[CompanyService]
  API --> RS[RoleService]
  API --> GS[GroupService]
  API --> AS[AuditService]
  US --> UR[UserRepository]
  US --> PC[PermissionCache]
  CS --> CR[CompanyRepository]
  RS --> RR[RoleRepository]
  GS --> GR[VehicleGroupRepository]
  AS --> AR[AuditRepository]
  UR --> PG[(PostgreSQL)]
  CR --> PG
  RR --> PG
  GR --> PG
  AR --> PG
  PC --> RD[(Redis)]
```

## RBAC модель

- 6 системных ролей: super_admin (0), admin (10), manager (20), operator (30), dispatcher (40), viewer (50)
- Пользователь НЕ может назначить роль выше своей (level < свой level)
- 27 разрешений по 8 категориям
- Кэш прав в Redis (TTL 1h), инвалидация при смене роли

## ZIO Layer граф

```
Main → Server → Routes → Services → Repositories → Transactor
                                  → Cache → Redis
```
