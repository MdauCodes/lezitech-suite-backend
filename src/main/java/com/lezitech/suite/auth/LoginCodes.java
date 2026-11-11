package com.lezitech.suite.auth;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.notify.Mailer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 6-digit codes sent by email for "forgot password" and for the first password of a workspace the
 * LeziTech team set up. Only a hash is stored. A new code cancels the older ones; a code works once,
 * expires (15 minutes for a reset, 3 days for a welcome) and allows 5 wrong tries.
 */
@Service
public class LoginCodes {

    public static final Duration RESET_TTL = Duration.ofMinutes(15);
    public static final Duration WELCOME_TTL = Duration.ofDays(3);
    static final int MAX_TRIES = 5;
    /** At most this many reset codes per account per hour, so nobody can flood someone's inbox. */
    static final int MAX_PER_HOUR = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Mailer mailer;

    public LoginCodes(JdbcTemplate jdbc, PasswordEncoder encoder, Mailer mailer) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mailer = mailer;
    }

    /** Make a new code for this person, cancelling any older unused ones. Returns the plain code (to put in an email). */
    @Transactional
    public String issue(UUID userId, String purpose, Duration ttl) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("UPDATE login_codes SET used_at = now() WHERE user_id = ? AND used_at IS NULL", userId);
        jdbc.update("INSERT INTO login_codes (user_id, purpose, code_hash, expires_at) VALUES (?, ?, ?, ?)",
                userId, purpose, encoder.encode(code), Timestamp.from(Instant.now().plus(ttl)));
        return code;
    }

    /** "Forgot password": email a code if the address belongs to an active account. Says nothing either way. */
    public void sendResetCode(String emailRaw) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name FROM users WHERE email = ? AND active", email);
        if (rows.isEmpty()) {
            return;
        }
        UUID user = (UUID) rows.get(0).get("id");
        Integer recent = jdbc.queryForObject(
                "SELECT count(*) FROM login_codes WHERE user_id = ? AND purpose = 'RESET' AND created_at > now() - interval '1 hour'", Integer.class, user);
        if (recent != null && recent >= MAX_PER_HOUR) {
            return;
        }
        String code = issue(user, "RESET", RESET_TTL);
        String name = first((String) rows.get(0).get("name"));
        mailer.send(email, (String) rows.get(0).get("name"), code + " is your BizNez code",
                "Hi " + name + ",\n\nYour code to set a new BizNez password is " + code + ".\nIt works for 15 minutes.\n\n"
                        + "If you didn't ask for this, ignore this email; your password stays the same.\n\nLeziTech",
                "<p>Hi " + Mailer.esc(name) + ",</p><p>Your code to set a new BizNez password is:</p>"
                        + "<p style=\"font-size:28px;font-weight:bold;letter-spacing:6px;margin:8px 0\">" + code + "</p>"
                        + "<p>It works for 15 minutes. If you didn't ask for this, ignore this email; your password stays the same.</p>");
    }

    /** Check the code for this email and use it up. Returns the user id; a wrong code counts as a try. */
    @Transactional(noRollbackFor = ApiException.class)
    public UUID consume(String emailRaw, String codeRaw) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        String code = codeRaw == null ? "" : codeRaw.replaceAll("\\s", "");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.user_id, c.code_hash, c.attempts, c.expires_at FROM login_codes c JOIN users u ON u.id = c.user_id
                WHERE u.email = ? AND u.active AND c.used_at IS NULL ORDER BY c.created_at DESC LIMIT 1 FOR UPDATE OF c""", email);
        ApiException wrong = new ApiException(HttpStatus.BAD_REQUEST, "BAD_CODE", "That code is wrong or has expired. Ask for a new one.");
        if (rows.isEmpty()) {
            throw wrong;
        }
        Map<String, Object> c = rows.get(0);
        UUID id = (UUID) c.get("id");
        if (((Timestamp) c.get("expires_at")).toInstant().isBefore(Instant.now()) || (Integer) c.get("attempts") >= MAX_TRIES) {
            jdbc.update("UPDATE login_codes SET used_at = now() WHERE id = ?", id);
            throw wrong;
        }
        if (!code.matches("[0-9]{6}") || !encoder.matches(code, (String) c.get("code_hash"))) {
            jdbc.update("UPDATE login_codes SET attempts = attempts + 1 WHERE id = ?", id);
            throw wrong;
        }
        jdbc.update("UPDATE login_codes SET used_at = now() WHERE id = ?", id);
        return (UUID) c.get("user_id");
    }

    static String first(String name) {
        if (name == null || name.isBlank()) {
            return "there";
        }
        return name.trim().split("\\s+")[0];
    }
}
