-- V18: restaurant ordering from the table (QR), files on R2, tips, loyalty, board screens, support grants,
-- uploaded ETRs and till reconciliation. See docs/plan-restaurant-and-tills.md (package A).

-- ---------- Orders placed or paid from the diner's phone ----------
ALTER TABLE orders ADD COLUMN self_order          boolean NOT NULL DEFAULT false;  -- placed from the table QR
ALTER TABLE orders ADD COLUMN held_for_payment    boolean NOT NULL DEFAULT false;  -- pay-first restaurants: kitchen waits
ALTER TABLE orders ADD COLUMN customer_phone      varchar(20);                     -- M-Pesa number given when ordering
ALTER TABLE orders ADD COLUMN assigned_to         uuid;                            -- waiter (users.id) serving it
ALTER TABLE orders ADD COLUMN pickup_code         varchar(4);                      -- takeaway self-orders: random 4 digits
ALTER TABLE orders ADD COLUMN estimated_ready_at  timestamptz;
ALTER TABLE orders ADD COLUMN batch_id            uuid;                            -- group order across tables
ALTER TABLE orders ADD COLUMN tip                 numeric(14,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN rating              smallint CHECK (rating IS NULL OR rating BETWEEN 1 AND 5);
ALTER TABLE orders ADD COLUMN rating_comment      varchar(500);
ALTER TABLE orders ADD COLUMN rated_at            timestamptz;
-- The business's own KRA receipt (ETR), uploaded as a photo or PDF and emailed to the customer.
ALTER TABLE orders ADD COLUMN etr_file_id         uuid;
ALTER TABLE orders ADD COLUMN etr_uploaded_at     timestamptz;
ALTER TABLE orders ADD COLUMN etr_emailed_to      varchar(200);
ALTER TABLE orders ADD COLUMN etr_emailed_at      timestamptz;
CREATE INDEX ix_orders_assigned ON orders (tenant_id, assigned_to) WHERE status = 'OPEN';
CREATE INDEX ix_orders_batch ON orders (tenant_id, batch_id) WHERE batch_id IS NOT NULL;

-- ---------- Kitchen ----------
ALTER TABLE order_lines ADD COLUMN ready_at           timestamptz;   -- for cooking-time averages
ALTER TABLE order_lines ADD COLUMN substitute_item_id uuid REFERENCES items(id) ON DELETE SET NULL;
ALTER TABLE order_lines ADD COLUMN substitute_status  varchar(10);   -- OFFERED | ACCEPTED | DECLINED
ALTER TABLE order_lines ADD COLUMN rejected_reason    varchar(200);

-- ---------- Menu ----------
ALTER TABLE items ADD COLUMN photo_file_id     uuid;
ALTER TABLE items ADD COLUMN allergens         text[] NOT NULL DEFAULT '{}';
ALTER TABLE items ADD COLUMN allergen_note     varchar(200);
ALTER TABLE items ADD COLUMN unavailable_until timestamptz;           -- shown as sold out until then; 'infinity' = until restocked
ALTER TABLE items ADD COLUMN prep_minutes      integer CHECK (prep_minutes IS NULL OR prep_minutes BETWEEN 1 AND 600);

-- ---------- Tills: owner reconciliation and lock ----------
ALTER TABLE shifts ADD COLUMN reconcile_status varchar(12);           -- MATCHED | DIFFERENCE | INVESTIGATED
ALTER TABLE shifts ADD COLUMN reconcile_note   text;
ALTER TABLE shifts ADD COLUMN reconciled_at    timestamptz;
ALTER TABLE shifts ADD COLUMN reconciled_by    uuid;
ALTER TABLE shifts ADD COLUMN locked           boolean NOT NULL DEFAULT false;

-- ---------- New business tables (all fenced to the business) ----------

-- Uploaded files (menu photos, ETRs). The bytes live in R2 (or a local folder in development) under store_key.
CREATE TABLE files (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    purpose      varchar(20) NOT NULL,      -- ITEM_PHOTO | ETR
    store_key    varchar(300) NOT NULL UNIQUE,
    content_type varchar(60) NOT NULL,
    size_bytes   integer NOT NULL,
    created_by   uuid,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Tips, split between kitchen, business and the server by the restaurant's shares at the time.
CREATE TABLE tips (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id       uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    amount         numeric(14,2) NOT NULL CHECK (amount > 0),
    server_id      uuid,
    kitchen_share  numeric(14,2) NOT NULL,
    business_share numeric(14,2) NOT NULL,
    server_share   numeric(14,2) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_tips_tenant ON tips (tenant_id, created_at DESC);

-- Split payment from several phones: prompts go out one at a time, in position order.
CREATE TABLE pay_splits (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id         uuid NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    phone            varchar(20) NOT NULL,
    amount           numeric(14,2) NOT NULL CHECK (amount > 0),
    position         integer NOT NULL,
    status           varchar(10) NOT NULL DEFAULT 'WAITING',   -- WAITING | PROMPTED | PAID | FAILED
    attempts         integer NOT NULL DEFAULT 0,
    mpesa_request_id uuid,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_pay_splits_order ON pay_splits (order_id, position);

-- Loyalty points per customer, and every move (earned, redeemed, expired, adjusted).
CREATE TABLE loyalty_moves (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    order_id    uuid REFERENCES orders(id) ON DELETE SET NULL,
    kind        varchar(10) NOT NULL,      -- EARN | REDEEM | EXPIRE | ADJUST
    points      numeric(14,2) NOT NULL,    -- positive earned, negative spent
    note        varchar(200),
    created_by  uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_loyalty_customer ON loyalty_moves (tenant_id, customer_id, created_at DESC);
ALTER TABLE customers ADD COLUMN loyalty_points numeric(14,2) NOT NULL DEFAULT 0;

-- Order board screens per area, each with its own link and key.
CREATE TABLE board_screens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        varchar(60) NOT NULL,
    description varchar(200),
    screen_key  varchar(40) NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE board_screen_tables (
    tenant_id uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    screen_id uuid NOT NULL REFERENCES board_screens(id) ON DELETE CASCADE,
    table_id  uuid NOT NULL REFERENCES dining_tables(id) ON DELETE CASCADE,
    PRIMARY KEY (screen_id, table_id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['files','tips','pay_splits','loyalty_moves','board_screens','board_screen_tables']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
-- Loyalty history is append-only from the app.
REVOKE UPDATE, DELETE ON loyalty_moves FROM suite_app;

-- ---------- Support access: only while the owner has granted it ----------
-- Not fenced: the LeziTech control panel checks it before signing in as the business. Code always filters by business.
CREATE TABLE support_grants (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    granted_by  uuid NOT NULL,
    granted_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    stopped_at  timestamptz,
    stopped_by  uuid,
    note        varchar(200)
);
CREATE INDEX ix_support_grants_active ON support_grants (tenant_id, expires_at DESC);
GRANT SELECT, INSERT, UPDATE ON support_grants TO suite_app;
