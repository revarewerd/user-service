> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

# User Service — Data Model

## PostgreSQL: schema `users`

Таблицы: `companies`, `users`, `roles`, `user_roles`, `user_settings`, `vehicle_groups`, `user_group_access`, `audit_log`

Подробная DDL — см. `src/main/resources/db/migration/V1__users_schema.sql`

## Redis ключи

| Ключ | Тип | TTL | Описание |
|---|---|---|---|
| `user:perms:{userId}` | SET | 1h | Разрешения пользователя |
| `user:profile:{userId}` | JSON | 5m | Профиль пользователя |
| `user:groups:{userId}` | JSON | 1h | Группы и уровни доступа |
| `group:vehicles:{groupId}` | JSON | 1h | ID транспорта в группе |
| `user:vehicles:{userId}` | JSON | 1h | Агрегированный доступ к транспорту |

### Инвалидация

- Изменение роли → DEL `user:perms:*`, `user:profile:*`, `user:vehicles:*`
- Изменение группы → DEL `group:vehicles:*` + `user:vehicles:*` для всех участников
