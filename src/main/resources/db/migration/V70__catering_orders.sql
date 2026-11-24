-- V70: catering orders (events, offices, funerals, weddings) for restaurants and canteens.
-- The order carries the menu as lines. Money is never kept here: the first payment (or the event day) opens an ordinary
-- bill (order_id) and every payment lands on that bill, so it shows in the day's takings and reports like any sale.
CREATE TABLE catering_orders (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token           varchar(40) NOT NULL UNIQUE,
    status          varchar(12) NOT NULL DEFAULT 'BOOKED',   -- BOOKED | PREPARING | DELIVERED | CANCELLED
    name            varchar(80) NOT NULL,
    phone           varchar(20) NOT NULL,
    event_at        timestamptz NOT NULL,
    place           varchar(300) NOT NULL DEFAULT '',
    people          integer NOT NULL DEFAULT 1,
    notes           varchar(500),
    total           numeric(14,2) NOT NULL DEFAULT 0,
    order_id        uuid REFERENCES orders(id) ON DELETE SET NULL,   -- the bill, opened with the first payment
    sent_to_kitchen_at timestamptz,
    cancel_reason   varchar(200),
    created_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_catering_orders_event ON catering_orders (tenant_id, event_at);

CREATE TABLE catering_lines (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    catering_id     uuid NOT NULL REFERENCES catering_orders(id) ON DELETE CASCADE,
    item_id         uuid REFERENCES items(id) ON DELETE SET NULL,
    name            varchar(200) NOT NULL,
    per_person      boolean NOT NULL DEFAULT false,   -- true: qty is per person, so the line is qty x people
    qty             numeric(10,2) NOT NULL,
    unit_price      numeric(14,2) NOT NULL,           -- price each (per head when per_person), editable
    line_total      numeric(14,2) NOT NULL
);
CREATE INDEX ix_catering_lines_order ON catering_lines (catering_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['catering_orders','catering_lines']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
