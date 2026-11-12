-- Staff can be added with just a name, a job and a till PIN: many cashiers, waiters and stylists have no email.
-- An email is optional (it lets them reset their own PIN with a 6-digit code); a password only if they also sign in
-- with email. Owners still sign up with both. Postgres keeps emails unique and allows any number of empty ones.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
