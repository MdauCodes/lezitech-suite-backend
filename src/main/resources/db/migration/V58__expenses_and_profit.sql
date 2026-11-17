-- V58: business expenses and profit & loss (docs/plan-gaps.md, phase 1).

-- 1. What the business spends money on: rent, wages, electricity, transport... Cash taken out of a till to pay for
--    something is recorded against that till's session, so the cash count at closing still adds up.
CREATE TABLE expenses (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    spent_on        date NOT NULL DEFAULT current_date,
    category        varchar(60) NOT NULL,
    what            varchar(200) NOT NULL,
    amount          numeric(14,2) NOT NULL CHECK (amount > 0),
    method          varchar(10) NOT NULL DEFAULT 'CASH',   -- CASH | MPESA | BANK | CARD
    paid_to         varchar(120),
    reference       varchar(80),
    receipt_file_id uuid,
    location_id     uuid REFERENCES locations(id) ON DELETE SET NULL,
    shift_id        uuid REFERENCES shifts(id) ON DELETE SET NULL,   -- set when the cash came out of a till
    created_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_expenses_tenant ON expenses (tenant_id, spent_on DESC);
CREATE INDEX ix_expenses_shift ON expenses (shift_id) WHERE shift_id IS NOT NULL;
ALTER TABLE expenses ENABLE ROW LEVEL SECURITY;
ALTER TABLE expenses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON expenses
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON expenses TO suite_app;

-- 2. The buying price of what was sold, as it was at the moment of the sale, so profit doesn't change later when
--    the item's buying price is edited. Filled for every new line by the database (every path that adds lines).
ALTER TABLE order_lines ADD COLUMN unit_cost numeric(14,4);

CREATE OR REPLACE FUNCTION order_lines_fill_cost() RETURNS trigger AS $$
BEGIN
    IF NEW.unit_cost IS NULL AND NEW.item_id IS NOT NULL THEN
        SELECT i.cost INTO NEW.unit_cost FROM items i WHERE i.id = NEW.item_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER order_lines_fill_cost BEFORE INSERT ON order_lines
    FOR EACH ROW EXECUTE FUNCTION order_lines_fill_cost();

-- Lines already sold get today's buying price once, so old reports keep working the same way.
UPDATE order_lines l SET unit_cost = i.cost FROM items i WHERE i.id = l.item_id AND l.unit_cost IS NULL;
