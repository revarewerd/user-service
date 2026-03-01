-- ============================================================
-- User Service: Миграция V1 — создание схемы users
-- Таблицы: companies, users, roles, user_roles,
--          vehicle_groups, user_group_access, audit_log
-- ============================================================

-- Отдельная схема для изоляции данных User Service
CREATE SCHEMA IF NOT EXISTS users;

-- ============================================================
-- Тип для уровня доступа к группам транспорта
-- ============================================================
CREATE TYPE users.access_level_type AS ENUM ('View', 'Operate', 'Manage');

-- ============================================================
-- Компании (организации / мульти-тенант)
-- ============================================================
CREATE TABLE users.companies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    inn             VARCHAR(20),
    kpp             VARCHAR(20),
    legal_address   TEXT,
    actual_address  TEXT,
    phone           VARCHAR(50),
    email           VARCHAR(255),
    contact_person  VARCHAR(255),
    timezone        VARCHAR(50) NOT NULL DEFAULT 'Europe/Moscow',
    locale          VARCHAR(10) NOT NULL DEFAULT 'ru',
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    max_users       INTEGER NOT NULL DEFAULT 10,
    max_vehicles    INTEGER NOT NULL DEFAULT 50,
    subscription_plan VARCHAR(50) NOT NULL DEFAULT 'basic',
    subscription_expires_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- Пользователи
-- ============================================================
CREATE TABLE users.users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID NOT NULL REFERENCES users.companies(id),
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    phone           VARCHAR(50),
    position        VARCHAR(255),
    avatar_url      TEXT,
    timezone        VARCHAR(50) NOT NULL DEFAULT 'Europe/Moscow',
    locale          VARCHAR(10) NOT NULL DEFAULT 'ru',
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at   TIMESTAMPTZ,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT users_email_unique UNIQUE (email)
);

CREATE INDEX idx_users_company ON users.users(company_id);
CREATE INDEX idx_users_email ON users.users(email);
CREATE INDEX idx_users_active ON users.users(is_active) WHERE is_active = TRUE;

-- ============================================================
-- Роли
-- ============================================================
CREATE TABLE users.roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID REFERENCES users.companies(id),  -- NULL = системная роль
    name        VARCHAR(100) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    description TEXT,
    permissions TEXT[] NOT NULL DEFAULT '{}',
    level       INTEGER NOT NULL DEFAULT 25,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT roles_name_company_unique UNIQUE (name, company_id)
);

-- ============================================================
-- Связь пользователей с ролями (М:М)
-- ============================================================
CREATE TABLE users.user_roles (
    user_id     UUID NOT NULL REFERENCES users.users(id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES users.roles(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    assigned_by UUID REFERENCES users.users(id),

    PRIMARY KEY (user_id, role_id)
);

-- ============================================================
-- Группы транспорта
-- ============================================================
CREATE TABLE users.vehicle_groups (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID NOT NULL REFERENCES users.companies(id),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    color       VARCHAR(7) DEFAULT '#3388ff',
    icon        VARCHAR(50),
    vehicle_ids BIGINT[] NOT NULL DEFAULT '{}',
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vehicle_groups_company ON users.vehicle_groups(company_id);

-- ============================================================
-- Доступ пользователей к группам транспорта
-- ============================================================
CREATE TABLE users.user_group_access (
    user_id      UUID NOT NULL REFERENCES users.users(id) ON DELETE CASCADE,
    group_id     UUID NOT NULL REFERENCES users.vehicle_groups(id) ON DELETE CASCADE,
    access_level users.access_level_type NOT NULL DEFAULT 'View',
    granted_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    granted_by   UUID REFERENCES users.users(id),

    PRIMARY KEY (user_id, group_id)
);

-- ============================================================
-- Лог аудита
-- ============================================================
CREATE TABLE users.audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID NOT NULL REFERENCES users.companies(id),
    user_id     UUID NOT NULL REFERENCES users.users(id),
    action      VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id   UUID,
    details     JSONB,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_company_date ON users.audit_log(company_id, created_at DESC);
CREATE INDEX idx_audit_user ON users.audit_log(user_id);

-- ============================================================
-- Системные роли (вставляем при первой миграции)
-- ============================================================
INSERT INTO users.roles (id, company_id, name, display_name, description, permissions, level, is_system) VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'super_admin', 'Супер-администратор', 'Полный доступ ко всем ресурсам системы',
     ARRAY['*'], 0, TRUE),
    ('00000000-0000-0000-0000-000000000002', NULL, 'company_admin', 'Администратор компании', 'Полный доступ в рамках своей организации',
     ARRAY['users.*', 'vehicles.*', 'geozones.*', 'reports.*', 'settings.*', 'audit.view'], 10, TRUE),
    ('00000000-0000-0000-0000-000000000003', NULL, 'dispatcher', 'Диспетчер', 'Управление транспортом и мониторинг',
     ARRAY['vehicles.view', 'vehicles.track', 'vehicles.command', 'geozones.view', 'reports.generate'], 20, TRUE),
    ('00000000-0000-0000-0000-000000000004', NULL, 'operator', 'Оператор', 'Просмотр данных без изменений',
     ARRAY['vehicles.view', 'vehicles.track', 'geozones.view', 'reports.view'], 30, TRUE),
    ('00000000-0000-0000-0000-000000000005', NULL, 'viewer', 'Наблюдатель', 'Только просмотр карты',
     ARRAY['vehicles.view', 'vehicles.track'], 40, TRUE),
    ('00000000-0000-0000-0000-000000000006', NULL, 'driver', 'Водитель', 'Доступ к своему маршруту',
     ARRAY['vehicles.view.own', 'reports.view.own'], 50, TRUE);

-- ============================================================
-- Представление: пользователи с ролями (для удобства запросов)
-- ============================================================
CREATE VIEW users.v_user_roles AS
SELECT
    u.id AS user_id,
    u.email,
    u.first_name,
    u.last_name,
    u.company_id,
    r.id AS role_id,
    r.name AS role_name,
    r.display_name AS role_display_name,
    r.permissions,
    r.level AS role_level
FROM users.users u
JOIN users.user_roles ur ON u.id = ur.user_id
JOIN users.roles r ON ur.role_id = r.id
WHERE u.is_active = TRUE;

-- ============================================================
-- Функция: получить все права пользователя (агрегация из всех ролей)
-- ============================================================
CREATE OR REPLACE FUNCTION users.get_user_permissions(p_user_id UUID)
RETURNS TEXT[] AS $$
    SELECT ARRAY(
        SELECT DISTINCT unnest(r.permissions)
        FROM users.user_roles ur
        JOIN users.roles r ON ur.role_id = r.id
        WHERE ur.user_id = p_user_id
    );
$$ LANGUAGE SQL STABLE;

-- ============================================================
-- Функция: проверить, имеет ли пользователь конкретное разрешение
-- ============================================================
CREATE OR REPLACE FUNCTION users.user_has_permission(p_user_id UUID, p_permission TEXT)
RETURNS BOOLEAN AS $$
    SELECT EXISTS (
        SELECT 1
        FROM users.user_roles ur
        JOIN users.roles r ON ur.role_id = r.id
        WHERE ur.user_id = p_user_id
          AND (
            p_permission = ANY(r.permissions)
            OR '*' = ANY(r.permissions)
            OR split_part(p_permission, '.', 1) || '.*' = ANY(r.permissions)
          )
    );
$$ LANGUAGE SQL STABLE;
