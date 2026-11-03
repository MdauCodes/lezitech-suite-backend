-- Everything a tenant owns. Every table carries tenant_id and is fenced by RLS
-- on app.tenant, which the transaction manager sets at the start of each transaction.

-- ---------- Places: store/stockroom, shops, kitchen ----------
CREATE TABLE locations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(120) NOT NULL,
    kind        varchar(20)  NOT NULL DEFAULT 'SHOP',      -- STORE | SHOP | KITCHEN
    parent_id   uuid REFERENCES locations(id),             -- the store that feeds this shop
    is_default  boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------- Catalogue ----------
CREATE TABLE categories (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(120) NOT NULL,
    color       varchar(9),
    sort        integer NOT NULL DEFAULT 0
);

CREATE TABLE items (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    category_id      uuid REFERENCES categories(id) ON DELETE SET NULL,
    name             varchar(200) NOT NULL,
    kind             varchar(20)  NOT NULL DEFAULT 'PRODUCT',  -- PRODUCT | SERVICE | MENU
    sku              varchar(60),
    barcode          varchar(60),
    unit             varchar(20)  NOT NULL DEFAULT 'pc',
    price            numeric(14,2) NOT NULL DEFAULT 0,
    cost             numeric(14,2) NOT NULL DEFAULT 0,
    wholesale_price  numeric(14,2),
    wholesale_min_qty numeric(14,3),
    tax_rate         numeric(5,2) NOT NULL DEFAULT 16,
    track_stock      boolean NOT NULL DEFAULT true,
    reorder_level    numeric(14,3) NOT NULL DEFAULT 0,
    duration_min     integer,                                  -- services
    station          varchar(30),                              -- kitchen / bar
    needs_prescription boolean NOT NULL DEFAULT false,         -- pharmacy
    -- Add-on groups: [{"name":"Size","required":true,"multi":false,"choices":[{"name":"Large","price":50}]}]
    options          jsonb NOT NULL DEFAULT '[]'::jsonb,
    color            varchar(9),
    active           boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_items_tenant ON items (tenant_id, active);
CREATE UNIQUE INDEX ux_items_barcode ON items (tenant_id, barcode) WHERE barcode IS NOT NULL;

-- ---------- Stock ----------
CREATE TABLE stock_levels (
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    item_id     uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    location_id uuid NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    qty         numeric(14,3) NOT NULL DEFAULT 0,
    PRIMARY KEY (item_id, location_id)
);

CREATE TABLE stock_batches (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    item_id     uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    location_id uuid NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    batch_no    varchar(60) NOT NULL,
    expiry_date date,
    qty         numeric(14,3) NOT NULL DEFAULT 0
);

CREATE TABLE stock_moves (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    item_id     uuid NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    location_id uuid NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    qty         numeric(14,3) NOT NULL,                     -- signed
    reason      varchar(20) NOT NULL,                       -- SALE | RECEIVE | ADJUST | TRANSFER | OPENING | VOID
    ref         varchar(120),
    note        text,
    user_id     uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_moves_tenant ON stock_moves (tenant_id, created_at DESC);

-- ---------- People ----------
CREATE TABLE customers (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name         varchar(200) NOT NULL,
    phone        varchar(30),
    email        varchar(200),
    company      varchar(200),
    kind         varchar(20) NOT NULL DEFAULT 'RETAIL',     -- RETAIL | WHOLESALE | CORPORATE
    credit_limit numeric(14,2) NOT NULL DEFAULT 0,
    balance      numeric(14,2) NOT NULL DEFAULT 0,          -- owed to us
    points       integer NOT NULL DEFAULT 0,
    notes        text,
    tags         text[] NOT NULL DEFAULT '{}',
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_customers_tenant ON customers (tenant_id, name);

CREATE TABLE suppliers (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(200) NOT NULL,
    phone       varchar(30),
    email       varchar(200),
    balance     numeric(14,2) NOT NULL DEFAULT 0,           -- we owe them
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------- Restaurant floor ----------
CREATE TABLE floors (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(80) NOT NULL,
    sort        integer NOT NULL DEFAULT 0
);

CREATE TABLE dining_tables (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    floor_id    uuid NOT NULL REFERENCES floors(id) ON DELETE CASCADE,
    label       varchar(20) NOT NULL,
    seats       integer NOT NULL DEFAULT 4,
    shape       varchar(10) NOT NULL DEFAULT 'square',
    x           integer NOT NULL DEFAULT 0,
    y           integer NOT NULL DEFAULT 0
);

-- ---------- Tills and sales ----------
CREATE TABLE shifts (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id        uuid NOT NULL,
    location_id    uuid REFERENCES locations(id),
    opened_at      timestamptz NOT NULL DEFAULT now(),
    closed_at      timestamptz,
    opening_cash   numeric(14,2) NOT NULL DEFAULT 0,
    counted_cash   numeric(14,2),
    counted_mpesa  numeric(14,2),
    counted_card   numeric(14,2),
    note           text,
    status         varchar(10) NOT NULL DEFAULT 'OPEN'
);

CREATE TABLE orders (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number       integer NOT NULL,
    kind         varchar(12) NOT NULL DEFAULT 'SALE',        -- SALE | QUOTE | INVOICE
    channel      varchar(12) NOT NULL DEFAULT 'COUNTER',     -- COUNTER | DINE_IN | TAKEAWAY | DELIVERY | WHOLESALE | BOOKING
    status       varchar(12) NOT NULL DEFAULT 'OPEN',        -- OPEN | PAID | CREDIT | VOID
    table_id     uuid REFERENCES dining_tables(id) ON DELETE SET NULL,
    guests       integer,
    customer_id  uuid REFERENCES customers(id) ON DELETE SET NULL,
    location_id  uuid REFERENCES locations(id),
    shift_id     uuid REFERENCES shifts(id),
    user_id      uuid,
    subtotal     numeric(14,2) NOT NULL DEFAULT 0,
    discount     numeric(14,2) NOT NULL DEFAULT 0,
    tax          numeric(14,2) NOT NULL DEFAULT 0,
    total        numeric(14,2) NOT NULL DEFAULT 0,
    paid         numeric(14,2) NOT NULL DEFAULT 0,
    note         text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    closed_at    timestamptz
);
CREATE INDEX ix_orders_tenant ON orders (tenant_id, created_at DESC);
CREATE INDEX ix_orders_open ON orders (tenant_id, status) WHERE status = 'OPEN';

CREATE TABLE order_lines (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id      uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    item_id       uuid REFERENCES items(id) ON DELETE SET NULL,
    name          varchar(200) NOT NULL,
    qty           numeric(14,3) NOT NULL,
    unit_price    numeric(14,2) NOT NULL,
    tax_rate      numeric(5,2) NOT NULL DEFAULT 0,
    discount      numeric(14,2) NOT NULL DEFAULT 0,
    line_total    numeric(14,2) NOT NULL,
    options       jsonb NOT NULL DEFAULT '[]'::jsonb,
    note          varchar(300),
    course        integer NOT NULL DEFAULT 1,
    station       varchar(30),
    kitchen_status varchar(12) NOT NULL DEFAULT 'PENDING',  -- PENDING | NEW | PREPARING | READY | SERVED
    sent_at       timestamptz,
    staff_id      uuid,                                     -- stylist / waiter credited with the line
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_lines_order ON order_lines (order_id);
CREATE INDEX ix_lines_kitchen ON order_lines (tenant_id, kitchen_status) WHERE kitchen_status IN ('NEW','PREPARING','READY');

CREATE TABLE payments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id    uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    shift_id    uuid REFERENCES shifts(id),
    method      varchar(12) NOT NULL,                       -- CASH | MPESA | CARD | CREDIT
    amount      numeric(14,2) NOT NULL,
    reference   varchar(60),
    user_id     uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------- Purchasing ----------
CREATE TABLE purchase_orders (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    supplier_id  uuid REFERENCES suppliers(id) ON DELETE SET NULL,
    location_id  uuid REFERENCES locations(id),
    status       varchar(12) NOT NULL DEFAULT 'DRAFT',      -- DRAFT | ORDERED | RECEIVED
    total        numeric(14,2) NOT NULL DEFAULT 0,
    note         text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    received_at  timestamptz
);

CREATE TABLE purchase_lines (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    po_id       uuid NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    item_id     uuid NOT NULL REFERENCES items(id),
    qty         numeric(14,3) NOT NULL,
    unit_cost   numeric(14,2) NOT NULL,
    batch_no    varchar(60),
    expiry_date date
);

-- ---------- CRM ----------
CREATE TABLE pipeline_stages (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(80) NOT NULL,
    sort        integer NOT NULL DEFAULT 0,
    probability integer NOT NULL DEFAULT 10,
    is_won      boolean NOT NULL DEFAULT false,
    is_lost     boolean NOT NULL DEFAULT false,
    rot_days    integer NOT NULL DEFAULT 7                  -- highlight deals untouched this long
);

CREATE TABLE deals (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title            varchar(200) NOT NULL,
    customer_id      uuid REFERENCES customers(id) ON DELETE SET NULL,
    contact_name     varchar(200),
    phone            varchar(30),
    email            varchar(200),
    value            numeric(14,2) NOT NULL DEFAULT 0,
    stage_id         uuid NOT NULL REFERENCES pipeline_stages(id),
    owner_id         uuid,
    source           varchar(40),
    expected_close   date,
    lost_reason      varchar(200),
    last_activity_at timestamptz NOT NULL DEFAULT now(),
    created_at       timestamptz NOT NULL DEFAULT now(),
    closed_at        timestamptz
);

CREATE TABLE activities (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    deal_id     uuid REFERENCES deals(id) ON DELETE CASCADE,
    customer_id uuid REFERENCES customers(id) ON DELETE CASCADE,
    kind        varchar(12) NOT NULL DEFAULT 'NOTE',        -- NOTE | CALL | MEETING | TASK | WHATSAPP | EMAIL
    body        text NOT NULL,
    due_at      timestamptz,
    done        boolean NOT NULL DEFAULT false,
    user_id     uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------- Bookings (salons, clinics, services) ----------
CREATE TABLE appointments (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    customer_id   uuid REFERENCES customers(id) ON DELETE SET NULL,
    customer_name varchar(200) NOT NULL,
    phone         varchar(30),
    item_id       uuid REFERENCES items(id) ON DELETE SET NULL,
    staff_id      uuid,
    starts_at     timestamptz NOT NULL,
    ends_at       timestamptz NOT NULL,
    status        varchar(12) NOT NULL DEFAULT 'BOOKED',    -- BOOKED | ARRIVED | DONE | NO_SHOW | CANCELLED
    notes         text,
    order_id      uuid REFERENCES orders(id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_appts_day ON appointments (tenant_id, starts_at);

-- ---------- Tenant fence ----------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['locations','categories','items','stock_levels','stock_batches','stock_moves',
        'customers','suppliers','floors','dining_tables','shifts','orders','order_lines','payments',
        'purchase_orders','purchase_lines','pipeline_stages','deals','activities','appointments']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
