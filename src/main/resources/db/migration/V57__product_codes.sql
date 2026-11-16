-- Product codes (SKU): every product gets a short, readable code that's unique in its business, e.g. DRI-0012
-- (first letters of its category, else of its name, and a number). Sizes and colours get their product's code plus
-- the size, e.g. CLO-0004-M-RED (set by the app when sizes are saved). Owners can type their own codes; a code
-- can't be on two products of the same business.

ALTER TABLE tenants ADD COLUMN next_sku integer NOT NULL DEFAULT 1;

-- The code for a product saved without one. Runs as the app, inside the business's own rows.
CREATE OR REPLACE FUNCTION items_fill_sku() RETURNS trigger AS $$
DECLARE
    prefix text;
    n integer;
    code text;
BEGIN
    IF NEW.sku IS NOT NULL AND btrim(NEW.sku) <> '' THEN
        NEW.sku := btrim(NEW.sku);
        RETURN NEW;
    END IF;
    -- Emptied on an edit: keep the code it had.
    IF TG_OP = 'UPDATE' AND OLD.sku IS NOT NULL AND btrim(OLD.sku) <> '' THEN
        NEW.sku := OLD.sku;
        RETURN NEW;
    END IF;
    SELECT upper(left(regexp_replace(coalesce(c.name, ''), '[^A-Za-z]', '', 'g'), 3)) INTO prefix
    FROM categories c WHERE c.id = NEW.category_id;
    IF prefix IS NULL OR length(prefix) < 2 THEN
        prefix := upper(left(regexp_replace(coalesce(NEW.name, ''), '[^A-Za-z]', '', 'g'), 3));
    END IF;
    IF prefix IS NULL OR length(prefix) < 2 THEN
        prefix := 'ITM';
    END IF;
    LOOP
        UPDATE tenants SET next_sku = next_sku + 1 WHERE id = NEW.tenant_id RETURNING next_sku - 1 INTO n;
        code := prefix || '-' || lpad(n::text, 4, '0');
        EXIT WHEN NOT EXISTS (SELECT 1 FROM items WHERE tenant_id = NEW.tenant_id AND lower(sku) = lower(code));
    END LOOP;
    NEW.sku := code;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Codes typed twice in one business before this: the later ones get -2, -3... so every code is unique.
WITH d AS (
    SELECT id, row_number() OVER (PARTITION BY tenant_id, lower(btrim(sku)) ORDER BY created_at, id) AS k
    FROM items WHERE sku IS NOT NULL AND btrim(sku) <> ''
)
UPDATE items i SET sku = btrim(i.sku) || '-' || d.k FROM d WHERE d.id = i.id AND d.k > 1;

CREATE UNIQUE INDEX ux_items_sku ON items (tenant_id, lower(sku)) WHERE sku IS NOT NULL;

CREATE TRIGGER items_fill_sku BEFORE INSERT OR UPDATE OF sku ON items FOR EACH ROW EXECUTE FUNCTION items_fill_sku();

-- Every product that has no code yet gets one now (oldest first, so numbers follow the order they were added).
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT id FROM items WHERE sku IS NULL OR btrim(sku) = '' ORDER BY tenant_id, created_at, id LOOP
        UPDATE items SET sku = NULL WHERE id = r.id;
    END LOOP;
END $$;
