-- Platform tables: tenants and logins. No RLS here: login resolves a user by
-- email before any tenant is known, and tenants is the root of the hierarchy.
-- The app layer always filters users by the caller's tenant.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'suite_app') THEN
        CREATE ROLE suite_app LOGIN;
    END IF;
END $$;

CREATE TABLE tenants (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            varchar(63)  NOT NULL UNIQUE,
    name            varchar(200) NOT NULL,
    sector          varchar(30)  NOT NULL,
    sub_sector      varchar(60),
    products        text[]       NOT NULL DEFAULT '{POS,CRM,ERP}',
    status          varchar(20)  NOT NULL DEFAULT 'TRIAL',
    plan            varchar(20)  NOT NULL DEFAULT 'TRIAL',
    trial_ends_at   timestamptz  NOT NULL,
    paid_until      timestamptz,
    brand_color     varchar(9)   NOT NULL DEFAULT '#0f766e',
    logo_url        text,
    currency        varchar(3)   NOT NULL DEFAULT 'KES',
    phone           varchar(30),
    country         varchar(2)   NOT NULL DEFAULT 'KE',
    -- Per-tenant switches from the sector pack, editable in Settings.
    settings        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    order_seq       integer      NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email           varchar(200) NOT NULL UNIQUE,
    name            varchar(120) NOT NULL,
    password_hash   varchar(200) NOT NULL,
    role            varchar(20)  NOT NULL DEFAULT 'OWNER',
    pin_hash        varchar(200),
    active          boolean      NOT NULL DEFAULT true,
    created_at      timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_users_tenant ON users (tenant_id);

-- Upgrade requests from the paywall; the operator activates the plan after payment.
CREATE TABLE subscription_requests (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    plan            varchar(20)  NOT NULL,
    billing_cycle   varchar(10)  NOT NULL,
    amount          numeric(12,2) NOT NULL,
    mpesa_phone     varchar(30),
    status          varchar(20)  NOT NULL DEFAULT 'PENDING',
    created_at      timestamptz  NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE ON tenants, users, subscription_requests TO suite_app;
