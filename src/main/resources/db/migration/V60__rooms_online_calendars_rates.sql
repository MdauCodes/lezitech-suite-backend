-- V60: hotels, lodges, guest houses and Airbnb hosts (docs/plan-gaps.md, phase 3):
-- calendars shared with Airbnb and Booking.com (iCal both ways), online room booking with a deposit,
-- the guest register, seasonal and weekend prices, and halls booked for events.

-- 1. Each room's own calendar link for Airbnb/Booking.com to read (a secret in the address).
ALTER TABLE spaces ADD COLUMN ical_token varchar(40) DEFAULT replace(gen_random_uuid()::text, '-', '');
UPDATE spaces SET ical_token = replace(gen_random_uuid()::text, '-', '') WHERE ical_token IS NULL;

-- 2. Calendars from other sites, read every 15 minutes; their bookings block the room here.
CREATE TABLE room_calendars (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    space_id       uuid NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    source         varchar(40) NOT NULL,          -- Airbnb | Booking.com | Expedia | Other
    url            varchar(1000) NOT NULL,
    last_synced_at timestamptz,
    last_error     varchar(300),
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE room_blocks (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    space_id    uuid NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    calendar_id uuid REFERENCES room_calendars(id) ON DELETE CASCADE,
    starts_on   date NOT NULL,
    ends_on     date NOT NULL,                    -- the morning they leave, like a stay's check-out
    uid         varchar(300),
    summary     varchar(200),
    CHECK (ends_on > starts_on)
);
CREATE INDEX ix_room_blocks_space ON room_blocks (tenant_id, space_id, starts_on);

-- 3. Prices that change by season, by weekday, or for long stays. Most specific wins: a room, then a room type, then all.
CREATE TABLE room_rates (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name       varchar(80) NOT NULL,              -- "High season", "Weekends", "Christmas"
    space_id   uuid REFERENCES spaces(id) ON DELETE CASCADE,
    type_name  varchar(60),
    starts_on  date,                              -- empty: any date
    ends_on    date,
    weekdays   varchar(30),                       -- "fri,sat"; empty: every day
    rate       numeric(14,2) NOT NULL CHECK (rate >= 0),
    min_nights integer NOT NULL DEFAULT 1 CHECK (min_nights BETWEEN 1 AND 60),
    created_at timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['room_calendars', 'room_blocks', 'room_rates']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;

-- 4. Stays: online bookings (held until the deposit is paid), the guest register, events in halls, fixed or nightly prices.
ALTER TABLE stays ADD COLUMN token           varchar(40) UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', '');
UPDATE stays SET token = replace(gen_random_uuid()::text, '-', '') WHERE token IS NULL;
ALTER TABLE stays ADD COLUMN email           varchar(160);
ALTER TABLE stays ADD COLUMN hold_until      timestamptz;          -- status HELD until the deposit is paid
ALTER TABLE stays ADD COLUMN deposit_amount  numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE stays ADD COLUMN acknowledged_at timestamptz;          -- online bookings ring at the front desk until seen
ALTER TABLE stays ADD COLUMN room_total      numeric(14,2);        -- the stay's price from the nightly prices, when booked
ALTER TABLE stays ADD COLUMN rate_fixed      boolean NOT NULL DEFAULT false;  -- staff typed one price per night
ALTER TABLE stays ADD COLUMN id_type         varchar(20);          -- NATIONAL_ID | PASSPORT | ALIEN_ID | OTHER
ALTER TABLE stays ADD COLUMN id_number       varchar(40);
ALTER TABLE stays ADD COLUMN nationality     varchar(60);
ALTER TABLE stays ADD COLUMN coming_from     varchar(80);
ALTER TABLE stays ADD COLUMN vehicle_reg     varchar(20);
ALTER TABLE stays ADD COLUMN event_name      varchar(120);         -- halls: the event booked
ALTER TABLE stays ADD COLUMN organiser       varchar(120);
ALTER TABLE stays ADD COLUMN people          integer;

ALTER TABLE mpesa_requests ADD COLUMN stay_id uuid;
