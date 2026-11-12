-- Owners sign up without an email check (quick start); during the trial Home asks them to confirm it with a 6-digit
-- code, so a mistyped address is caught before they need "Forgot password". Using a code from that email (password or
-- PIN reset, the hand-over welcome code) also confirms it. Everyone who already has an account counts as confirmed.
ALTER TABLE users ADD COLUMN email_confirmed_at timestamptz;
UPDATE users SET email_confirmed_at = now() WHERE email IS NOT NULL;
