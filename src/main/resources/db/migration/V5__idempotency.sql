-- One key per till action (a checkout, a payment). A retried or double-tapped request with the
-- same key returns the first result instead of charging twice.
CREATE TABLE idempotency_keys (
    tenant_id  uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    key        varchar(100) NOT NULL,
    order_id   uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, key)
);

ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON idempotency_keys
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_keys TO suite_app;
