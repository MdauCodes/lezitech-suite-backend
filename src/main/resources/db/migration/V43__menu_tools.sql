-- R6: menu tools.
-- Combos are items of kind COMBO: one price, and their parts kept as option groups whose choices name the dish
-- ({"itemId": ...}); ordering one adds the parts as KES 0 lines, so the kitchen and bar get each part and stock and
-- recipes use each part up.
-- Deals (by days and hours, the price fixed when ordered):
--   BUNDLE   these dishes on one bill for a set price ("burger + chips + soda for 850"), applied by BizNez itself
--   PERCENT  happy hour: % off chosen dishes or categories
--   PRICE    happy hour: a set price per dish
--   BOGO     buy N get M free among chosen dishes or categories
-- Meal times: a category can say when it's usually served (breakfast 6-11). Nothing is hidden; what's being served now
-- comes first on the menu.

CREATE TABLE menu_deals (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(80) NOT NULL,
    kind        varchar(10) NOT NULL CHECK (kind IN ('BUNDLE', 'PERCENT', 'PRICE', 'BOGO')),
    active      boolean NOT NULL DEFAULT true,
    days        varchar(3)[] NOT NULL DEFAULT '{}',      -- empty = every day
    time_from   time,                                    -- null = all day
    time_to     time,
    items       jsonb NOT NULL DEFAULT '[]'::jsonb,      -- BUNDLE [{itemId, qty}]; PRICE [{itemId, price}]; PERCENT/BOGO [{itemId}]
    categories  uuid[] NOT NULL DEFAULT '{}',            -- PERCENT/BOGO: whole categories
    percent     numeric(5,2),
    deal_price  numeric(14,2),
    buy         integer,
    get         integer,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_menu_deals_tenant ON menu_deals (tenant_id, active);

ALTER TABLE menu_deals ENABLE ROW LEVEL SECURITY;
ALTER TABLE menu_deals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON menu_deals
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON menu_deals TO suite_app;

ALTER TABLE order_lines ADD COLUMN deal_id uuid;          -- a "Deal: ..." line BizNez added (negative)
ALTER TABLE order_lines ADD COLUMN parent_line_id uuid;   -- a combo's part
ALTER TABLE order_lines ADD COLUMN happy_deal_id uuid;    -- priced by a happy-hour deal when ordered

ALTER TABLE categories ADD COLUMN serve_from time;
ALTER TABLE categories ADD COLUMN serve_to time;
