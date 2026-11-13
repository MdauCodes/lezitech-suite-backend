-- Malls & property, package M2 ("the lease's life"): rent increases the owner approves, deposits billed and held (not
-- income), move-out with deductions and the deposit settled, lease-end alerts, renewals, and each lease's history.

-- Rent increases written in the lease: a % or a fixed amount every N months, from the 1st of the month the lease began.
ALTER TABLE leases ADD COLUMN increase_kind   varchar(8)    NOT NULL DEFAULT 'NONE';   -- NONE | PERCENT | FIXED
ALTER TABLE leases ADD COLUMN increase_value  numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE leases ADD COLUMN increase_every  integer       NOT NULL DEFAULT 12;       -- months
ALTER TABLE leases ADD COLUMN next_increase_on date;                                   -- always the 1st of a month

-- Deposit: what was already received before BizNez (no bill), and when it was settled at move-out.
ALTER TABLE leases ADD COLUMN deposit_before   numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE leases ADD COLUMN deposit_settled_on date;
ALTER TABLE leases ADD COLUMN moved_out_on     date;

-- One row per increase due: the owner approves it (possibly changing the amount) or skips it. Approved ones are applied
-- to the lease's rent from the month they're due; until then bills keep the old rent.
CREATE TABLE lease_increases (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    lease_id     uuid NOT NULL REFERENCES leases(id) ON DELETE CASCADE,
    due_on       date NOT NULL,
    old_rent     numeric(14,2) NOT NULL,
    new_rent     numeric(14,2) NOT NULL,
    status       varchar(10) NOT NULL DEFAULT 'PENDING',   -- PENDING | APPROVED | SKIPPED | APPLIED
    decided_by   uuid,
    decided_at   timestamptz,
    notice_status varchar(10),                             -- tenant's email notice: SENT | NO_EMAIL | FAILED
    applied_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (lease_id, due_on)
);

-- What happened to a lease over time, in plain words, for the lease's history.
CREATE TABLE lease_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    lease_id    uuid NOT NULL REFERENCES leases(id) ON DELETE CASCADE,
    kind        varchar(20) NOT NULL,      -- STARTED | INCREASE | RENEWED | MOVED_OUT | DEPOSIT_REFUND | ...
    summary     varchar(300) NOT NULL,
    detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
    user_id     uuid,
    user_name   varchar(120),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_lease_events_lease ON lease_events (lease_id, created_at);

-- Alerts to the owner (lease ending in 90/60/30 days, an increase to approve), each sent once.
CREATE TABLE lease_alerts (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    lease_id    uuid NOT NULL REFERENCES leases(id) ON DELETE CASCADE,
    kind        varchar(30) NOT NULL,      -- END_90:<date> | END_60:<date> | END_30:<date> | INCREASE:<date>
    status      varchar(10) NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (lease_id, kind)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['lease_increases','lease_events','lease_alerts']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;

-- Deposits already recorded on existing leases were typed in by hand: they count as received before BizNez.
UPDATE leases SET deposit_before = deposit WHERE deposit > 0;
