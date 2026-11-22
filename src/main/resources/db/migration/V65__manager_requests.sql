-- V65: things a junior member of staff asks a manager to do, e.g. "please cancel this bill".
-- A waiter, cashier or rider cannot cancel a bill themselves, so they ask. The bill is left exactly as it is
-- until a manager answers from their "Waiting for you" list. The manager can allow it (the bill is then
-- cancelled), or refuse it with a few words so the staff member knows what to do next.
CREATE TABLE manager_requests (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id        uuid REFERENCES orders(id) ON DELETE CASCADE,
    delivery_id     uuid,                          -- set when the ask came from a delivery that failed
    kind            varchar(24) NOT NULL,          -- CANCEL_BILL (more kinds later)
    message         varchar(300) NOT NULL,         -- in words the manager and the staff member both understand
    reason          varchar(200) NOT NULL,         -- why the staff member is asking
    asked_by        uuid,
    asked_by_name   varchar(120) NOT NULL DEFAULT '',
    asked_at        timestamptz NOT NULL DEFAULT now(),
    done_at         timestamptz,                   -- set when a manager has answered
    done_by         uuid,
    done_by_name    varchar(120),
    outcome         varchar(12),                   -- ALLOWED | REFUSED
    answer          varchar(200),                  -- the manager's words back to the staff member
    taken_over      boolean NOT NULL DEFAULT false, -- the manager said they will deal with it themselves
    seen_at         timestamptz                    -- the staff member has read the answer
);
CREATE INDEX ix_manager_requests_order ON manager_requests (order_id, asked_at);
CREATE INDEX ix_manager_requests_open ON manager_requests (tenant_id, asked_at DESC) WHERE done_at IS NULL;
-- One open ask per person per bill: a double tap can't make two, but if a second member of staff
-- asks about the same bill they get their own row, so they also get told the answer.
CREATE UNIQUE INDEX ux_manager_requests_one_open ON manager_requests (order_id, kind, asked_by) WHERE done_at IS NULL;
CREATE INDEX ix_manager_requests_asker ON manager_requests (asked_by, asked_at DESC);
ALTER TABLE manager_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE manager_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON manager_requests
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON manager_requests TO suite_app;
