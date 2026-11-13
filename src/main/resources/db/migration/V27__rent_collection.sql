-- Malls & property, package M1 ("get paid"): money from tenants lands on their invoices by itself, oldest first; anything
-- extra stays on the lease as credit for the next invoices; statements; late penalties the owner can lift; email reminders.

-- The unit's M-Pesa account number. Blank means "the unit's name without spaces or symbols, in capitals" (G-12 → G12).
ALTER TABLE spaces ADD COLUMN account_code varchar(20);

-- Money paid ahead (or overpaid), used up by the next invoices; late-payment penalty rules (off unless the lease says so);
-- the secret in the tenant's statement link.
ALTER TABLE leases ADD COLUMN credit             numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE leases ADD COLUMN penalty_kind       varchar(8)    NOT NULL DEFAULT 'NONE';   -- NONE | FIXED | PERCENT
ALTER TABLE leases ADD COLUMN penalty_value      numeric(14,2) NOT NULL DEFAULT 0;        -- shillings, or % of what's unpaid
ALTER TABLE leases ADD COLUMN penalty_grace_days integer       NOT NULL DEFAULT 5;
ALTER TABLE leases ADD COLUMN statement_token    varchar(64);
UPDATE leases SET statement_token = replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '') WHERE statement_token IS NULL;
ALTER TABLE leases ALTER COLUMN statement_token SET DEFAULT replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
ALTER TABLE leases ALTER COLUMN statement_token SET NOT NULL;
CREATE UNIQUE INDEX ux_leases_statement_token ON leases (statement_token);

-- One row per sum of money received from a tenant (prompt, paybill, or recorded by hand). The money is spread over the
-- lease's open invoices as ordinary payments (payments.lease_receipt_id); whatever is left goes to leases.credit.
CREATE TABLE lease_receipts (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    lease_id    uuid NOT NULL REFERENCES leases(id) ON DELETE CASCADE,
    amount      numeric(14,2) NOT NULL,
    to_invoices numeric(14,2) NOT NULL DEFAULT 0,
    to_credit   numeric(14,2) NOT NULL DEFAULT 0,
    method      varchar(12) NOT NULL,                  -- MPESA | CASH | BANK | CHEQUE
    reference   varchar(60),
    source      varchar(10) NOT NULL,                  -- PROMPT | PAYBILL | MANUAL
    note        varchar(200),
    user_id     uuid,
    received_on date NOT NULL DEFAULT current_date,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_lease_receipts_lease ON lease_receipts (lease_id, created_at);
-- The same M-Pesa code can't be recorded twice for one business.
CREATE UNIQUE INDEX ux_lease_receipts_ref ON lease_receipts (tenant_id, method, reference) WHERE reference IS NOT NULL AND method = 'MPESA';

ALTER TABLE payments ADD COLUMN lease_receipt_id uuid REFERENCES lease_receipts(id) ON DELETE SET NULL;

-- A penalty is its own invoice on the lease, pointing at the late invoice it's for: at most one per late invoice.
ALTER TABLE orders ADD COLUMN penalty_for uuid REFERENCES orders(id) ON DELETE CASCADE;
CREATE UNIQUE INDEX ux_orders_penalty_for ON orders (penalty_for) WHERE penalty_for IS NOT NULL;

-- Prompts for a tenant's rent are filed against the lease, not one invoice.
ALTER TABLE mpesa_requests ADD COLUMN lease_id uuid;
-- Paybill payments put on a lease.
ALTER TABLE mpesa_c2b ADD COLUMN lease_id uuid;

-- Each reminder is sent once: claimed here first, so two servers can't both send it.
CREATE TABLE rent_reminders (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id    uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    kind        varchar(12) NOT NULL,                  -- BEFORE | DUE | AFTER_<days>
    channel     varchar(10) NOT NULL DEFAULT 'EMAIL',
    sent_to     varchar(200),
    status      varchar(10) NOT NULL,                  -- SENT | NO_EMAIL | OFF | FAILED
    error       varchar(300),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, kind, channel)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['lease_receipts','rent_reminders']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
