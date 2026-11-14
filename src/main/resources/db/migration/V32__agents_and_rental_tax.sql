-- Malls & property, package M6: property agents and rental tax (owner's decisions, 2026-11-14).
-- An agent collects rent for several landlords. Each landlord's commission is set on its own: a % of rent collected, a %
-- of rent billed, or a fixed monthly fee. A landlord's statement: rent billed and collected for their units, commission,
-- expenses paid for them, rental income tax (MRI) shown for residential units, what was paid out, and what's still owed
-- to them. Landlords get it by email on the 1st and through a private link (no password); the agent can also send the link
-- on WhatsApp. Company tenants' withholding-tax certificates are recorded against their bills.

CREATE TABLE landlords (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name             varchar(200) NOT NULL,
    phone            varchar(30),
    email            varchar(200),
    commission_kind  varchar(14) NOT NULL DEFAULT 'PCT_COLLECTED',   -- PCT_COLLECTED | PCT_BILLED | FIXED
    commission_value numeric(14,2) NOT NULL DEFAULT 10,
    payout_details   varchar(300),                                     -- e.g. "Equity 0123456789" or "M-Pesa 0722..."
    kra_pin          varchar(20),
    statement_token  varchar(64) NOT NULL DEFAULT replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''),
    active           boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_landlords_token ON landlords (statement_token);

ALTER TABLE spaces ADD COLUMN landlord_id uuid REFERENCES landlords(id) ON DELETE SET NULL;

-- Money the agent spent for a landlord (repairs, rates, cleaning...): comes off their payout.
CREATE TABLE landlord_expenses (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    landlord_id uuid NOT NULL REFERENCES landlords(id) ON DELETE CASCADE,
    space_id    uuid REFERENCES spaces(id) ON DELETE SET NULL,
    what        varchar(150) NOT NULL,
    amount      numeric(14,2) NOT NULL,
    spent_on    date NOT NULL,
    reference   varchar(60),
    user_id     uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Money the agent paid the landlord.
CREATE TABLE landlord_payouts (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    landlord_id uuid NOT NULL REFERENCES landlords(id) ON DELETE CASCADE,
    amount      numeric(14,2) NOT NULL,
    method      varchar(12) NOT NULL,
    reference   varchar(60),
    paid_on     date NOT NULL,
    note        varchar(200),
    user_id     uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- A month's statement, kept once it's sent (the figures are then fixed; later months build on them).
CREATE TABLE landlord_statements (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    landlord_id uuid NOT NULL REFERENCES landlords(id) ON DELETE CASCADE,
    period      varchar(7) NOT NULL,
    billed      numeric(14,2) NOT NULL,
    collected   numeric(14,2) NOT NULL,
    commission  numeric(14,2) NOT NULL,
    expenses    numeric(14,2) NOT NULL,
    net         numeric(14,2) NOT NULL,
    emailed_to  varchar(200),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (landlord_id, period)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['landlords','landlord_expenses','landlord_payouts','landlord_statements']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
