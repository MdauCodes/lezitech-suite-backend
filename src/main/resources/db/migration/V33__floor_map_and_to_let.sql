-- Malls & property, package M7: floor map, the public "units to let" page, enquiries as leads, and the owner's own units
-- (owner's decisions, 2026-11-14).

-- Where a unit sits on its floor's map: grid cell (x, y, width, height) and/or a pin on the floor's plan image (% from
-- the left and top). How it shows on the to-let page. Whether the landlord uses it themselves (no bills).
ALTER TABLE spaces ADD COLUMN map_x integer;
ALTER TABLE spaces ADD COLUMN map_y integer;
ALTER TABLE spaces ADD COLUMN map_w integer NOT NULL DEFAULT 1;
ALTER TABLE spaces ADD COLUMN map_h integer NOT NULL DEFAULT 1;
ALTER TABLE spaces ADD COLUMN pin_x numeric(6,2);
ALTER TABLE spaces ADD COLUMN pin_y numeric(6,2);
ALTER TABLE spaces ADD COLUMN listed boolean NOT NULL DEFAULT true;          -- on the to-let page when vacant or leaving
ALTER TABLE spaces ADD COLUMN show_price boolean NOT NULL DEFAULT true;      -- false: "Price on request"
ALTER TABLE spaces ADD COLUMN listing_note varchar(300);
ALTER TABLE spaces ADD COLUMN owner_use boolean NOT NULL DEFAULT false;      -- used by the landlord: occupied, never billed

-- The landlord's own business renting a unit like any tenant (billed), marked so it reads clearly.
ALTER TABLE leases ADD COLUMN own_business boolean NOT NULL DEFAULT false;

-- An enquiry from the to-let page is a sales lead for a unit.
ALTER TABLE deals ADD COLUMN space_id uuid REFERENCES spaces(id) ON DELETE SET NULL;

-- A floor's plan image (an architect's plan or a photo), for pinning units on.
CREATE TABLE floor_plans (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    floor       varchar(20) NOT NULL,
    file_id     uuid NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, floor)
);

-- Photos of a unit for the to-let page.
CREATE TABLE space_photos (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    space_id    uuid NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    file_id     uuid NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    sort        integer NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['floor_plans','space_photos']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
