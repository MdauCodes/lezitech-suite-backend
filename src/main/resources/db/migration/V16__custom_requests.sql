-- "Build something for me": the same list the team works from (setup requests), marked by kind.
-- SETUP  = set up Lezi Suite for my business (as before)
-- CUSTOM = build me something else: a website, a custom system, a mobile app, a connection to another system
ALTER TABLE setup_requests ADD COLUMN kind varchar(12) NOT NULL DEFAULT 'SETUP';
ALTER TABLE setup_requests ADD COLUMN wants text[] NOT NULL DEFAULT '{}';
ALTER TABLE setup_requests ADD COLUMN budget varchar(60);
ALTER TABLE setup_requests ADD COLUMN timeline varchar(60);
-- When an existing customer asks from inside the app: their business (tenant_id is the workspace we build for a new one).
ALTER TABLE setup_requests ADD COLUMN from_tenant_id uuid REFERENCES tenants(id) ON DELETE SET NULL;
CREATE INDEX ix_setup_requests_kind ON setup_requests (kind, created_at DESC);
