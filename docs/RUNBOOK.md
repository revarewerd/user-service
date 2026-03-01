> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

# User Service — Runbook

## Запуск

```bash
cd services/user-service
sbt run
# Порт: 8091
```

## Health check

```bash
curl http://localhost:8091/health
```

## Переменные окружения

| Переменная | Умолчание | Описание |
|---|---|---|
| `POSTGRES_URL` | `jdbc:postgresql://localhost:5432/tracker_users` | URL БД |
| `POSTGRES_USER` | `users` | Пользователь БД |
| `POSTGRES_PASSWORD` | `users_pass` | Пароль БД |
| `REDIS_HOST` | `localhost` | Redis хост |
| `REDIS_PORT` | `6379` | Redis порт |
| `SERVER_PORT` | `8091` | Порт HTTP сервера |

## Типичные проблемы

| Проблема | Решение |
|---|---|
| Права не обновляются | Проверить инвалидацию Redis: `DEL user:perms:{userId}` |
| Login timeout | BCrypt ~100ms — это нормально |
| Permission denied unexpectedly | Проверить `user:perms:{userId}` в Redis |
