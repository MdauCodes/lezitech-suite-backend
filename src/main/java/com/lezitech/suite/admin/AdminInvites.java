package com.lezitech.suite.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.auth.TokenVersions;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.Mailer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public static final int MIN_PASSWORD = 10;
    /** At most this many invites per colleague per hour, so nobody can flood an inbox (as LoginCodes does). */
    static final int MAX_PER_HOUR = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Mailer mailer;
    private final TokenVersions versions;
    private final ObjectMapper json;

    public AdminInvites(JdbcTemplate jdbc, PasswordEncoder encoder, Mailer mailer, TokenVersions versions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mailer = mailer;
        this.versions = versions;
        this.json = json;
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
        Map<String, Object> a = jdbc.queryForMap("SELECT email, name FROM platform_admins WHERE id = ?", adminId);
        Integer recent = jdbc.queryForObject(
                "SELECT count(*) FROM admin_invites WHERE admin_id = ? AND created_at > now() - interval '1 hour'", Integer.class, adminId);
        if (recent != null && recent >= MAX_PER_HOUR) {
            throw ApiException.bad("We've sent a few invites to " + a.get("email") + " already. Ask them to check spam, or try again in an hour.");
        }
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        jdbc.update("UPDATE admin_invites SET used_at = now() WHERE admin_id = ? AND used_at IS NULL", adminId);
        jdbc.update("INSERT INTO admin_invites (admin_id, token_hash, expires_at) VALUES (?, ?, ?)",
                adminId, hash(token), Timestamp.from(Instant.now().plus(INVITE_TTL)));

        String name = first((String) a.get("name"));
        String link = mailer.appUrl() + "/admin/invite/" + token;
        String text = "Hi " + name + ",\n\nYou have been added to the LeziTech control panel. Open this link to choose your own password:\n\n"
                + link + "\n\nThe link works once and for 3 days. Nobody else will ever know your password.\n"
                + "If you were not expecting this, ignore this email and nothing happens.\n\nLeziTech";
        String html = EmailLayout.p("Hi " + Mailer.esc(name) + ",")
                + EmailLayout.p("You have been added to the LeziTech control panel. Choose your own password to get in.")
                + EmailLayout.button("Choose my password", link, "#101415")
                + EmailLayout.p("The link works once and for 3 days. Nobody else will ever know your password. "
                + "If you were not expecting this, ignore this email and nothing happens.");
        return mailer.send((String) a.get("email"), (String) a.get("name"), "Choose your LeziTech control panel password", text, html);
    }

    /** What the invite page needs: the person's name and email. Refuses a link that is unknown, used or expired. */
    public Map<String, Object> look(String token) {
        Map<String, Object> row = find(token, false);
        return Map.of("name", row.get("name"), "email", row.get("email"));
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
                SELECT i.id, i.admin_id, i.used_at, i.expires_at, a.name, a.email, a.role, a.invite_pending, a.blocked_at
                FROM admin_invites i JOIN platform_admins a ON a.id = i.admin_id
                WHERE i.token_hash = ?""" + (lock ? " FOR UPDATE OF i" : ""), hash(token));
        if (rows.isEmpty()) {
            throw unknown;
        }
        Map<String, Object> r = rows.get(0);
        if (r.get("blocked_at") != null) {
            throw new ApiException(HttpStatus.GONE, "INVITE_BLOCKED",
                    "This invite is no longer valid. Ask the person who added you if you still need access.");
        }
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
