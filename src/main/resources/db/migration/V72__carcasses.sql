-- V72: carcasses. A carcass (or part) bought whole, then cut up into by-weight items, with waste and anything not accounted for.
CREATE TABLE carcasses (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind            varchar(40) NOT NULL,                 -- beef, goat, mutton, pork, chicken or typed
    supplier        varchar(120),
    weight_kg       numeric(10,3) NOT NULL CHECK (weight_kg > 0),
    cost            numeric(14,2) NOT NULL CHECK (cost >= 0),
    received_on     date NOT NULL,
    status          varchar(10) NOT NULL DEFAULT 'RECEIVED',   -- RECEIVED | CUT
    waste_kg        numeric(10,3) NOT NULL DEFAULT 0,
    unaccounted_kg  numeric(10,3) NOT NULL DEFAULT 0,
    note            varchar(200),
    user_id         uuid,
    cut_at          timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_carcasses_day ON carcasses (tenant_id, received_on DESC);

-- What each carcass was cut into: the kilos of each cut and the part of the cost it carries.
CREATE TABLE carcass_cuts (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    carcass_id  uuid NOT NULL REFERENCES carcasses(id) ON DELETE CASCADE,
    item_id     uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    kg          numeric(10,3) NOT NULL CHECK (kg > 0),
    cost_share  numeric(14,2) NOT NULL DEFAULT 0
);
CREATE INDEX ix_carcass_cuts_carcass ON carcass_cuts (carcass_id);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['carcasses', 'carcass_cuts'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
