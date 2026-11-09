-- Where each business came from, so ad spend can be judged by paying customers, not clicks.
-- acquisition: first-touch utm_source / utm_medium / utm_campaign / utm_content / utm_term, fbclid, fbc, fbp,
-- gclid, landing page and referrer, captured by the website and sent with signup or a setup request.
ALTER TABLE tenants ADD COLUMN acquisition jsonb;
ALTER TABLE setup_requests ADD COLUMN acquisition jsonb;

-- Referrals: every workspace gets a code; a business that signs up with it is linked to the one that referred it.
-- Both get a free month when the new business makes its first payment.
ALTER TABLE tenants ADD COLUMN referral_code varchar(12) UNIQUE;
ALTER TABLE tenants ADD COLUMN referred_by uuid REFERENCES tenants(id) ON DELETE SET NULL;
ALTER TABLE tenants ADD COLUMN referral_rewarded_at timestamptz;

-- Conversions reported to Meta (Conversions API), sent from a queue so a slow or failed call never blocks a signup.
-- No RLS: written for the platform, read only by the sender and the control panel.
CREATE TABLE marketing_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid REFERENCES tenants(id) ON DELETE CASCADE,
    event_name  varchar(40)  NOT NULL,            -- Lead | StartTrial | Subscribe | Purchase
    event_id    varchar(80)  NOT NULL,            -- shared with the browser pixel so Meta counts it once
    payload     jsonb        NOT NULL,            -- the event as Meta receives it (personal data already hashed)
    status      varchar(10)  NOT NULL DEFAULT 'PENDING',  -- PENDING | SENT | FAILED | OFF
    attempts    integer      NOT NULL DEFAULT 0,
    last_error  text,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    sent_at     timestamptz
);
CREATE INDEX marketing_events_pending ON marketing_events (created_at) WHERE status = 'PENDING';

GRANT SELECT, INSERT, UPDATE ON marketing_events TO suite_app;
