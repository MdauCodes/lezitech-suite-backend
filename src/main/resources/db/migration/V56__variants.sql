-- Product variants: one product (the "family", e.g. a T-shirt) in several sizes or colours. Each variant is its own
-- item row, so it has its own price, cost, barcode, stock, reorder level and pack, and the till, stock, buying and
-- reports work per variant unchanged. The family row holds the choices and isn't sold or stocked itself.
ALTER TABLE items ADD COLUMN variant_of     uuid REFERENCES items (id) ON DELETE CASCADE;
ALTER TABLE items ADD COLUMN variant_values jsonb;   -- on a variant: {"Size": "M", "Colour": "Red"}
ALTER TABLE items ADD COLUMN variant_axes   jsonb;   -- on the family: [{"name": "Size", "values": ["S", "M", "L"]}]
CREATE INDEX ix_items_variant_of ON items (variant_of) WHERE variant_of IS NOT NULL;
