-- Bumped when someone is switched off, changes password or job: their older tokens stop working.
ALTER TABLE users ADD COLUMN token_version integer NOT NULL DEFAULT 0;
ALTER TABLE platform_admins ADD COLUMN token_version integer NOT NULL DEFAULT 0;
