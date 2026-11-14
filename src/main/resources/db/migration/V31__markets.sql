-- Malls & property, package M4: markets (owner's decisions, 2026-11-14).
-- A stall is charged monthly (the usual, and the default), weekly or daily. Daily stalls are charged every day the market
-- opens (settings.rent.market.openDays), whether the trader comes or not; the collector can excuse a day. Collectors walk
-- the rows with a phone: cash, an M-Pesa prompt, or not paid. At day's end the office counts the collector's cash and any
-- shortfall shows. One-off tickets for casual traders are off unless the landlord switches them on.

ALTER TABLE leases ADD COLUMN frequency varchar(8) NOT NULL DEFAULT 'MONTHLY';   -- MONTHLY | WEEKLY | DAILY (rent = per period)

-- A daily or weekly stall charge is one bill for that day (weekly: the Monday of the week).
ALTER TABLE orders ADD COLUMN charge_day date;
CREATE UNIQUE INDEX ux_orders_charge_day ON orders (lease_id, charge_day) WHERE lease_id IS NOT NULL AND charge_day IS NOT NULL AND status <> 'VOID';

-- What each collector took on their round: stall payments in cash, and casual tickets.
CREATE TABLE market_collections (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    collector_id  uuid NOT NULL,
    collector_name varchar(120),
    day           date NOT NULL,
    kind          varchar(8) NOT NULL,                  -- STALL | TICKET
    lease_id      uuid REFERENCES leases(id) ON DELETE SET NULL,
    order_id      uuid REFERENCES orders(id) ON DELETE SET NULL,
    payer_name    varchar(120),
    amount        numeric(14,2) NOT NULL,
    method        varchar(8) NOT NULL DEFAULT 'CASH',
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_market_collections_day ON market_collections (tenant_id, day, collector_id);

-- The office's count of a collector's cash for a day.
CREATE TABLE market_cash_counts (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    collector_id  uuid NOT NULL,
    day           date NOT NULL,
    recorded      numeric(14,2) NOT NULL,
    counted       numeric(14,2) NOT NULL,
    difference    numeric(14,2) NOT NULL,                -- counted - recorded (negative = short)
    note          varchar(300),
    counted_by    uuid,
    counted_by_name varchar(120),
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (collector_id, day)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['market_collections','market_cash_counts']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
