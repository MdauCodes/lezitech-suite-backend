-- Forgotten-password links reuse the invite table and its hashed single-use token; "kind" says which a row is.
-- Existing rows are all invites.
ALTER TABLE admin_invites ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'invite' CHECK (kind IN ('invite', 'reset'));
