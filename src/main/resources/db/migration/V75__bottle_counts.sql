-- V75: the nightly bottle count. Items the owner wants counted every night, and each count with what was found.
ALTER TABLE items ADD COLUMN count_nightly boolean NOT NULL DEFAULT false;

CREATE TABLE bottle_counts (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    day            date NOT NULL,                       -- the business day it counts for
    location_id    uuid REFERENCES locations(id) ON DELETE SET NULL,
    counted_by     uuid,
    counted_by_name varchar(120),
    counted_at     timestamptz NOT NULL DEFAULT now(),
    missing_bottles numeric(14,3) NOT NULL DEFAULT 0,
    missing_value  numeric(14,2),                       -- at cost
    extra_value    numeric(14,2),
    note           varchar(300)
);
CREATE INDEX ix_bottle_counts_day ON bottle_counts (tenant_id, day DESC);

CREATE TABLE bottle_count_lines (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    count_id    uuid NOT NULL REFERENCES bottle_counts(id) ON DELETE CASCADE,
    item_id     uuid REFERENCES items(id) ON DELETE SET NULL,
    item_name   varchar(200) NOT NULL,
    expected    numeric(14,3) NOT NULL,                 -- in bottles
    counted     numeric(14,3) NOT NULL,                 -- in bottles
    difference  numeric(14,3) NOT NULL,                 -- counted minus expected
    value       numeric(14,2) NOT NULL DEFAULT 0        -- difference at cost (negative: missing)
);
CREATE INDEX ix_bottle_count_lines ON bottle_count_lines (count_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['bottle_counts', 'bottle_count_lines'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
