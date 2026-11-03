-- Demo workspaces for piloting, and the Lezitech control panel (platform admins, audit, activity).

ALTER TABLE tenants ADD COLUMN is_demo boolean NOT NULL DEFAULT false;
ALTER TABLE tenants ADD COLUMN last_active_at timestamptz;
ALTER TABLE tenants ADD COLUMN admin_note text;
ALTER TABLE users ADD COLUMN last_login_at timestamptz;

-- Lezitech staff. Separate from tenant users: they never belong to a workspace.
CREATE TABLE platform_admins (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         varchar(200) NOT NULL UNIQUE,
    name          varchar(120) NOT NULL,
    password_hash varchar(200) NOT NULL,
    role          varchar(20)  NOT NULL DEFAULT 'SUPPORT',   -- SUPERADMIN | ADMIN | SUPPORT | SALES | DEVELOPER
    active        boolean NOT NULL DEFAULT true,
    last_login_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Every control-panel action, who did it and to which workspace.
CREATE TABLE platform_audit (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    uuid REFERENCES platform_admins(id) ON DELETE SET NULL,
    admin_name  varchar(120),
    action      varchar(60) NOT NULL,
    tenant_id   uuid REFERENCES tenants(id) ON DELETE SET NULL,
    detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_time ON platform_audit (created_at DESC);

-- Platform-wide switches the control panel can flip (e.g. turn a sector off for new signups).
CREATE TABLE platform_settings (
    key         varchar(80) PRIMARY KEY,
    value       jsonb NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON platform_admins, platform_audit, platform_settings TO suite_app;
