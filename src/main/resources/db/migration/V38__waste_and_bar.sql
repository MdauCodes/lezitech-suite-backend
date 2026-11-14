-- V38: waste and the bar (restaurant package R3).
-- Where an ingredient is kept, so the kitchen and the bar each count their own: KITCHEN | BAR.
ALTER TABLE items ADD COLUMN area varchar(10) NOT NULL DEFAULT 'KITCHEN';

-- Waste: what was thrown away, spilled, sent back or eaten by staff, and what it cost.
CREATE TABLE waste_entries (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    item_id     uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    qty         numeric(14,3) NOT NULL CHECK (qty > 0),
    reason      varchar(20) NOT NULL,     -- BURNT | EXPIRED | DROPPED | SPILLED | RETURNED | STAFF_MEAL | OTHER
    note        varchar(200),
    value       numeric(14,2) NOT NULL DEFAULT 0,   -- at what the restaurant paid
    user_id     uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_waste_entries_day ON waste_entries (tenant_id, created_at DESC);

ALTER TABLE waste_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE waste_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON waste_entries
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON waste_entries TO suite_app;
