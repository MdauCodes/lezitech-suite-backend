-- V80: Busynez Supply phase 1. A delivery round: the store loads, the driver accepts, the client's clerk confirms
-- what arrived (by an emailed link with a signature, or by a 4 digit code read to the driver).

-- Delivery note numbers (DN-1, DN-2 ...) count per business, the same way order numbers do.
ALTER TABLE tenants ADD COLUMN dn_seq integer NOT NULL DEFAULT 0;

CREATE TABLE delivery_runs (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    run_date         date NOT NULL,
    vehicle          varchar(60),
    driver_user_id   uuid NOT NULL REFERENCES users(id),
    driver_name      varchar(120) NOT NULL,
    status           varchar(8) NOT NULL DEFAULT 'LOADING' CHECK (status IN ('LOADING', 'OUT', 'BACK')),
    created_by       uuid,
    created_by_name  varchar(120),
    loaded_by        uuid,
    loaded_by_name   varchar(120),
    loaded_at        timestamptz,
    accepted_by_name varchar(120),
    accepted_at      timestamptz,
    back_at          timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_delivery_runs_day ON delivery_runs (tenant_id, run_date DESC);
CREATE INDEX ix_delivery_runs_driver ON delivery_runs (driver_user_id, status);

-- What goes on the vehicle: planned from the orders, then the count the storekeeper actually loaded.
CREATE TABLE delivery_run_lines (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    run_id      uuid NOT NULL REFERENCES delivery_runs(id) ON DELETE CASCADE,
    item_id     uuid NOT NULL REFERENCES items(id),
    name        varchar(200) NOT NULL,
    qty_planned numeric(14,3) NOT NULL,
    qty_loaded  numeric(14,3),
    UNIQUE (run_id, item_id)
);

-- One drop: one order, one delivery note. Only hashes of the link and the code are kept.
CREATE TABLE delivery_stops (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    run_id            uuid NOT NULL REFERENCES delivery_runs(id) ON DELETE CASCADE,
    sort              integer NOT NULL,
    note_no           integer NOT NULL,
    order_id          uuid NOT NULL REFERENCES orders(id),
    customer_name     varchar(200) NOT NULL,
    confirm_email     varchar(200),
    status            varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'EMAILED', 'CONFIRMED', 'PART')),
    token_hash        varchar(64),
    code_hash         varchar(64),
    code_tries        integer NOT NULL DEFAULT 0,
    sends             integer NOT NULL DEFAULT 0,
    emailed_at        timestamptz,
    expires_at        timestamptz,
    received_by_name  varchar(120),
    signature         text,
    confirmed_at      timestamptz,
    confirmed_via     varchar(5) CHECK (confirmed_via IN ('LINK', 'CODE')),
    cash_collected    numeric(14,2),
    UNIQUE (tenant_id, note_no)
);
CREATE INDEX ix_delivery_stops_run ON delivery_stops (run_id, sort);
CREATE INDEX ix_delivery_stops_order ON delivery_stops (order_id);

CREATE TABLE delivery_stop_lines (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    stop_id        uuid NOT NULL REFERENCES delivery_stops(id) ON DELETE CASCADE,
    pos            integer NOT NULL,
    order_line_id  uuid REFERENCES order_lines(id) ON DELETE SET NULL,
    item_id        uuid REFERENCES items(id) ON DELETE SET NULL,
    name           varchar(200) NOT NULL,
    qty_sent       numeric(14,3) NOT NULL,
    qty_received   numeric(14,3),
    qty_sent_back  numeric(14,3) NOT NULL DEFAULT 0,
    reason         varchar(200)
);
CREATE INDEX ix_delivery_stop_lines_stop ON delivery_stop_lines (stop_id, pos);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['delivery_runs', 'delivery_run_lines', 'delivery_stops', 'delivery_stop_lines'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;

-- The clerk's page has no login, so the link must be found before the business is known. This lookup holds only
-- the SHA-256 of the link (never the link) and the business it belongs to; everything else stays behind the fence.
CREATE TABLE delivery_links (
    token_hash  varchar(64) PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    stop_id     uuid NOT NULL REFERENCES delivery_stops(id) ON DELETE CASCADE,
    expires_at  timestamptz NOT NULL
);
CREATE INDEX ix_delivery_links_stop ON delivery_links (stop_id);
GRANT SELECT, INSERT, DELETE ON delivery_links TO suite_app;
