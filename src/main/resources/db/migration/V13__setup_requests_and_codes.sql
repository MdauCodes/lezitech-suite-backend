-- Assisted onboarding: businesses ask LeziTech to set them up; the team builds the workspace, then hands
-- it over. The 7-day trial starts at hand-over, not while the team is still setting it up.
CREATE TABLE setup_requests (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name   varchar(200) NOT NULL,
    sector          varchar(30),
    sub_sector      varchar(80),
    contact_name    varchar(120) NOT NULL,
    phone           varchar(30)  NOT NULL,
    email           varchar(200),
    town            varchar(80),
    branches        integer,
    staff           integer,
    payments        text[]       NOT NULL DEFAULT '{}',   -- CASH, MPESA_TILL, MPESA_PAYBILL, CARD, CREDIT, BANK
    current_tool    varchar(120),
    opening_hours   varchar(120),
    notes           text,
    source          varchar(120),                          -- ad campaign / page it came from
    status          varchar(12)  NOT NULL DEFAULT 'NEW',   -- NEW | CONTACTED | IN_SETUP | DONE | LOST
    admin_note      text,
    tenant_id       uuid REFERENCES tenants(id) ON DELETE SET NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_setup_requests_status ON setup_requests (status, created_at DESC);

-- A workspace the team is still building: trial reminders wait and the trial clock starts at hand-over.
ALTER TABLE tenants ADD COLUMN in_setup boolean NOT NULL DEFAULT false;
ALTER TABLE tenants ADD COLUMN handed_over_at timestamptz;

-- One-time 6-digit codes sent by email: password reset, and the first password for a handed-over workspace.
-- Only a hash is stored; a code works once, for a short time, with a few tries.
CREATE TABLE login_codes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     varchar(10) NOT NULL,                    -- RESET | WELCOME
    code_hash   varchar(100) NOT NULL,
    attempts    integer NOT NULL DEFAULT 0,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_codes_user ON login_codes (user_id, created_at DESC);

GRANT SELECT, INSERT, UPDATE ON setup_requests, login_codes TO suite_app;
