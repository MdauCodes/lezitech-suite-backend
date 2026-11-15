-- R7: bakery, cake orders, and grill by weight.

-- Bakery: items baked fresh each day; what's left at closing can be sold tomorrow at a lower price (a twin item,
-- "Bread (yesterday's)"), given away or thrown.
ALTER TABLE items ADD COLUMN baked_daily     boolean NOT NULL DEFAULT false;
ALTER TABLE items ADD COLUMN day_old_price   numeric(14,2);
ALTER TABLE items ADD COLUMN day_old_of      uuid REFERENCES items(id) ON DELETE CASCADE;
-- Grill: priced per kg, sold by the weight from the scale (the quantity is the kg).
ALTER TABLE items ADD COLUMN sold_by_weight  boolean NOT NULL DEFAULT false;

CREATE TABLE bake_logs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    day             date NOT NULL,
    item_id         uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    baked           numeric(14,3) NOT NULL DEFAULT 0,
    -- The closing count:
    expected        numeric(14,3),        -- what BizNez had on hand
    left_counted    numeric(14,3),
    missing         numeric(14,3),
    missing_value   numeric(14,2),
    leftover_action varchar(15),          -- SELL_TOMORROW | GIVE_AWAY | WASTE | KEEP
    counted_at      timestamptz,
    counted_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, day, item_id)
);

CREATE TABLE cake_orders (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token           varchar(40) NOT NULL UNIQUE,
    source          varchar(10) NOT NULL DEFAULT 'ONLINE',     -- ONLINE | STAFF
    status          varchar(15) NOT NULL,                      -- WAITING_DEPOSIT | CONFIRMED | BAKING | READY | COLLECTED | CANCELLED
    name            varchar(80) NOT NULL,
    phone           varchar(20) NOT NULL,
    email           varchar(200),
    flavour         varchar(80) NOT NULL,
    size_kg         numeric(6,2) NOT NULL,
    tiers           integer NOT NULL DEFAULT 1,
    message         varchar(120),                              -- the writing on the cake
    notes           varchar(500),                              -- colours, design, allergies
    design_file_id  uuid,
    price           numeric(14,2) NOT NULL,
    deposit_amount  numeric(14,2) NOT NULL DEFAULT 0,
    deposit_status  varchar(10) NOT NULL DEFAULT 'NONE',       -- NONE | WAITING | PAID | APPLIED
    deposit_receipt varchar(30),
    due_at          timestamptz NOT NULL,                      -- pickup or delivery time
    fulfil          varchar(10) NOT NULL DEFAULT 'PICKUP',     -- PICKUP | DELIVERY
    area_id         uuid,
    address         varchar(300),
    landmark        varchar(200),
    order_id        uuid REFERENCES orders(id) ON DELETE SET NULL,
    acknowledged_at timestamptz,
    reminded_at     timestamptz,
    cancel_reason   varchar(200),
    created_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_cake_orders_due ON cake_orders (tenant_id, due_at);

ALTER TABLE mpesa_requests ADD COLUMN cake_order_id uuid;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['bake_logs','cake_orders']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
