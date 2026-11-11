-- 1. Activity log: what staff did that affects money or stock, so the owner can find out later why profit was low.
--    severity: INFO (routine), NOTICE (worth a look), CONCERN (needs the owner's attention).
CREATE TABLE activity_log (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id     uuid,
    user_name   varchar(120),
    approved_by varchar(120),
    kind        varchar(30) NOT NULL,     -- PRICE_CHANGE | BELOW_LOWEST_PRICE | DISCOUNT | CUSTOM_ITEM | VOID | REFUND | ITEM_PRICE_EDIT | STOCK_COUNT | CASH_SHORT | CASH_OVER | ACCESS_CHANGE | STAFF_CHANGE
    severity    varchar(10) NOT NULL DEFAULT 'INFO',
    summary     text NOT NULL,
    order_id    uuid,
    item_id     uuid,
    amount      numeric(14,2),            -- money effect in the business's currency (e.g. given up below the shelf price)
    detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_activity_tenant ON activity_log (tenant_id, created_at DESC);
CREATE INDEX ix_activity_user ON activity_log (tenant_id, user_id, created_at DESC);
ALTER TABLE activity_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE activity_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON activity_log
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
-- Staff can add to the log but nobody can change or delete it from the app.
GRANT SELECT, INSERT ON activity_log TO suite_app;

-- 2. Emails sent in the background (receipts, invoices, quotations), retried a few times.
CREATE TABLE email_jobs (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id    uuid NOT NULL,
    to_email    varchar(200) NOT NULL,
    status      varchar(10) NOT NULL DEFAULT 'QUEUED',   -- QUEUED | SENDING | SENT | FAILED
    attempts    integer NOT NULL DEFAULT 0,
    last_error  text,
    created_by  uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    next_try_at timestamptz NOT NULL DEFAULT now(),
    sent_at     timestamptz
);
CREATE INDEX ix_email_jobs_due ON email_jobs (status, next_try_at);
GRANT SELECT, INSERT, UPDATE ON email_jobs TO suite_app;

-- 3. Problems that need a person: shown to the business (Problems page, toast) and to LeziTech (control panel).
CREATE TABLE problems (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind        varchar(30) NOT NULL,     -- EMAIL_FAILED | ...
    title       varchar(200) NOT NULL,
    detail      text,
    ref_id      uuid,                     -- e.g. the email job to try again
    status      varchar(10) NOT NULL DEFAULT 'OPEN',   -- OPEN | RESOLVED
    created_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by varchar(120)
);
CREATE INDEX ix_problems_open ON problems (tenant_id, status, created_at DESC);
GRANT SELECT, INSERT, UPDATE ON problems TO suite_app;
