#!/bin/sh
# Break-glass: give one control-panel person a one-time sign-in code when email delivery is down.
# Needs database access (DATABASE_URL, a database owner). Read docs/ops/admin-code-break-glass.md first.
# The code is valid for 10 minutes, works once, is stored only as a hash, and the use is written to the platform audit.
set -eu
EMAIL=$(printf %s "${1:?Usage: admin-code-break-glass.sh person@example.com}" | tr 'A-Z' 'a-z')
: "${DATABASE_URL:?Set DATABASE_URL to the production database}"
CODE=$(od -An -N4 -tu4 /dev/urandom | tr -d ' ' | awk '{printf "%06d", $1 % 1000000}')
OPERATOR="${USER:-unknown}"
DONE=$(psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -tA -v email="$EMAIL" -v code="$CODE" -v operator="$OPERATOR" <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
WITH a AS (
  SELECT id, name FROM platform_admins
  WHERE email = :'email' AND active AND NOT invite_pending AND blocked_at IS NULL
), spent AS (
  UPDATE admin_codes SET used_at = now()
  WHERE admin_id IN (SELECT id FROM a) AND kind = 'signin' AND used_at IS NULL RETURNING 1
), made AS (
  INSERT INTO admin_codes (admin_id, kind, code_hash, expires_at)
  SELECT id, 'signin', crypt(:'code', gen_salt('bf', 10)), now() + interval '10 minutes' FROM a RETURNING admin_id
)
INSERT INTO platform_audit (admin_id, admin_name, action, detail)
SELECT a.id, a.name, 'auth.break_glass_code', jsonb_build_object('by', :'operator', 'email', :'email')
FROM a JOIN made ON made.admin_id = a.id
RETURNING 1;
SQL
)
if [ -z "$DONE" ]; then
  echo "No active team member with that email, so no code was made." >&2
  exit 1
fi
echo "One-time code for $EMAIL (valid 10 minutes): $CODE"
echo "Give it to that person yourself, by phone or in person. It is not stored anywhere else."
