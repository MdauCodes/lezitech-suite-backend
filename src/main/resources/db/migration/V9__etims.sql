-- KRA eTIMS: every completed sale is sent to KRA for a signed invoice number and QR code;
-- refunds get a signed credit note. Results live on the order.
ALTER TABLE orders ADD COLUMN etims_status     varchar(12);   -- null = not needed | PENDING | SIGNED
ALTER TABLE orders ADD COLUMN etims_invoice_no varchar(60);
ALTER TABLE orders ADD COLUMN etims_signature  varchar(120);
ALTER TABLE orders ADD COLUMN etims_sdc_id     varchar(40);
ALTER TABLE orders ADD COLUMN etims_qr         text;
ALTER TABLE orders ADD COLUMN etims_signed_at  timestamptz;
ALTER TABLE orders ADD COLUMN etims_test       boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN etims_credit_no  varchar(60);

-- What still has to reach KRA (no RLS: the retry job works across workspaces, then acts as each one).
CREATE TABLE etims_queue (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id    uuid NOT NULL,
    kind        varchar(12) NOT NULL,          -- SALE | CREDIT_NOTE
    attempts    integer NOT NULL DEFAULT 0,
    last_error  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    next_try_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, kind)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON etims_queue TO suite_app;

-- Business customers' KRA PIN goes on their tax invoice.
ALTER TABLE customers ADD COLUMN kra_pin varchar(20);
