-- Malls & property, package M9: a food court in one QR (owner's decisions, 2026-11-14).
-- The mall adds its food stalls, each with its own menu and a kitchen screen opened from a private link (no sign-up for
-- the stall). A diner scans one QR, orders from several stalls in one basket and pays once by M-Pesa to the mall's own
-- paybill. Each stall sees only its part; the diner gets an order number on their phone and on a TV screen, and collects
-- at each stall when it's ready. BizNez shows the mall how much to pay each stall (sales less the mall's optional fee);
-- the mall records each payment. Neither LeziTech nor BizNez holds the money.

CREATE TABLE fc_stalls (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name          varchar(80) NOT NULL,
    lease_id      uuid REFERENCES leases(id) ON DELETE SET NULL,     -- the stall's lease in the mall, if any
    fee_pct       numeric(5,2) NOT NULL DEFAULT 0,                    -- the mall's fee on this stall's food court sales
    payout_to     varchar(120),                                       -- e.g. "M-Pesa 0722 000 000"
    screen_key    varchar(64) NOT NULL DEFAULT replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''),
    open          boolean NOT NULL DEFAULT true,
    sort          integer NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE fc_items (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    stall_id    uuid NOT NULL REFERENCES fc_stalls(id) ON DELETE CASCADE,
    name        varchar(100) NOT NULL,
    description varchar(200),
    price       numeric(14,2) NOT NULL,
    available   boolean NOT NULL DEFAULT true,
    sort        integer NOT NULL DEFAULT 0
);

CREATE TABLE fc_orders (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    number        integer NOT NULL,                                   -- the day's number, called on the screen
    day           date NOT NULL,
    token         varchar(64) NOT NULL DEFAULT replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''),
    phone         varchar(20) NOT NULL,
    table_label   varchar(20),
    total         numeric(14,2) NOT NULL,
    status        varchar(16) NOT NULL DEFAULT 'WAITING_PAYMENT',     -- WAITING_PAYMENT | PAID | DONE | CANCELLED
    receipt       varchar(30),
    paid_at       timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, day, number)
);
CREATE UNIQUE INDEX ux_fc_orders_token ON fc_orders (token);

CREATE TABLE fc_order_lines (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id    uuid NOT NULL REFERENCES fc_orders(id) ON DELETE CASCADE,
    stall_id    uuid NOT NULL REFERENCES fc_stalls(id),
    item_id     uuid REFERENCES fc_items(id) ON DELETE SET NULL,
    name        varchar(100) NOT NULL,
    price       numeric(14,2) NOT NULL,
    qty         integer NOT NULL,
    line_total  numeric(14,2) NOT NULL,
    status      varchar(10) NOT NULL DEFAULT 'NEW',                  -- NEW | READY | COLLECTED
    ready_at    timestamptz,
    collected_at timestamptz
);
CREATE INDEX ix_fc_lines_stall ON fc_order_lines (stall_id, status);

-- What the mall paid each stall.
CREATE TABLE fc_payouts (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    stall_id    uuid NOT NULL REFERENCES fc_stalls(id) ON DELETE CASCADE,
    amount      numeric(14,2) NOT NULL,
    method      varchar(12) NOT NULL,
    reference   varchar(60),
    paid_on     date NOT NULL,
    user_id     uuid,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- The TV screen's private key, and prompts for a food court order.
ALTER TABLE tenants ADD COLUMN fc_board_key varchar(64) NOT NULL DEFAULT replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
ALTER TABLE mpesa_requests ADD COLUMN fc_order_id uuid;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['fc_stalls','fc_items','fc_orders','fc_order_lines','fc_payouts']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_fence ON %I
            USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)$p$, t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO suite_app', t);
    END LOOP;
END $$;
