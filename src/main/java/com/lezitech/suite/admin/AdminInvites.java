package com.lezitech.suite.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.auth.TokenVersions;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Invites for LeziTech control-panel colleagues. The colleague is created switched off with a password nobody knows,
 * and gets an emailed link to choose their own. The link's secret is 256 random bits, only its hash is stored, it works
 * once and it expires. No password is ever in the email, a log, an audit entry or an API response.
 */
@Service
public class AdminInvites {

    /**
     * Three days, the same as the 3-day "welcome" code a business owner gets for their staff (LoginCodes.WELCOME_TTL).
     * Long enough for a colleague who reads email once a day or is away for a weekend; short enough that a link
     * sitting in an old inbox stops being a way into an account that can see every business. A lost or expired link is
     * cheap to fix: "Send the invite again" makes a new one and cancels the old one.
     */
    public static final Duration INVITE_TTL = Duration.ofDays(3);
    /**
     * Fifteen minutes for a password-reset code. A reset is asked for by someone sitting at the screen, right now, and
     * a code is short enough to be guessed, so it lives briefly and is spent after 5 wrong tries (MAX_ATTEMPTS).
     */
    public static final Duration RESET_TTL = Duration.ofMinutes(15);
    /** Wrong guesses allowed against one reset code before it is spent. */
    static final int MAX_ATTEMPTS = 5;
    /**
     * Wrong guesses allowed against one person's reset codes in a day, counted in the database so a restart or a second
     * server cannot reset it. Past this, no code works for them until the day is up.
     */
    static final int MAX_WRONG_PER_DAY = 20;
    static final String INVITE = "invite";
    static final String RESET = "reset";
    public static final int MIN_PASSWORD = 10;
    /** At most this many invites per colleague per hour, so nobody can flood an inbox (as LoginCodes does). */
    static final int MAX_PER_HOUR = 4;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(AdminInvites.class);

    /**
     * Reset asks are worked off here, never on the request thread, so an address on the team costs the caller exactly
     * what an unknown one does. Small and bounded: a flood is dropped rather than queued without end.
     */
    private final ThreadPoolExecutor resets = newResetExecutor();

