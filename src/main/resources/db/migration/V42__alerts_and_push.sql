-- Staff never miss an order: phone and computer notifications (Web Push) for each staff member's devices, the ring for
-- new online deliveries (stopped by whoever sees it first), and a rider's "new trip for you".

CREATE TABLE push_subscriptions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint    text NOT NULL UNIQUE,
    p256dh      varchar(200) NOT NULL,
    auth        varchar(100) NOT NULL,
    device      varchar(120),
    created_at  timestamptz NOT NULL DEFAULT now(),
    last_ok_at  timestamptz
);
CREATE INDEX ix_push_user ON push_subscriptions (tenant_id, user_id);

ALTER TABLE push_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE push_subscriptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_fence ON push_subscriptions
    USING (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON push_subscriptions TO suite_app;

-- Keys BizNez itself keeps (not per business), e.g. the Web Push key pair; secret values are encrypted with SecretBox.
CREATE TABLE platform_secrets (
    name        varchar(60) PRIMARY KEY,
    value       text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT ON platform_secrets TO suite_app;

ALTER TABLE deliveries ADD COLUMN acknowledged_at timestamptz;   -- someone on the staff saw the new online delivery
ALTER TABLE deliveries ADD COLUMN acknowledged_by uuid;
ALTER TABLE deliveries ADD COLUMN rider_seen_at   timestamptz;   -- the rider saw the trip given to them
