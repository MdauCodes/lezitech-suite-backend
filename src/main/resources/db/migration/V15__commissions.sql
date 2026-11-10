-- Commissions for stylists, barbers, washers, trainers and mechanics.
-- A staff member's rate applies to the lines credited to them (order_lines.staff_id, or whoever rang the sale up);
-- a service or product can carry its own rate instead (e.g. 40% on braids, 10% on products).
ALTER TABLE users ADD COLUMN commission_rate numeric(5,2) CHECK (commission_rate IS NULL OR commission_rate BETWEEN 0 AND 100);
ALTER TABLE items ADD COLUMN commission_rate numeric(5,2) CHECK (commission_rate IS NULL OR commission_rate BETWEEN 0 AND 100);

-- What the owner has paid out, so the report shows what is still owed for a period.
CREATE TABLE commission_payouts (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period_from  date NOT NULL,
    period_to    date NOT NULL,
    amount       numeric(14,2) NOT NULL CHECK (amount > 0),
    method       varchar(12) NOT NULL DEFAULT 'CASH',   -- CASH | MPESA | BANK
    reference    varchar(80),
    note         text,
    paid_by      uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_commission_payouts_user ON commission_payouts (tenant_id, user_id, period_from);

ALTER TABLE commission_payouts ENABLE ROW LEVEL SECURITY;
ALTER TABLE commission_payouts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON commission_payouts
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON commission_payouts TO suite_app;
