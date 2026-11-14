-- Malls & property, package M5: the tenant portal (owner's decisions, 2026-11-13).
-- A landlord's tenant signs in to their own page: pays rent by M-Pesa prompt, sees statements and documents, reports
-- problems with photos and follows the repair, reads the landlord's notices, and can give notice to move out.

-- A tenant's sign-in, per business (the same person renting in two malls has two). Looked up by email before anyone is
-- signed in, so not fenced by row-level security: code always filters by business.
CREATE TABLE renter_accounts (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    customer_id    uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    email          varchar(200) NOT NULL,
    name           varchar(200) NOT NULL,
    password_hash  varchar(100),
    confirmed_at   timestamptz,
    token_version  integer NOT NULL DEFAULT 0,
    active         boolean NOT NULL DEFAULT true,
    invited_at     timestamptz,
    last_login_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);
CREATE INDEX ix_renter_accounts_customer ON renter_accounts (customer_id);

-- 6-digit codes emailed to a tenant: first sign-in (from the landlord's invite or asked for by the tenant) and resets.
CREATE TABLE renter_codes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  uuid NOT NULL REFERENCES renter_accounts(id) ON DELETE CASCADE,
    purpose     varchar(10) NOT NULL,                -- INVITE | ACCESS
    code_hash   varchar(100) NOT NULL,
    attempts    integer NOT NULL DEFAULT 0,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_renter_codes_account ON renter_codes (account_id, created_at DESC);

-- Complaints and repairs. Reported by a tenant (from the portal) or by the landlord's staff (e.g. a common area).
CREATE TABLE maintenance_requests (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number         integer NOT NULL,
    lease_id       uuid REFERENCES leases(id) ON DELETE SET NULL,
    space_id       uuid REFERENCES spaces(id) ON DELETE SET NULL,
    customer_id    uuid REFERENCES customers(id) ON DELETE SET NULL,
    category       varchar(20) NOT NULL DEFAULT 'REPAIR',    -- REPAIR | WATER | POWER | SECURITY | CLEANING | NOISE | OTHER
    title          varchar(150) NOT NULL,
    details        text,
    priority       varchar(10) NOT NULL DEFAULT 'NORMAL',    -- NORMAL | URGENT
    status         varchar(12) NOT NULL DEFAULT 'NEW',       -- NEW | ASSIGNED | IN_PROGRESS | DONE | CLOSED | CANCELLED
    assigned_to    uuid REFERENCES users(id) ON DELETE SET NULL,
    cost           numeric(14,2),
    charged_order_id uuid REFERENCES orders(id) ON DELETE SET NULL,
    rating         integer CHECK (rating BETWEEN 1 AND 5),
    rating_comment varchar(500),
    reported_by    varchar(10) NOT NULL DEFAULT 'TENANT',    -- TENANT | STAFF
    reporter_name  varchar(200),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    done_at        timestamptz,
    UNIQUE (tenant_id, number)
);
CREATE INDEX ix_maintenance_open ON maintenance_requests (tenant_id, status, created_at DESC);

-- Each step of a request: a message, a status change, photos (file ids).
CREATE TABLE maintenance_updates (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    request_id   uuid NOT NULL REFERENCES maintenance_requests(id) ON DELETE CASCADE,
    author_kind  varchar(10) NOT NULL,                       -- TENANT | STAFF
    author_name  varchar(200),
    message      text,
    status       varchar(12),
    photos       jsonb NOT NULL DEFAULT '[]'::jsonb,
    internal     boolean NOT NULL DEFAULT false,             -- staff-only note, never shown to the tenant
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_maintenance_updates_req ON maintenance_updates (request_id, created_at);

-- Notices from the landlord: to everyone, one floor, or chosen leases.
CREATE TABLE property_notices (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title         varchar(150) NOT NULL,
    body          text NOT NULL,
    audience      varchar(10) NOT NULL DEFAULT 'ALL',        -- ALL | FLOOR | LEASES
    floor         varchar(20),
    lease_ids     jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_by    uuid,
    created_by_name varchar(120),
    emailed       integer NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_property_notices ON property_notices (tenant_id, created_at DESC);

-- Documents the landlord shares with a tenant (the signed lease, house rules, a receipt...).
CREATE TABLE lease_documents (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    lease_id    uuid NOT NULL REFERENCES leases(id) ON DELETE CASCADE,
    file_id     uuid NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    name        varchar(150) NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Notice to vacate given by the tenant from the portal (or recorded by the landlord).
ALTER TABLE leases ADD COLUMN vacate_on       date;
ALTER TABLE leases ADD COLUMN vacate_reason   varchar(300);
ALTER TABLE leases ADD COLUMN vacate_given_at timestamptz;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['maintenance_requests','maintenance_updates','property_notices','lease_documents']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON renter_accounts, renter_codes TO suite_app;
