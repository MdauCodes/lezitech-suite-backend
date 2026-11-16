-- A full stock-take: started for one place (and optionally one category), counted by one or more people on their
-- phones, then finished: every counted line becomes the stock, and the differences (missing / extra, at cost) are kept.
CREATE TABLE stock_counts (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number       int NOT NULL,
    location_id  uuid NOT NULL REFERENCES locations(id),
    category_id  uuid REFERENCES categories(id) ON DELETE SET NULL,
    blind        boolean NOT NULL DEFAULT true,       -- counters don't see what the records expect
    status       varchar(12) NOT NULL DEFAULT 'OPEN', -- OPEN | DONE | CANCELLED
    note         varchar(300),
    started_by   uuid,
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_by  uuid,
    finished_at  timestamptz,
    missing_value numeric(14,2),
    extra_value   numeric(14,2),
    UNIQUE (tenant_id, number)
);

CREATE TABLE stock_count_lines (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    count_id    uuid NOT NULL REFERENCES stock_counts(id) ON DELETE CASCADE,
    item_id     uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    counted     numeric(14,3),                        -- null: not counted yet (left as it is)
    counted_by  uuid,
    counted_at  timestamptz,
    expected    numeric(14,3),                        -- the records when the count was finished
    unit_cost   numeric(14,2),
    UNIQUE (count_id, item_id)
);
CREATE INDEX ix_stock_count_lines ON stock_count_lines (count_id);

ALTER TABLE stock_counts ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_counts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON stock_counts
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
ALTER TABLE stock_count_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_count_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON stock_count_lines
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON stock_counts, stock_count_lines TO suite_app;