    private static ThreadPoolExecutor newResetExecutor() {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200), r -> {
            Thread t = new Thread(r, "admin-reset-mail");
            t.setDaemon(true);
            return t;
        });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Mailer mailer;
    private final TokenVersions versions;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    /** A real bcrypt hash to check against when there is no code to check, so every failure costs the same time. */
    private final String dummyHash;

    public AdminInvites(JdbcTemplate jdbc, PasswordEncoder encoder, Mailer mailer, TokenVersions versions, ObjectMapper json,
                        PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mailer = mailer;
        this.versions = versions;
        this.json = json;
        this.dummyHash = encoder.encode("000000");
    }

    /** A password hash for an account that must not be signed into yet: the password it came from is thrown away. */
    public String unusablePasswordHash() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return encoder.encode(HexFormat.of().formatHex(b));
    }

    /** Make a fresh link for this colleague (cancelling older ones) and email it. Returns the email status (SENT, OFF, ...). */
    @Transactional
    public String send(UUID adminId) {
        return issue(adminId, INVITE);
    }

    /**
     * Someone asked to reset a forgotten password. Returns at once: the lookup, the link and the email all happen on a
     * background thread, so the time taken is the same for every address and the answer cannot say who is on the team.
     */
    public void requestResetLater(String rawEmail) {
        try {
            resets.execute(() -> requestReset(rawEmail));
        } catch (RejectedExecutionException busy) {
            log.warn("Password-reset ask dropped: the background queue is full");
        }
    }

    /**
     * The work behind {@link #requestResetLater}. Never throws and never says whether the email is on the team. Blocked
     * people and people who never accepted are refused: they must use their invite, or reset would be a side door.
     */
    void requestReset(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, name, active, invite_pending, blocked_at FROM platform_admins WHERE lower(email) = ?", email);
            if (rows.isEmpty()) {
                return;
            }
            Map<String, Object> r = rows.get(0);
            UUID id = (UUID) r.get("id");
            boolean allowed = r.get("blocked_at") == null && Boolean.TRUE.equals(r.get("active")) && !Boolean.TRUE.equals(r.get("invite_pending"));
            String result = allowed ? issueQuietly(id) : "REFUSED";
            audit(id, (String) r.get("name"), "team.reset_request", Map.of("email", email, "result", result));
        } catch (Throwable e) {
            // Never reaches the caller. The address is left out of the log on purpose.
            log.warn("Password-reset ask failed ({}); the person was told the usual words", e.getClass().getSimpleName());
        }
    }

    private String issueQuietly(UUID id) {
        try {
            return issue(id, RESET);
        } catch (ApiException tooMany) {
            return "LIMITED";
        }
    }

    /** Cancel the old link and store the new one as one step; the email goes out after, never inside it. */
    private String issue(UUID adminId, String kind) {
        boolean reset = RESET.equals(kind);
        String token = tx.execute(st -> makeLink(adminId, kind));
        Map<String, Object> a = jdbc.queryForMap("SELECT email, name FROM platform_admins WHERE id = ?", adminId);

        String name = first((String) a.get("name"));
        if (reset) {
            // "token" is the 6-digit code here. It is emailed, never stored in plain form.
            EmailLayout.Msg m = resetEmail(name, token);
            return mailer.send((String) a.get("email"), (String) a.get("name"), m.subject(), m.text(), m.html(), m.preheader());
        }
        EmailLayout.Msg m = inviteEmail(name, mailer.appUrl() + "/admin/invite/" + token);
        return mailer.send((String) a.get("email"), (String) a.get("name"), m.subject(), m.text(), m.html(), m.preheader());
    }

    /** The reset-code email. No code in the subject or the inbox preview line. */
    public static EmailLayout.Msg resetEmail(String name, String code) {
        String text = "Hi " + name + ",\n\nSomeone asked to reset your Busynez control panel password. Your code is:\n\n"
                + code + "\n\nWorks once, for 15 minutes. Type it on the reset page along with your new password. "
                + "This email never asks you to click a link.\n\n"
                + "Didn't ask for this? Ignore this email. Your password stays the same.\n\n"
                + "Busynez will never phone, text or WhatsApp you to ask for this code. Don't share it with anyone.\n\nBusynez";
        String html = EmailLayout.h("Your control panel code", "#101415")
                + EmailLayout.p("Hi " + Mailer.esc(name) + ",")
                + EmailLayout.p("Someone asked to reset your Busynez control panel password. Type this code on the reset page, with your new password.")
                + EmailLayout.code(code, "Works once, for 15 minutes")
                + EmailLayout.p("Didn't ask for this? Ignore this email. Your password stays the same.")
                + EmailLayout.safety("Busynez will never phone, text or WhatsApp you to ask for this code. Don't share it with anyone.");
        return new EmailLayout.Msg("Your Busynez control panel code", text, html, "Use it on the reset page. It works once, for 15 minutes.");
    }

    /** The "choose your password" invite email for a new colleague. */
    public static EmailLayout.Msg inviteEmail(String name, String link) {
        String text = "Hi " + name + ",\n\nYou have been added to the Busynez control panel team. Open this link to choose your own password:\n\n"
                + link + "\n\nThe link works once and for 3 days. Nobody else will ever know your password.\n"
                + "Weren't expecting this? Ignore this email and nothing happens.\n\nBusynez";
        String html = EmailLayout.h("You're on the Busynez control panel team", "#101415")
                + EmailLayout.p("Hi " + Mailer.esc(name) + ",")
                + EmailLayout.p("You have been added to the Busynez control panel. Choose your own password to get in.")
                + EmailLayout.button("Choose my password", link, "#101415")
                + EmailLayout.p("The link works once and for 3 days. Nobody else will ever know your password.")
                + EmailLayout.p("Weren't expecting this? Ignore this email and nothing happens.");
        return new EmailLayout.Msg("Choose your Busynez control panel password", text, html, "Choose your own password to get in. The link works for 3 days.");
    }

    /** Inside one transaction (joins send()'s own): checks the hourly limit for this kind, cancels old links, stores the new one. */
    private String makeLink(UUID adminId, String kind) {
        boolean reset = RESET.equals(kind);
        Map<String, Object> a = jdbc.queryForMap("SELECT email, name FROM platform_admins WHERE id = ?", adminId);
        Integer recent = jdbc.queryForObject(
                "SELECT count(*) FROM admin_invites WHERE admin_id = ? AND kind = ? AND created_at > now() - interval '1 hour'", Integer.class, adminId, kind);
        if (recent != null && recent >= MAX_PER_HOUR) {
            throw ApiException.bad("We've sent a few " + (reset ? "codes" : "invites") + " to " + a.get("email") + " already. Ask them to check spam, or try again in an hour.");
        }
        jdbc.update("UPDATE admin_invites SET used_at = now() WHERE admin_id = ? AND used_at IS NULL AND kind = ?", adminId, kind);
        String token;
        String stored;
        if (reset) {
            token = String.format("%06d", RANDOM.nextInt(1_000_000));
            stored = encoder.encode(token);
        } else {
            byte[] b = new byte[32];
            RANDOM.nextBytes(b);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            stored = hash(token);
        }
        jdbc.update("INSERT INTO admin_invites (admin_id, token_hash, expires_at, kind) VALUES (?, ?, ?, ?)",
                adminId, stored, Timestamp.from(Instant.now().plus(reset ? RESET_TTL : INVITE_TTL)), kind);
        return token;
    }

    /** What the invite page needs: the person's name and email. Refuses a link that is unknown, used or expired. */
    public Map<String, Object> look(String token) {
        Map<String, Object> row = find(token, false);
        return Map.of("name", row.get("name"), "email", row.get("email"), "kind", row.get("kind"));
    }

    /** Set the password, switch the account on and spend the link. The password is only ever stored as a hash. */
    @Transactional
    public Map<String, Object> accept(String token, String password) {
        Map<String, Object> row = find(token, true);
        if (password == null || password.length() < MIN_PASSWORD) {
            throw ApiException.bad("Use at least " + MIN_PASSWORD + " characters for control-panel passwords");
        }
        UUID adminId = (UUID) row.get("admin_id");
        jdbc.update("UPDATE platform_admins SET password_hash = ?, active = true, invite_pending = false WHERE id = ? AND blocked_at IS NULL",
                encoder.encode(password), adminId);
        jdbc.update("UPDATE admin_invites SET used_at = now() WHERE admin_id = ? AND used_at IS NULL", adminId);
        versions.bump(adminId, true);
        audit(adminId, (String) row.get("name"), "team.invite_accept", Map.of("email", row.get("email"), "role", row.get("role")));
        return Map.of("ok", true, "email", row.get("email"));
    }

    /**
     * Finish a forgotten-password reset with the emailed code. Every failure (unknown email, no code, expired, spent,
     * wrong, blocked, never accepted) gives the same error, and one bcrypt check always runs so timing matches.
     * Only the password changes; an account is never switched on from here.
     */
    public Map<String, Object> resetWithCode(String rawEmail, String rawCode, String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw ApiException.bad("Use at least " + MIN_PASSWORD + " characters for control-panel passwords");
        }
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        String code = rawCode == null ? "" : rawCode.trim();
        Boolean ok = tx.execute(st -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT i.id, i.token_hash, i.attempts, a.id AS admin_id, a.name, a.email,
                           (SELECT coalesce(sum(j.attempts), 0) FROM admin_invites j WHERE j.admin_id = a.id AND j.kind = 'reset'
                              AND j.created_at > now() - interval '24 hours') AS wrong_today
                    FROM admin_invites i JOIN platform_admins a ON a.id = i.admin_id
                    WHERE lower(a.email) = ? AND i.kind = 'reset' AND i.used_at IS NULL AND i.expires_at > now()
                      AND a.blocked_at IS NULL AND a.active = true AND a.invite_pending = false
                    ORDER BY i.created_at DESC LIMIT 1 FOR UPDATE OF i""", email);
            if (rows.isEmpty()) {
                encoder.matches(code, dummyHash);
                return false;
            }
            Map<String, Object> r = rows.get(0);
            if (((Number) r.get("wrong_today")).intValue() >= MAX_WRONG_PER_DAY) {
                encoder.matches(code, dummyHash);
                jdbc.update("UPDATE admin_invites SET used_at = now() WHERE id = ?", r.get("id"));
                return false;
            }
            // Exactly one bcrypt check either way: a malformed code is checked against the dummy hash.
            boolean formatOk = code.matches("\\d{6}");
            boolean right = encoder.matches(code, formatOk ? (String) r.get("token_hash") : dummyHash) && formatOk;
            UUID adminId = (UUID) r.get("admin_id");
            if (!right) {
                int attempts = ((Number) r.get("attempts")).intValue() + 1;
                jdbc.update("UPDATE admin_invites SET attempts = ?, used_at = CASE WHEN ? >= ? THEN now() ELSE used_at END WHERE id = ?",
                        attempts, attempts, MAX_ATTEMPTS, r.get("id"));
                return false;
            }
            jdbc.update("UPDATE platform_admins SET password_hash = ? WHERE id = ? AND blocked_at IS NULL", encoder.encode(password), adminId);
            jdbc.update("UPDATE admin_invites SET used_at = now() WHERE admin_id = ? AND used_at IS NULL", adminId);
            versions.bump(adminId, true);
            audit(adminId, (String) r.get("name"), "team.reset_complete", Map.of("email", r.get("email")));
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESET_CODE_WRONG", "That code is not right or has expired. Ask for a new code.");
        }
        return Map.of("ok", true);
    }

    /** Cancel any link still waiting, e.g. when the person is switched off before they accept. */
    public void cancel(UUID adminId) {
        jdbc.update("UPDATE admin_invites SET used_at = now() WHERE admin_id = ? AND used_at IS NULL", adminId);
    }

    private Map<String, Object> find(String token, boolean lock) {
        ApiException unknown = new ApiException(HttpStatus.NOT_FOUND, "INVITE_UNKNOWN",
                "This link is not one of ours. Ask the person who added you to send the invite again.");
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw unknown;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.id, i.admin_id, i.used_at, i.expires_at, i.kind, a.name, a.active, a.email, a.role, a.invite_pending, a.blocked_at
                FROM admin_invites i JOIN platform_admins a ON a.id = i.admin_id
                WHERE i.token_hash = ? AND i.kind = 'invite'""" + (lock ? " FOR UPDATE OF i" : ""), hash(token));
        if (rows.isEmpty()) {
            throw unknown;
        }
        Map<String, Object> r = rows.get(0);
        if (r.get("blocked_at") != null) {
            throw new ApiException(HttpStatus.GONE, "INVITE_BLOCKED",
                    "This invite is no longer valid. Ask the person who added you if you still need access.");
        }
        // Reset rows never reach here (the query takes invites only). An invite is for someone who has not accepted.
        if (r.get("used_at") != null || !Boolean.TRUE.equals(r.get("invite_pending"))) {
            throw new ApiException(HttpStatus.GONE, "INVITE_USED",
                    "This link has already been used, or a newer one was sent. If you have set your password, sign in. Otherwise ask for a new invite.");
        }
        if (((Timestamp) r.get("expires_at")).toInstant().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "INVITE_EXPIRED",
                    "This link has expired. Ask the person who added you to send the invite again.");
        }
        return r;
    }

    /** For actions with no signed-in admin (accepting an invite): same table as AdminService.audit, written as that person. */
    private void audit(UUID adminId, String name, String action, Map<String, Object> detail) {
        try {
            jdbc.update("INSERT INTO platform_audit (admin_id, admin_name, action, detail) VALUES (?, ?, ?, cast(? as jsonb))",
                    adminId, name, action, json.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (Exception e) {
            // An audit failure must not undo the invite; the password is never in this entry.
        }
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String first(String name) {
        return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
    }
}
