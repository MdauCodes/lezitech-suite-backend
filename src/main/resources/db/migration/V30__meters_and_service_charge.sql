-- Malls & property, package M3: water and electricity meters, and service charge (owner's decisions, 2026-11-14).
-- Meters: the caretaker reads them (with a photo); usage goes on the tenant's next monthly bill, for the kinds the
-- landlord charges (water, electricity, both or none; per lease the landlord can switch one off). Prices are set per
-- kind for the whole property (settings.rent.utilities), with an optional price for one unit.
-- Service charge: fixed per lease (as before), a yearly budget shared by floor size, or included in the rent
-- (settings.rent.serviceMode). Budget years close with the difference carried into next year's budget.

ALTER TABLE spaces ADD COLUMN water_price numeric(14,2);      -- this unit's own price per unit used (blank = the property's)
ALTER TABLE spaces ADD COLUMN power_price numeric(14,2);

ALTER TABLE leases ADD COLUMN charge_water boolean;           -- blank = as the property is set; false = included for this tenant
ALTER TABLE leases ADD COLUMN charge_power boolean;

CREATE TABLE meter_readings (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    space_id        uuid NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    kind            varchar(6) NOT NULL,                       -- WATER | POWER
    reading         numeric(14,2) NOT NULL,
    previous        numeric(14,2),                             -- null: the first reading, which only sets the start
    used            numeric(14,2) NOT NULL DEFAULT 0,
    read_on         date NOT NULL,
    photo_file_id   uuid REFERENCES files(id) ON DELETE SET NULL,
    note            varchar(200),
    new_meter       boolean NOT NULL DEFAULT false,            -- meter replaced: counting starts again from this reading
    read_by         uuid,
    read_by_name    varchar(120),
    lease_id        uuid REFERENCES leases(id) ON DELETE SET NULL,
    billed_order_id uuid REFERENCES orders(id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_meter_readings_space ON meter_readings (space_id, kind, read_on DESC, created_at DESC);
CREATE INDEX ix_meter_readings_unbilled ON meter_readings (tenant_id) WHERE billed_order_id IS NULL;

-- Service-charge budget, one per year.
CREATE TABLE sc_budgets (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    year        integer NOT NULL,
    carried     numeric(14,2) NOT NULL DEFAULT 0,               -- from last year: + shortfall, - surplus
    status      varchar(8) NOT NULL DEFAULT 'OPEN',             -- OPEN | CLOSED
    closed_at   timestamptz,
    closed_note varchar(300),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, year)
);
CREATE TABLE sc_budget_lines (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    budget_id   uuid NOT NULL REFERENCES sc_budgets(id) ON DELETE CASCADE,
    name        varchar(100) NOT NULL,
    amount      numeric(14,2) NOT NULL,
    sort        integer NOT NULL DEFAULT 0
);
CREATE TABLE sc_expenses (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    budget_id   uuid NOT NULL REFERENCES sc_budgets(id) ON DELETE CASCADE,
    line_id     uuid REFERENCES sc_budget_lines(id) ON DELETE SET NULL,
    what        varchar(150) NOT NULL,
    amount      numeric(14,2) NOT NULL,
    spent_on    date NOT NULL,
    reference   varchar(60),
    user_id     uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['meter_readings','sc_budgets','sc_budget_lines','sc_expenses']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
