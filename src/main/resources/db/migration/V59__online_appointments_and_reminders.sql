-- V59: salons take bookings online, with an optional M-Pesa deposit, and clients get reminders (docs/plan-gaps.md, phase 2).

-- 1. Appointments booked online: the client's own link (token), where it came from, deposit, and staff acknowledgement.
ALTER TABLE appointments ADD COLUMN token           varchar(40) UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', '');
ALTER TABLE appointments ADD COLUMN source          varchar(10) NOT NULL DEFAULT 'STAFF';   -- STAFF | ONLINE
ALTER TABLE appointments ADD COLUMN email           varchar(160);
ALTER TABLE appointments ADD COLUMN deposit_amount  numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE appointments ADD COLUMN deposit_status  varchar(10) NOT NULL DEFAULT 'NONE';    -- NONE | WAITING | PAID | APPLIED
ALTER TABLE appointments ADD COLUMN deposit_receipt varchar(40);
ALTER TABLE appointments ADD COLUMN hold_until      timestamptz;                            -- HELD until the deposit is paid
ALTER TABLE appointments ADD COLUMN acknowledged_at timestamptz;                            -- online bookings ring until seen
ALTER TABLE appointments ADD COLUMN cancel_reason   varchar(200);
UPDATE appointments SET token = replace(gen_random_uuid()::text, '-', '') WHERE token IS NULL;
CREATE INDEX ix_appointments_staff_time ON appointments (tenant_id, staff_id, starts_at);

-- 2. When each staff member works: {"mon": ["09:00", "18:00"], "sun": null, ...}. Empty means the business's hours.
ALTER TABLE users ADD COLUMN work_hours jsonb;

-- 3. Deposit prompts for an appointment.
ALTER TABLE mpesa_requests ADD COLUMN appointment_id uuid;

-- 4. Reminders sent to customers, once per thing, kind and channel (email now; SMS and WhatsApp later).
CREATE TABLE reminders (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ref_kind   varchar(20) NOT NULL,     -- APPOINTMENT | ...
    ref_id     uuid NOT NULL,
    kind       varchar(30) NOT NULL,     -- BEFORE_24H | BEFORE_2H | MANUAL-<time> | CONFIRM ...
    channel    varchar(10) NOT NULL,     -- EMAIL | SMS | WHATSAPP
    sent_to    varchar(160) NOT NULL,
    status     varchar(10) NOT NULL DEFAULT 'SENDING',   -- SENDING | SENT | FAILED
    error      text,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at    timestamptz,
    UNIQUE (ref_kind, ref_id, kind, channel)
);
CREATE INDEX ix_reminders_ref ON reminders (tenant_id, ref_kind, ref_id);
ALTER TABLE reminders ENABLE ROW LEVEL SECURITY;
ALTER TABLE reminders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON reminders
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON reminders TO suite_app;
