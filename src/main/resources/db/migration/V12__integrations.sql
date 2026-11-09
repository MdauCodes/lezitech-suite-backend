-- Each business's own payment and messaging accounts: Safaricom Daraja (till or paybill), PayHero, and
-- SMS (Africa's Talking). Set by the owner under Settings -> Payments, or by the LeziTech team in the
-- control panel during setup. Keys and passkeys are stored encrypted (SecretBox, AES-GCM); the plain
-- settings hold nothing secret. No RLS, like mpesa_requests: payment callbacks arrive without a login
-- and need the business's account to re-check a payment, so every query filters by tenant_id in code.
CREATE TABLE integrations (
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind            varchar(20) NOT NULL,                     -- DARAJA | PAYHERO | SMS
    enabled         boolean NOT NULL DEFAULT false,           -- DARAJA/PAYHERO: the one the till uses for prompts
    settings        jsonb NOT NULL DEFAULT '{}'::jsonb,
    secrets         text,                                     -- encrypted JSON
    status          varchar(10) NOT NULL DEFAULT 'UNTESTED',  -- UNTESTED | OK | FAILED
    status_message  text,
    tested_at       timestamptz,
    updated_by      varchar(160),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, kind)
);

-- Which provider sent a prompt, and PayHero's own reference for checking its status.
ALTER TABLE mpesa_requests ADD COLUMN provider varchar(10) NOT NULL DEFAULT 'DARAJA';
ALTER TABLE mpesa_requests ADD COLUMN provider_ref varchar(80);

GRANT SELECT, INSERT, UPDATE, DELETE ON integrations TO suite_app;
