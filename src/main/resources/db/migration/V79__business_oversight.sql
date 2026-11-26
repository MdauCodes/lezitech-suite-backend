-- V79: oversight of every business from the control panel, and the business always being told.

-- Messages LeziTech sends a business about what it pays or gets (an offer, free days, a plan change, a payment recorded,
-- a price change, a pause). They stay on the business's Home and Billing pages until "Got it", and are listed under
-- "Messages from Busynez". Fenced like other business tables: the control panel writes and reads them as that business.
CREATE TABLE tenant_notices (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind             varchar(30) NOT NULL,
    title            varchar(200) NOT NULL,
    body             text NOT NULL,
    created_by       varchar(120),
    created_at       timestamptz NOT NULL DEFAULT now(),
    seen_at          timestamptz,
    sent_email_at    timestamptz,
    sent_whatsapp_at timestamptz,
    email_to         varchar(200),
    email_status     varchar(12),
    whatsapp_to      varchar(30),
    whatsapp_text    text
);
CREATE INDEX ix_tenant_notices_tenant ON tenant_notices (tenant_id, created_at DESC);
ALTER TABLE tenant_notices ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_notices FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON tenant_notices
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON tenant_notices TO suite_app;

-- Notes the LeziTech team keeps about a business (several, each with author and time). Not fenced: only the control panel reads them.
CREATE TABLE tenant_notes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    admin_id    uuid,
    admin_name  varchar(120) NOT NULL,
    note        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_tenant_notes_tenant ON tenant_notes (tenant_id, created_at DESC);
GRANT SELECT, INSERT ON tenant_notes TO suite_app;
INSERT INTO tenant_notes (tenant_id, admin_name, note)
SELECT id, 'LeziTech team', admin_note FROM tenants WHERE admin_note IS NOT NULL AND btrim(admin_note) <> '';

-- An offer made for one business only (not everyone).
ALTER TABLE platform_offers ADD COLUMN tenant_id uuid REFERENCES tenants(id) ON DELETE CASCADE;
CREATE INDEX ix_platform_offers_tenant ON platform_offers (tenant_id) WHERE tenant_id IS NOT NULL;

-- Why and when a business was paused, and what it was before (so it goes back to that).
ALTER TABLE tenants ADD COLUMN suspended_reason varchar(300);
ALTER TABLE tenants ADD COLUMN suspended_at timestamptz;
ALTER TABLE tenants ADD COLUMN suspended_from varchar(10);

-- Who activated a payment, the reason, when it was paid, and the period it covered.
ALTER TABLE subscription_requests ADD COLUMN activated_by varchar(120);
ALTER TABLE subscription_requests ADD COLUMN activated_reason varchar(300);
ALTER TABLE subscription_requests ADD COLUMN paid_at timestamptz;
ALTER TABLE subscription_requests ADD COLUMN period_from timestamptz;
ALTER TABLE subscription_requests ADD COLUMN period_to timestamptz;
UPDATE subscription_requests SET paid_at = created_at WHERE status = 'PAID';
CREATE INDEX ix_subscription_requests_paid ON subscription_requests (paid_at) WHERE status = 'PAID';

-- A plan price that goes up reaches a paying business only at its bill after next: until that bill it keeps the price it had.
CREATE TABLE tenant_price_holds (
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    plan        varchar(20) NOT NULL,
    monthly     bigint NOT NULL,
    until_bill  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, plan)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_price_holds TO suite_app;

-- One payment code can be recorded for one paid plan payment only. If old data already has the same code twice, the migration stops
-- (so the app never runs without this guard) and names the codes, so the team can sort them out first. Checked on 26 Nov 2026:
-- production had no paid requests with codes, so this creates the index.
DO $$
DECLARE dupes text;
BEGIN
    SELECT string_agg(code || ' (' || n || ' times)', ', ') INTO dupes FROM (
        SELECT upper(mpesa_receipt) AS code, count(*) AS n FROM subscription_requests
        WHERE status = 'PAID' AND mpesa_receipt IS NOT NULL GROUP BY 1 HAVING count(*) > 1) d;
    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'Payment codes recorded more than once; fix these before deploying: %', dupes;
    END IF;
END $$;
CREATE UNIQUE INDEX ux_subscription_requests_paid_code ON subscription_requests (upper(mpesa_receipt)) WHERE status = 'PAID' AND mpesa_receipt IS NOT NULL;

-- Notices to send in the background (a price change or an offer ending reaches many businesses; the request that caused it does not wait).
CREATE TABLE notice_jobs (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        varchar(20) NOT NULL,
    plan        varchar(20) NOT NULL,
    was         bigint NOT NULL,
    now_price   bigint NOT NULL,
    ends_on     date,
    by_name     varchar(160),
    created_at  timestamptz NOT NULL DEFAULT now(),
    started_at  timestamptz,
    done_at     timestamptz,
    told        integer NOT NULL DEFAULT 0
);
CREATE INDEX ix_notice_jobs_open ON notice_jobs (created_at) WHERE done_at IS NULL;
-- A plan offer that ends on its own date is announced once, 7 days before.
CREATE UNIQUE INDEX ux_notice_jobs_ending ON notice_jobs (plan, ends_on) WHERE kind = 'OFFER_ENDING';
GRANT SELECT, INSERT, UPDATE, DELETE ON notice_jobs TO suite_app;
