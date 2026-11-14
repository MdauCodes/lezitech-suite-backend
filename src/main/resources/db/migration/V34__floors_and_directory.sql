-- Malls & property, package M8: floors for multi-storey buildings, and the mall directory (owner's decisions, 2026-11-14).
-- Floors are listed in their real order (basements, ground, 1st, 2nd...), and a floor's units can be made in one go.
-- The directory is a public page of the mall's shops; tenants keep their own shop's entry and offers up to date from
-- their tenant page, and the landlord approves what goes public (and can edit or hide anything).

CREATE TABLE building_floors (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(20) NOT NULL,                 -- matches spaces.floor
    sort        integer NOT NULL,                     -- lowest = bottom (basements negative)
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

-- One entry per occupied unit (a tenant's shop, or the landlord's own). "draft" holds a tenant's changes until approved.
CREATE TABLE directory_entries (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    space_id       uuid NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    lease_id       uuid REFERENCES leases(id) ON DELETE SET NULL,   -- whose entry it is (null: the landlord's own unit)
    display_name   varchar(120) NOT NULL,
    category       varchar(20) NOT NULL DEFAULT 'OTHER',
    description    varchar(300),
    phone          varchar(30),
    whatsapp       varchar(30),
    hours          varchar(120),
    logo_file_id   uuid REFERENCES files(id) ON DELETE SET NULL,
    hidden         boolean NOT NULL DEFAULT false,
    draft          jsonb,                                            -- tenant's changes waiting for the landlord
    draft_at       timestamptz,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (space_id)
);

CREATE TABLE directory_offers (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    entry_id    uuid NOT NULL REFERENCES directory_entries(id) ON DELETE CASCADE,
    title       varchar(120) NOT NULL,
    details     varchar(300),
    ends_on     date,
    status      varchar(10) NOT NULL DEFAULT 'PENDING',          -- PENDING | APPROVED | REJECTED
    created_at  timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['building_floors','directory_entries','directory_offers']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
