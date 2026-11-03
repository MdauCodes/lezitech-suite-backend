-- Public screens (order board, tracking, menu), rooms and stays, tours, and property leases.

-- Key for the public order-status board URL, so it can't be guessed from the slug alone.
ALTER TABLE tenants ADD COLUMN display_key varchar(32) NOT NULL DEFAULT substr(md5(random()::text), 1, 16);

-- Customer-facing tracking link for each order.
ALTER TABLE orders ADD COLUMN public_token uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX ux_orders_token ON orders (public_token);
ALTER TABLE orders ADD COLUMN customer_label varchar(60);        -- name called out on the board ("Brian")
ALTER TABLE orders ADD COLUMN ready_at timestamptz;

-- ---------- Rentable spaces: hotel rooms, cottages, halls, mall stalls, apartments ----------
CREATE TABLE spaces (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(60) NOT NULL,                       -- "101", "Hall A", "Stall G12"
    kind        varchar(20) NOT NULL DEFAULT 'ROOM',        -- ROOM | COTTAGE | TENT | HALL | APARTMENT | STALL | SHOP | OFFICE
    type_name   varchar(60),                                -- Standard, Deluxe, Ground floor…
    capacity    integer NOT NULL DEFAULT 2,
    rate        numeric(14,2) NOT NULL DEFAULT 0,           -- per night (rooms/halls) or per month (property)
    service_charge numeric(14,2) NOT NULL DEFAULT 0,        -- property: monthly service charge
    area_sqft   integer,
    floor       varchar(20),
    housekeeping varchar(16) NOT NULL DEFAULT 'CLEAN',      -- CLEAN | DIRTY | INSPECT | OUT_OF_ORDER
    active      boolean NOT NULL DEFAULT true,
    sort        integer NOT NULL DEFAULT 0
);

-- Hotel/venue reservations. The folio is an ordinary open order that collects room nights,
-- restaurant bills charged to the room, and payments.
CREATE TABLE stays (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    space_id    uuid NOT NULL REFERENCES spaces(id),
    customer_id uuid REFERENCES customers(id) ON DELETE SET NULL,
    guest_name  varchar(200) NOT NULL,
    phone       varchar(30),
    adults      integer NOT NULL DEFAULT 1,
    children    integer NOT NULL DEFAULT 0,
    check_in    date NOT NULL,
    check_out   date NOT NULL,
    rate        numeric(14,2) NOT NULL,
    board       varchar(10) NOT NULL DEFAULT 'BB',          -- RO | BB | HB | FB
    source      varchar(40),
    status      varchar(12) NOT NULL DEFAULT 'RESERVED',    -- RESERVED | IN_HOUSE | CHECKED_OUT | CANCELLED | NO_SHOW
    notes       text,
    folio_id    uuid REFERENCES orders(id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (check_out > check_in)
);
CREATE INDEX ix_stays_dates ON stays (tenant_id, check_in, check_out);

-- ---------- Tours and safaris ----------
CREATE TABLE trips (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    item_id     uuid NOT NULL REFERENCES items(id),        -- the package
    starts_on   date NOT NULL,
    ends_on     date NOT NULL,
    seats       integer NOT NULL DEFAULT 7,
    vehicle     varchar(80),
    guide       varchar(80),
    status      varchar(12) NOT NULL DEFAULT 'OPEN',        -- OPEN | FULL | DEPARTED | DONE | CANCELLED
    notes       text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trip_bookings (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    trip_id     uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    customer_id uuid REFERENCES customers(id) ON DELETE SET NULL,
    lead_name   varchar(200) NOT NULL,
    phone       varchar(30),
    email       varchar(200),
    pax         integer NOT NULL DEFAULT 1,
    pickup      varchar(120),
    nationality varchar(60),
    status      varchar(12) NOT NULL DEFAULT 'CONFIRMED',   -- PROVISIONAL | CONFIRMED | CANCELLED
    order_id    uuid REFERENCES orders(id) ON DELETE SET NULL,
    notes       text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------- Property: leases on stalls, shops, offices, apartments ----------
CREATE TABLE leases (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    space_id       uuid NOT NULL REFERENCES spaces(id),
    customer_id    uuid NOT NULL REFERENCES customers(id),  -- the occupier
    starts_on      date NOT NULL,
    ends_on        date,
    rent           numeric(14,2) NOT NULL,
    service_charge numeric(14,2) NOT NULL DEFAULT 0,
    deposit        numeric(14,2) NOT NULL DEFAULT 0,
    due_day        integer NOT NULL DEFAULT 5,
    status         varchar(12) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | ENDED
    created_at     timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE orders ADD COLUMN stay_id uuid REFERENCES stays(id) ON DELETE SET NULL;
ALTER TABLE orders ADD COLUMN lease_id uuid REFERENCES leases(id) ON DELETE SET NULL;
ALTER TABLE orders ADD COLUMN period varchar(7);                 -- rent invoices: '2026-11'
ALTER TABLE orders ADD COLUMN due_on date;
CREATE UNIQUE INDEX ux_rent_period ON orders (lease_id, period) WHERE lease_id IS NOT NULL AND status <> 'VOID';

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['spaces','stays','trips','trip_bookings','leases']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
