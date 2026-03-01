> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

# User Service

## Что делает

Управление пользователями, компаниями, ролями и правами доступа (RBAC).  
Мультитенантность по `organization_id` / `company_id`.

## Порт

**8091**

## Технический стек

- Scala 3.4.0, ZIO 2.0.20, zio-http 3.0.0-RC4
- PostgreSQL (schema `users`) через Doobie
- Redis (кэш прав, профилей, групп)
- BCrypt (хэширование паролей)

## Запуск

```bash
cd services/user-service
sbt run
```

## Docker

```bash
docker build -t user-service .
docker run -p 8091:8091 user-service
```

## Health check

```
GET http://localhost:8091/health
```

## Связи

- Читает: —
- Публикует: —
- Потребляет: —
- Зависимости: PostgreSQL, Redis
