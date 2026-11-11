-- V19: notices on an order, for the diner's tracking page and the waiter's "My tables".
-- e.g. the kitchen took a dish off (finished), the diner said no to a substitute and wants a word, a diner asked
-- for help. A waiter marks a notice done.
CREATE TABLE order_notices (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id    uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    kind        varchar(20) NOT NULL,     -- ITEM_REMOVED | SUBSTITUTE_DECLINED | SUBSTITUTE_ACCEPTED
    message     varchar(300) NOT NULL,    -- in words the diner and the waiter both understand
    for_waiter  boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    done_at     timestamptz,
    done_by     uuid
);
CREATE INDEX ix_order_notices_order ON order_notices (order_id, created_at);
CREATE INDEX ix_order_notices_open ON order_notices (tenant_id, created_at DESC) WHERE done_at IS NULL;
ALTER TABLE order_notices ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_notices FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON order_notices
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON order_notices TO suite_app;
