-- R5: delivery. Areas with a fee and a rider's pay, riders (staff with the RIDER job, or a hired boda with just a name
-- and phone), one delivery per order, saved addresses by phone, and delivery-app orders (Glovo, Uber Eats, Bolt Food)
-- recorded with the app's cut so profit is true.

CREATE TABLE delivery_areas (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(80) NOT NULL,
    fee         numeric(14,2) NOT NULL DEFAULT 0,     -- what the customer pays
    rider_pay   numeric(14,2) NOT NULL DEFAULT 0,     -- what the rider gets for the trip
    minutes     integer NOT NULL DEFAULT 30,          -- usual time on the road
    active      boolean NOT NULL DEFAULT true,
    sort        integer NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_delivery_areas_name ON delivery_areas (tenant_id, lower(name));

CREATE TABLE riders (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id     uuid REFERENCES users(id) ON DELETE SET NULL,   -- a staff rider who signs in; null for a hired rider
    name        varchar(80) NOT NULL,
    phone       varchar(20),
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_riders_user ON riders (tenant_id, user_id) WHERE user_id IS NOT NULL;

CREATE TABLE deliveries (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id        uuid NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    area_id         uuid REFERENCES delivery_areas(id) ON DELETE SET NULL,
    area_name       varchar(80),
    name            varchar(80),
    phone           varchar(20) NOT NULL,
    address         varchar(300) NOT NULL,
    landmark        varchar(200),                     -- "blue gate opposite Naivas", "3rd floor, door 12"
    fee             numeric(14,2) NOT NULL DEFAULT 0,
    pay_on_delivery boolean NOT NULL DEFAULT false,   -- the customer pays the rider at the door
    status          varchar(12) NOT NULL DEFAULT 'WAITING', -- WAITING | ON_THE_WAY | DELIVERED | FAILED
    rider_id        uuid REFERENCES riders(id) ON DELETE SET NULL,
    rider_pay       numeric(14,2) NOT NULL DEFAULT 0,
    picked_at       timestamptz,
    delivered_at    timestamptz,
    failed_reason   varchar(200),
    collected       numeric(14,2) NOT NULL DEFAULT 0, -- cash the rider took at the door, still with them until handed in
    handed_in_at    timestamptz,
    handed_in_to    uuid,
    rider_paid_at   timestamptz,
    rider_paid_by   uuid,
    rider_paid_how  varchar(16),                      -- TILL_CASH | FROM_COLLECTED | MPESA
    rider_paid_shift uuid REFERENCES shifts(id),
    created_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_deliveries_open ON deliveries (tenant_id, status, created_at DESC);
CREATE INDEX ix_deliveries_rider ON deliveries (tenant_id, rider_id, created_at DESC);

CREATE TABLE delivery_addresses (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone       varchar(20) NOT NULL,
    name        varchar(80),
    area_id     uuid REFERENCES delivery_areas(id) ON DELETE SET NULL,
    address     varchar(300) NOT NULL,
    landmark    varchar(200),
    last_used_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_delivery_addresses ON delivery_addresses (tenant_id, phone, lower(address));

-- Delivery-app orders: which app, its order number, and the app's cut taken when the sale closes.
ALTER TABLE orders ADD COLUMN delivery_app varchar(20);
ALTER TABLE orders ADD COLUMN app_order_ref varchar(40);
ALTER TABLE orders ADD COLUMN app_commission numeric(14,2) NOT NULL DEFAULT 0;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['delivery_areas','riders','deliveries','delivery_addresses']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
