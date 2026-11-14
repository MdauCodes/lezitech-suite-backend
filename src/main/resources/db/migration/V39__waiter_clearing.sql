-- V39: a waiter clears at the end of their shift (Kenyan practice).
-- Each waiter answers for every bill they served: they hand the cashier the cash they collected, and any bill still unpaid
-- and any cash short is recorded against them, to be paid back or taken from their salary at month end.
CREATE TABLE waiter_clearings (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    waiter_id      uuid NOT NULL,
    cleared_by     uuid NOT NULL,                 -- the cashier or manager who counted the cash
    from_at        timestamptz NOT NULL,
    to_at          timestamptz NOT NULL,
    bills          integer NOT NULL DEFAULT 0,
    sales_total    numeric(14,2) NOT NULL DEFAULT 0,
    mpesa          numeric(14,2) NOT NULL DEFAULT 0,
    card           numeric(14,2) NOT NULL DEFAULT 0,
    cash_expected  numeric(14,2) NOT NULL DEFAULT 0,
    cash_received  numeric(14,2) NOT NULL DEFAULT 0,
    short          numeric(14,2) NOT NULL DEFAULT 0,  -- cash short (negative = extra)
    unpaid_total   numeric(14,2) NOT NULL DEFAULT 0,  -- bills still unpaid, now owed by the waiter
    note           varchar(300),
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_waiter_clearings_waiter ON waiter_clearings (tenant_id, waiter_id, to_at DESC);

-- What a staff member owes the business, and how it was settled. Signed: owed (+), settled (−).
CREATE TABLE staff_debts (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL,
    kind         varchar(12) NOT NULL,   -- SHORTAGE | UNPAID_BILL | PAID_BACK | SALARY | WRITTEN_OFF
    amount       numeric(14,2) NOT NULL,
    order_id     uuid REFERENCES orders(id) ON DELETE SET NULL,
    clearing_id  uuid REFERENCES waiter_clearings(id) ON DELETE SET NULL,
    note         varchar(300),
    by_user      uuid,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_staff_debts_user ON staff_debts (tenant_id, user_id, created_at DESC);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['waiter_clearings','staff_debts']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
