-- The website's "Let us set it up" sheet knows the visitor's name, phone and business; when they then tap "Start free",
-- signup in the app opens filled in. The details pass through here under a random one-time code (the only thing in the
-- link), read once and gone, or expired after 30 minutes. Not tied to any workspace: no row-level security.
CREATE TABLE signup_handovers (
    code        varchar(40) PRIMARY KEY,
    data        jsonb NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_signup_handovers_expiry ON signup_handovers (expires_at);

GRANT SELECT, INSERT, DELETE ON signup_handovers TO suite_app;
