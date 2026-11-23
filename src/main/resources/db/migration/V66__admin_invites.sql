-- Inviting a LeziTech colleague by email: they set their own password from a one-off link, so nobody types a
-- password for them and nothing secret travels over chat. Only a hash of the link's secret is kept here.
ALTER TABLE platform_admins ADD COLUMN invite_pending boolean NOT NULL DEFAULT false;

-- "active = false" alone cannot tell "invited, not accepted yet" from "deliberately switched off". blocked_at is the
-- durable "refused" mark: set when someone is switched off, cleared when switched back on. While set, no invite can be
-- re-sent and no old link can switch the account on.
ALTER TABLE platform_admins ADD COLUMN blocked_at timestamptz;

CREATE TABLE admin_invites (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    uuid NOT NULL REFERENCES platform_admins(id) ON DELETE CASCADE,
    token_hash  varchar(64) NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_admin_invites_admin ON admin_invites (admin_id, created_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON admin_invites TO suite_app;
