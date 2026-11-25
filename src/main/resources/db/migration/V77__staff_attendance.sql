-- V77: staff attendance. Where each branch is, how staff prove they are there, work hours, and every clock event.

ALTER TABLE locations ADD COLUMN lat double precision;
ALTER TABLE locations ADD COLUMN lng double precision;
ALTER TABLE locations ADD COLUMN radius_m integer NOT NULL DEFAULT 100 CHECK (radius_m BETWEEN 30 AND 500);
ALTER TABLE locations ADD COLUMN att_method varchar(10) NOT NULL DEFAULT 'EITHER' CHECK (att_method IN ('SELFIE', 'CODE', 'EITHER'));
ALTER TABLE locations ADD COLUMN att_till_needs_clock boolean NOT NULL DEFAULT false;
ALTER TABLE locations ADD COLUMN att_allow_printed boolean NOT NULL DEFAULT false;
-- Secrets never leave the server: the key behind the rotating shop code, and the printed code (both rotatable).
ALTER TABLE locations ADD COLUMN att_secret varchar(64);
ALTER TABLE locations ADD COLUMN att_print_token varchar(64);

-- Work hours per weekday (1 = Monday ... 7 = Sunday). user_id null = the branch's hours; a row with a user replaces them.
CREATE TABLE attendance_hours (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    location_id uuid NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    user_id     uuid REFERENCES users(id) ON DELETE CASCADE,
    weekday     smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_time  time NOT NULL,
    end_time    time NOT NULL,
    grace_min   integer NOT NULL DEFAULT 10 CHECK (grace_min BETWEEN 0 AND 120),
    CHECK (end_time <> start_time)   -- an end earlier than the start means the shift ends the next day
);
CREATE UNIQUE INDEX ux_attendance_hours ON attendance_hours (location_id, coalesce(user_id, '00000000-0000-0000-0000-000000000000'::uuid), weekday);

-- Every clock in and out. Times are server times; day is the Nairobi date.
CREATE TABLE attendance_events (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_name    varchar(120) NOT NULL,
    location_id  uuid NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    kind         varchar(3) NOT NULL CHECK (kind IN ('IN', 'OUT')),
    at           timestamptz NOT NULL,
    day          date NOT NULL,
    lat          double precision,
    lng          double precision,
    accuracy_m   double precision,
    distance_m   double precision,
    method       varchar(10),                      -- SELFIE | CODE | PRINTED | AUTO
    selfie_file_id uuid REFERENCES files(id) ON DELETE SET NULL,
    flags        text[] NOT NULL DEFAULT '{}',     -- LATE, LEFT_EARLY, NO_CLOCK_OUT, ZERO_ACCURACY, LOCATION_JUMP
    late_min     integer,
    early_min    integer
);
CREATE INDEX ix_attendance_events_day ON attendance_events (tenant_id, day DESC);
CREATE INDEX ix_attendance_events_user ON attendance_events (user_id, at DESC);

-- Links that can only show the rotating code on a shop screen. Only a hash of the link is kept.
CREATE TABLE attendance_kiosks (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    location_id uuid NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    label       varchar(80) NOT NULL,
    token_hash  varchar(64) NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['attendance_hours', 'attendance_events', 'attendance_kiosks'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
