> Тег: `АКТУАЛЬНО` | Обновлён: `2026-03-01` | Версия: `1.0`

# User Service — REST API

## Профиль текущего пользователя

| Method | Path | Описание |
|---|---|---|
| GET | `/api/v1/users/me` | Получить свой профиль |
| PUT | `/api/v1/users/me` | Обновить профиль |
| PUT | `/api/v1/users/me/password` | Сменить пароль |
| GET | `/api/v1/users/me/settings` | Настройки |
| PUT | `/api/v1/users/me/settings` | Обновить настройки |

## Управление пользователями (admin)

| Method | Path | Описание |
|---|---|---|
| GET | `/api/v1/users` | Список пользователей |
| POST | `/api/v1/users` | Создать пользователя |
| GET | `/api/v1/users/{id}` | Получить пользователя |
| PUT | `/api/v1/users/{id}` | Обновить |
| DELETE | `/api/v1/users/{id}` | Деактивировать |
| PUT | `/api/v1/users/{id}/role` | Назначить роль |
| POST | `/api/v1/users/{id}/reset-password` | Сбросить пароль |

## Роли

| Method | Path | Описание |
|---|---|---|
| GET | `/api/v1/roles` | Список ролей |
| POST | `/api/v1/roles` | Создать кастомную роль |

## Группы транспорта

| Method | Path | Описание |
|---|---|---|
| GET | `/api/v1/groups` | Список групп |
| POST | `/api/v1/groups` | Создать группу |
| POST | `/api/v1/groups/{id}/access` | Назначить доступ |

## Компания

| Method | Path | Описание |
|---|---|---|
| GET | `/api/v1/company` | Информация о компании |
| PUT | `/api/v1/company` | Обновить |

## Аудит

| Method | Path | Описание |
|---|---|---|
| GET | `/api/v1/audit` | Лог аудита |
