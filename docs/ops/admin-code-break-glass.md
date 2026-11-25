# Control panel sign-in when the email code does not arrive

Every control panel sign-in needs a 6-digit code emailed to the person. If email delivery is down, nobody can sign in.
There is deliberately no setting that skips the code, and codes are never written to logs. The only way round is this
step, which needs access to the production database.

## Who may do it
Only the person who runs the server, and only after confirming (by phone or in person) that the team member really is
who they say they are.

## Steps
1. Ask the team member to type their email and password on the sign-in page once. They will see "We couldn't send your
   code". This makes sure no older code is waiting.
2. On a machine that can reach the database, run:

   `DATABASE_URL=postgres://... ops/admin-code-break-glass.sh person@example.com`

   (A database owner is needed the first time, because the script switches on the `pgcrypto` extension.)
3. The script prints a one-time code. Read it to the team member yourself. It is valid for 10 minutes, works once, and
   only a hash of it is stored.
4. On the sign-in page the team member presses "I already have a code", types their email and the code, and signs in.

## What is recorded
The script writes `auth.break_glass_code` to the platform audit (who ran it and for which email). The team member's
sign-in is recorded as usual. The code itself is never stored or logged.

## Afterwards
Find out why email failed and fix it. Check the audit for `auth.break_glass_code` rows you did not expect.
