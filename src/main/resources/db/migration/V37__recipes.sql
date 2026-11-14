-- V37: recipes and ingredients (restaurant package R2).
-- Ingredients (items.kind = 'INGREDIENT') and batch recipes such as a pot of stew (items.kind = 'BATCH') live in items,
-- so they share stock levels, moves and counts; they are never sold or shown on a menu.
-- Stock is kept in the cooking unit (g, ml, pc). Deliveries come in the buying unit: one "bag" = buy_size grams.
ALTER TABLE items ADD COLUMN buy_unit          varchar(20);
ALTER TABLE items ADD COLUMN buy_size          numeric(14,3) CHECK (buy_size IS NULL OR buy_size > 0);
ALTER TABLE items ADD COLUMN buy_price         numeric(14,2) CHECK (buy_price IS NULL OR buy_price >= 0);
-- A batch recipe makes this much (in its own unit) each time, e.g. 10,000 ml of stew.
ALTER TABLE items ADD COLUMN batch_yield       numeric(14,3) CHECK (batch_yield IS NULL OR batch_yield > 0);
-- Set when BizNez marked a dish finished because an ingredient ran out; cleared when stock comes back.
ALTER TABLE items ADD COLUMN finished_by_stock boolean NOT NULL DEFAULT false;

-- What one dish (or one batch) uses. A line with option_group/option_name applies only when that choice is picked;
-- it adds its ingredient and, with replaces_id, drops that base ingredient ("Chicken instead" drops the beef).
CREATE TABLE recipe_lines (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    item_id       uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    ingredient_id uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    qty           numeric(14,3) NOT NULL CHECK (qty > 0),
    option_group  varchar(80),
    option_name   varchar(80),
    replaces_id   uuid REFERENCES items(id) ON DELETE SET NULL,
    sort          integer NOT NULL DEFAULT 0
);
CREATE INDEX ix_recipe_lines_item ON recipe_lines (item_id);
CREATE INDEX ix_recipe_lines_ingredient ON recipe_lines (ingredient_id);

ALTER TABLE recipe_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE recipe_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON recipe_lines
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON recipe_lines TO suite_app;
