-- The wall between businesses reaches the staff table.
--
-- `users` is the sign-in table and had no row-level security, so every fence on it was a hand-written
-- "AND u.tenant_id = :t" in Java. On 21 Nov 2026 three reviews in one day found five places where that
-- line was missing, one of them readable from a public link that needs no sign-in: a cashier in one
-- business could put another business's staff member on a sale or a booking, and that person's name and
-- job then appeared on a report. Those five are fixed, but a dozen more "LEFT JOIN users u ON u.id = ..."
-- remain correct only because the rows they could reach happen to be fenced elsewhere. This moves the
-- fence into the database for every query that runs inside a transaction.
--
-- READ THIS BEFORE YOU TRUST IT: what the fence does and does not cover
-- ---------------------------------------------------------------------
-- The fence is up whenever `app.tenant` is set, and `app.tenant` is set by exactly one thing:
-- tenant/TenantTransactionManager, at the start of a Spring transaction. So the fence follows the
-- TRANSACTION BOUNDARY, not the kind of screen being served.
--
--   * Inside a transaction with a signed-in caller, or inside TenantContext.runAs (public customer pages,
--     background jobs), `app.tenant` holds that business and this policy confines every read and write on
--     `users` to it, whether or not the SQL remembers to say so. That is where the money, report, till,
--     booking, team and portal screens live - they have to be transactional to read the other fenced
--     tables - and it is where all five bugs found on 21 Nov were, the public one included.
--
--   * OUTSIDE any transaction `app.tenant` is never set at all, and this policy then permits everything.
--     `users` is therefore the one fenced table in the database that fails OPEN rather than closed: every
--     other table uses the strict form (see V2__business.sql around line 304, no "IS NULL OR" branch) and
--     comes back empty with no business set. Non-transactional endpoints that read `users` are NOT backed
--     by the database and still depend entirely on their own "AND tenant_id = ?". The live example to keep
--     in mind is auth/AuthController's /api/auth/pin till hand-over: it takes the staff member's id
--     straight from the request body, AuthController is not @Transactional, and the only thing keeping that
--     hand-over inside the business is the predicate hand-written on that query. Others of the same kind:
--     /auth/staff-pin, /auth/me, /auth/confirm-email/send, auth/TokenVersions, admin/AdminController's
--     console lists, onboarding/OnboardingController, property/ToLetController's public enquiry (which
--     reads the owner's row just BEFORE its runAs, not inside it), notify/TrialNudgeService,
--     notify/BrandedEmail and settings/PlanPaidEmail.
--
-- Why it is permissive rather than strict: the things that must read `users` with no business in context
-- are all legitimate - signing in by email before anyone knows which business it is (auth/AuthController,
-- auth/LoginCodes), password and PIN links (auth/PasswordController), the LeziTech admin and operator
-- consoles (admin/, platform/), support grants (auth/SupportAccess), an owner with several businesses
-- listing them and switching between them (auth/BusinessesController), and the platform's own email scans
-- over every business. Most of those are single statements outside any transaction, so a transaction-local
-- setting cannot reach them: making them opt in explicitly means wrapping the sign-in path in new
-- transactions. That is a bigger change than this one and not one for launch week. It is written up in
-- platform-review/SECURITY-TODO.md as the stronger follow-up, and until it is done, a new query on a
-- non-transactional path is still one forgotten predicate away from reading across businesses.

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_fence ON users
    USING (nullif(current_setting('app.tenant', true), '') IS NULL
           OR tenant_id = nullif(current_setting('app.tenant', true), '')::uuid)
    WITH CHECK (nullif(current_setting('app.tenant', true), '') IS NULL
           OR tenant_id = nullif(current_setting('app.tenant', true), '')::uuid);

-- Email addresses are unique across the whole platform, so "is this email already in use?" has to look
-- past the fence. It is asked while adding or editing a staff member, which happens inside the business's
-- own transaction, so from V63 that question could only see the asker's own business: the answer would be
-- "no", the insert would then hit the unique index, and the owner would get a 500 instead of plain words.
-- This helper answers it with the fence lowered for the length of the one statement. It gives back a
-- yes-or-no and never a row, and it needs no extra privileges of its own. It does tell a manager whether
-- an address is in use anywhere on the platform; the same thing can be learnt from the sign-up page, though
-- that one is rate-limited and POST /api/team is not.
--
-- NOTE FOR DEPLOYS AND ROLLBACKS: this function and the three Java call sites that use it
-- (team/TeamController twice, auth/AuthController's signup check) must ship together. Flyway cannot undo a
-- migration, so if the Java is rolled back while V63 stays applied - or an old container is still serving
-- during a rolling restart - those checks go back to querying `users` directly inside the tenant's
-- transaction, always answer "free", and produce the unique-index 500 this exists to prevent.
CREATE FUNCTION app_email_taken(p_email text, p_except uuid DEFAULT NULL) RETURNS boolean
    LANGUAGE sql STABLE
    SET "app.tenant" = ''
AS $$
    SELECT exists(SELECT 1 FROM users WHERE email = p_email AND (p_except IS NULL OR id <> p_except));
$$;

GRANT SELECT, INSERT, UPDATE ON users TO suite_app;
GRANT EXECUTE ON FUNCTION app_email_taken(text, uuid) TO suite_app;
