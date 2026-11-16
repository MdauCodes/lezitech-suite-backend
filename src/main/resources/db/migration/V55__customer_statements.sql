-- What a customer owes, line by line: a sale put on their account, a payment towards it, a refund off it. Each line
-- keeps the balance after it, so a statement for any dates can be printed, shared by link or emailed.
CREATE TABLE customer_ledger (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    customer_id   uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    kind          varchar(16) NOT NULL,   -- SALE | PAYMENT | REFUND
    amount        numeric(14,2) NOT NULL, -- signed: + they owe more, - they owe less
    balance_after numeric(14,2) NOT NULL,
    order_id      uuid REFERENCES orders(id) ON DELETE SET NULL,
    method        varchar(20),
    reference     varchar(80),
    note          varchar(200),
    user_id       uuid,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_customer_ledger ON customer_ledger (customer_id, created_at);

-- The long random part of a customer's statement link (made the first time a statement is shared).
ALTER TABLE customers ADD COLUMN statement_token varchar(64);
CREATE UNIQUE INDEX ux_customers_statement_token ON customers (tenant_id, statement_token) WHERE statement_token IS NOT NULL;

ALTER TABLE customer_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON customer_ledger
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON customer_ledger TO suite_app;
