-- Control panel: a 6-digit emailed code at every sign-in, and again before opening a business (step-up).
-- Only a bcrypt hash of the code is kept. Not fenced: used by LeziTech staff before any business is chosen.
CREATE TABLE admin_codes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    uuid NOT NULL REFERENCES platform_admins(id) ON DELETE CASCADE,
    kind        varchar(10) NOT NULL CHECK (kind IN ('signin', 'stepup')),
    tenant_id   uuid REFERENCES tenants(id) ON DELETE CASCADE,
    code_hash   varchar(80) NOT NULL,
    attempts    int NOT NULL DEFAULT 0,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_codes_admin ON admin_codes (admin_id, kind, created_at DESC);
GRANT SELECT, INSERT, UPDATE ON admin_codes TO suite_app;

-- Where and how each LeziTech person last signed in (every sign-in is also in the platform audit).
ALTER TABLE platform_admins ADD COLUMN last_login_ip varchar(64);
ALTER TABLE platform_admins ADD COLUMN last_login_device varchar(20);

-- LeziTech support looking at a business with no grant from the owner (look only, at most 2 hours).
-- Not fenced: the control panel writes it before signing in as the business; code always filters by business.
CREATE TABLE support_looks (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    admin_id    uuid NOT NULL REFERENCES platform_admins(id) ON DELETE CASCADE,
    admin_name  varchar(120) NOT NULL,
    reason      varchar(200) NOT NULL,
    started_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    ended_at    timestamptz
);
CREATE INDEX ix_support_looks_active ON support_looks (tenant_id, expires_at DESC);
GRANT SELECT, INSERT, UPDATE ON support_looks TO suite_app;

-- LeziTech support asking the owner for help access (the owner sees it in the app: Allow or Not now).
-- Not fenced, same reason as support_grants.
CREATE TABLE support_requests (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    admin_id    uuid NOT NULL REFERENCES platform_admins(id) ON DELETE CASCADE,
    admin_name  varchar(120) NOT NULL,
    reason      varchar(200) NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    outcome     varchar(10)
);
CREATE INDEX ix_support_requests_open ON support_requests (tenant_id, created_at DESC);
GRANT SELECT, INSERT, UPDATE ON support_requests TO suite_app;
