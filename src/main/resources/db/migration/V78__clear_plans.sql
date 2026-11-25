-- V78: plans explained clearly. Every plan change is recorded (who, when, from, to); a higher plan mid-period adds a
-- proration line to the next bill; a lower plan waits for the next bill date; reminders go out once per bill.
-- Not fenced (like subscription_requests): the control panel and the reminder job read them across businesses, and
-- code always filters by business.

CREATE TABLE plan_changes (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    changed_by       uuid,
    changed_by_name  varchar(120),
    from_plan        varchar(20),
    to_plan          varchar(20) NOT NULL,
    kind             varchar(20) NOT NULL,            -- UPGRADE | DOWNGRADE | TRIAL_SETUP | CANCEL_DOWNGRADE
    reason           varchar(300),                    -- "Staff attendance", "More than 3 staff", "Chosen on the plans page"
    proration        numeric(12,0) NOT NULL DEFAULT 0,
    effective_at     timestamptz NOT NULL DEFAULT now(),
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_plan_changes_tenant ON plan_changes (tenant_id, created_at DESC);

-- Extra amounts waiting for the next bill. paid_request_id is set when the payment that included them is confirmed.
CREATE TABLE bill_lines (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    plan_change_id   uuid REFERENCES plan_changes(id) ON DELETE SET NULL,
    kind             varchar(12) NOT NULL DEFAULT 'PRORATION',
    label            varchar(300) NOT NULL,
    amount           numeric(12,0) NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    paid_request_id  uuid REFERENCES subscription_requests(id) ON DELETE SET NULL,
    paid_at          timestamptz
);
CREATE INDEX ix_bill_lines_tenant ON bill_lines (tenant_id, paid_at);

-- Reminders for paid plans: BEFORE3 (3 days before the bill date) and DAY (on it), once per bill date.
CREATE TABLE bill_reminders (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    bill_date     date NOT NULL,
    kind          varchar(10) NOT NULL,
    amount        numeric(12,0),
    email_to      varchar(200),
    email_status  varchar(12) NOT NULL,
    email_error   text,
    whatsapp_to   varchar(30),
    whatsapp_text text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, bill_date, kind)
);

-- A lower plan chosen now starts at the next bill date: next_plan is that plan, plan_switch_at the moment it starts
-- (set once the next bill is paid early; a business that pays on or after the date switches at once).
ALTER TABLE tenants ADD COLUMN next_plan varchar(20);
ALTER TABLE tenants ADD COLUMN plan_switch_at timestamptz;
-- When the paid period now running began (for working out days used). Empty: one month before paid_until.
ALTER TABLE tenants ADD COLUMN period_start timestamptz;
-- What a payment request was made of (plan, offer, proration lines), kept as the control panel shows it.
ALTER TABLE subscription_requests ADD COLUMN lines jsonb;

GRANT SELECT, INSERT, UPDATE, DELETE ON plan_changes, bill_lines, bill_reminders TO suite_app;

-- Why a request was closed (a plan change made it out of date), in words the owner can read.
ALTER TABLE subscription_requests ADD COLUMN note varchar(300);

-- Businesses already paying: work out the period now running (one month, or a year if their last payment was yearly)
-- so a move up uses the real period.
UPDATE tenants t SET period_start = t.paid_until - COALESCE(
        (SELECT CASE WHEN r.billing_cycle = 'ANNUAL' THEN interval '12 months' ELSE interval '1 month' END
         FROM subscription_requests r WHERE r.tenant_id = t.id AND r.status = 'PAID' ORDER BY r.created_at DESC LIMIT 1),
        interval '1 month')
WHERE t.status = 'ACTIVE' AND t.paid_until IS NOT NULL AND t.period_start IS NULL;
