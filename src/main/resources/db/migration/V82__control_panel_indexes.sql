-- V82: indexes for the control panel lists, searches and the paying/offer counts.
SET LOCAL lock_timeout = '5s';

-- Fast "contains" search on business name, address and email. pg_trgm is optional: if the database user may not add it,
-- the notice says so and the search still works (just without the index). This block never fails the migration.
DO $$
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'pg_trgm not added (%): search indexes skipped. Ask the database owner to run CREATE EXTENSION pg_trgm once.', SQLERRM;
    END;
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        BEGIN
            EXECUTE 'CREATE INDEX IF NOT EXISTS ix_tenants_name_trgm ON tenants USING gin (lower(name) gin_trgm_ops)';
            EXECUTE 'CREATE INDEX IF NOT EXISTS ix_tenants_slug_trgm ON tenants USING gin (lower(slug) gin_trgm_ops)';
            EXECUTE 'CREATE INDEX IF NOT EXISTS ix_users_email_trgm ON users USING gin (lower(email) gin_trgm_ops)';
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'Search indexes skipped: %', SQLERRM;
        END;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_tenants_created_id ON tenants (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_tenants_status ON tenants (status);
CREATE INDEX IF NOT EXISTS ix_tenants_sector ON tenants (sector);
CREATE INDEX IF NOT EXISTS ix_subreq_tenant_status ON subscription_requests (tenant_id, status);
CREATE INDEX IF NOT EXISTS ix_subreq_offer_tenant_status ON subscription_requests (offer_id, tenant_id, status);
CREATE INDEX IF NOT EXISTS ix_offers_active_ends ON platform_offers (active, ends_at);
CREATE INDEX IF NOT EXISTS ix_audit_tenant_time ON platform_audit (tenant_id, created_at DESC);
