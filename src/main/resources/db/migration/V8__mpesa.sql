-- M-Pesa (Daraja). Safaricom calls back without a login, so these tables are found by request id
-- or shortcode first and then checked against the workspace in code; no RLS (like tenants/users).

-- One "please pay" prompt sent to a phone (STK push), for a till order or a Lezitech subscription.
CREATE TABLE mpesa_requests (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    purpose             varchar(15) NOT NULL,                 -- ORDER | SUBSCRIPTION
    order_id            uuid,
    subscription_request_id uuid REFERENCES subscription_requests(id) ON DELETE SET NULL,
    user_id             uuid,                                 -- who pressed the button
    phone               varchar(15) NOT NULL,
    amount              numeric(14,2) NOT NULL,
    callback_token      varchar(64) NOT NULL UNIQUE,          -- secret part of the callback URL
    checkout_request_id varchar(80) UNIQUE,
    merchant_request_id varchar(80),
    status              varchar(12) NOT NULL DEFAULT 'PENDING', -- PENDING | PAID | FAILED | CANCELLED
    receipt             varchar(30),
    result_code         integer,
    result_desc         text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_mpesa_requests_tenant ON mpesa_requests (tenant_id, created_at DESC);

-- Money paid straight to a till/paybill (C2B), matched to an order when the account number fits.
CREATE TABLE mpesa_c2b (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid REFERENCES tenants(id) ON DELETE CASCADE,
    trans_id         varchar(30) NOT NULL UNIQUE,
    amount           numeric(14,2) NOT NULL,
    shortcode        varchar(20) NOT NULL,
    bill_ref         varchar(60),
    msisdn           varchar(20),
    payer_name       varchar(120),
    trans_time       varchar(20),
    order_id         uuid,
    status           varchar(12) NOT NULL DEFAULT 'UNMATCHED',   -- UNMATCHED | APPLIED | IGNORED
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_mpesa_c2b_tenant ON mpesa_c2b (tenant_id, created_at DESC);

ALTER TABLE subscription_requests ADD COLUMN paid_via varchar(20);
ALTER TABLE subscription_requests ADD COLUMN mpesa_receipt varchar(30);

GRANT SELECT, INSERT, UPDATE ON mpesa_requests, mpesa_c2b TO suite_app;
