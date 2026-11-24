-- Forgotten-password resets now use a 6-digit emailed code instead of a link. For a reset row, token_hash holds a
-- bcrypt hash of the code (60 characters, salted, so the UNIQUE constraint can never clash); invite rows still hold the
-- SHA-256 of their link secret. "attempts" counts wrong guesses against a reset code; at 5 the code is spent.
ALTER TABLE admin_invites ADD COLUMN attempts int NOT NULL DEFAULT 0;
ALTER TABLE admin_invites ALTER COLUMN token_hash TYPE varchar(100);
-- Reset links sent before this change hold a SHA-256 hash, not a code: spend them so nothing old stays open.
UPDATE admin_invites SET used_at = now() WHERE kind = 'reset' AND used_at IS NULL;
