-- V40: table bookings and the waiting list (restaurant package R4).
-- A booking holds a table for a time (online, by phone, or a walk-in waiting for a table).
-- status: HELD (online, waiting for the M-Pesa deposit; the table is held until hold_until) | BOOKED | WAITING (walk-in
--         waiting list) | READY (walk-in told their table is ready) | SEATED | NO_SHOW | CANCELLED
CREATE TABLE reservations (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token            varchar(40) NOT NULL UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
    source           varchar(10) NOT NULL,          -- ONLINE | STAFF | WALK_IN
    status           varchar(10) NOT NULL,
    name             varchar(80) NOT NULL,
    phone            varchar(20),
    email            varchar(160),
    party            integer NOT NULL CHECK (party BETWEEN 1 AND 200),
    starts_at        timestamptz NOT NULL,
    duration_min     integer NOT NULL DEFAULT 90,
    table_id         uuid REFERENCES dining_tables(id) ON DELETE SET NULL,
    note             varchar(300),
    deposit_amount   numeric(14,2) NOT NULL DEFAULT 0,
    deposit_status   varchar(10) NOT NULL DEFAULT 'NONE',  -- NONE | WAITING | PAID | APPLIED
    deposit_receipt  varchar(40),
    hold_until       timestamptz,
    order_id         uuid REFERENCES orders(id) ON DELETE SET NULL,
    acknowledged_at  timestamptz,                          -- a staff member stopped the ring for it
    acknowledged_by  uuid,
    reminded_at      timestamptz,
    ready_at         timestamptz,
    seated_at        timestamptz,
    cancel_reason    varchar(200),
    created_by       uuid,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_reservations_day ON reservations (tenant_id, starts_at);
CREATE INDEX ix_reservations_table ON reservations (table_id, starts_at) WHERE status IN ('HELD', 'BOOKED', 'SEATED');
CREATE INDEX ix_reservations_ring ON reservations (tenant_id, created_at) WHERE acknowledged_at IS NULL AND source = 'ONLINE';

ALTER TABLE reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE reservations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON reservations
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON reservations TO suite_app;

ALTER TABLE mpesa_requests ADD COLUMN reservation_id uuid;
