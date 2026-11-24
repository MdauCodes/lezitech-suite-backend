package com.lezitech.suite.auth;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.notify.EmailLayout;
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
        sendResetCode(emailRaw, false);
    }

    /** forPin: a staff member resetting their own till PIN (same code, worded for a PIN). */
    public void sendResetCode(String emailRaw, boolean forPin) {
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
        EmailLayout.Msg m = resetEmail(first((String) rows.get(0).get("name")), code, forPin);
        mailer.send(email, (String) rows.get(0).get("name"), m.subject(), m.text(), m.html(), m.preheader());
    }

    private static final String SAFETY = "Busynez will never phone, text or WhatsApp you to ask for this code. Don't share it with anyone.";

    /** The reset-code email (password or till PIN). The subject starts with the code on purpose; the inbox preview line has none. */
    public static EmailLayout.Msg resetEmail(String name, String code, boolean forPin) {
        String what = forPin ? "till PIN" : "password";
        String text = "Hi " + name + ",\n\nYour code to set a new Busynez " + what + " is " + code + ".\nWorks once, for 15 minutes.\n\n"
                + "Didn't ask for this? Ignore this email. Your " + what + " stays the same.\n\n" + SAFETY + "\n\nBusynez";
        String html = EmailLayout.h(forPin ? "Your till PIN code" : "Your password code", "#101415")
                + EmailLayout.p("Hi " + Mailer.esc(name) + ",")
                + EmailLayout.p("Use this code to set a new Busynez " + what + ".")
                + EmailLayout.code(code, "Works once, for 15 minutes")
                + EmailLayout.p("Didn't ask for this? Ignore this email. Your " + what + " stays the same.")
                + EmailLayout.safety(SAFETY);
        return new EmailLayout.Msg(code + " is your Busynez code", text, html, "Use it to set a new " + what + ". It works for 15 minutes.");
    }

    /**
     * A staff invite (or a fresh sign-in code from the owner or LeziTech): a 3-day code and a link that opens "set my
     * password" with their email filled in. Returns the email status (SENT, LOGGED, ...). At most 4 an hour.
     */
    public String sendInvite(UUID user, String email, String fullName, String business, String slug, String job, String byName, String appUrl) {
        Integer recent = jdbc.queryForObject(
                "SELECT count(*) FROM login_codes WHERE user_id = ? AND created_at > now() - interval '1 hour'", Integer.class, user);
        if (recent != null && recent >= MAX_PER_HOUR) {
            throw ApiException.bad("We've sent a few codes to " + email + " already. Ask them to check spam, or try again in an hour.");
        }
        String code = issue(user, "WELCOME", WELCOME_TTL);
        EmailLayout.Msg m = inviteEmail(first(fullName), code, email, business, slug, job, byName, appUrl);
        return mailer.send(email, fullName, m.subject(), m.text(), m.html(), m.preheader());
    }

    /** The staff-joining email. The subject starts with the code on purpose; the inbox preview line has none. */
    public static EmailLayout.Msg inviteEmail(String name, String code, String email, String business, String slug, String job, String byName, String appUrl) {
        String setPassword = appUrl + "/reset?email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8) + "&w=" + slug;
        String signIn = appUrl + "/w/" + slug;
        String role = job == null || job.isBlank() ? "" : " as " + job;
        String text = "Hi " + name + ",\n\n" + byName + " added you to " + business + " on Busynez" + role + ".\n\n"
                + "1. Open " + setPassword + "\n2. Enter the code " + code + " (it works for 3 days) and choose your password.\n"
                + "3. After that, sign in any time at " + signIn + " with " + email + ".\n\n"
                + "Weren't expecting this? Ignore this email and nothing happens.\n\n" + SAFETY + "\n\n" + business;
        String html = EmailLayout.h("You've been added to " + business, "#101415")
                + EmailLayout.p("Hi " + Mailer.esc(name) + ",")
                + EmailLayout.p(Mailer.esc(byName) + " added you to <b>" + Mailer.esc(business) + "</b> on Busynez" + Mailer.esc(role)
                + ". Use this code to choose your password.")
                + EmailLayout.code(code, "Works for 3 days")
                + EmailLayout.button("Set my password", setPassword, "#101415")
                + EmailLayout.p("After that, sign in any time at <a href=\"" + Mailer.esc(signIn) + "\" style=\"color:#101415\">" + Mailer.esc(signIn) + "</a> with "
                + Mailer.esc(email) + ".")
                + EmailLayout.p("Weren't expecting this? Ignore this email and nothing happens.")
                + EmailLayout.safety(SAFETY);
        return new EmailLayout.Msg(code + " is your code to join " + business + " on Busynez", text, html,
                byName + " added you to " + business + ". Choose your password to get started.");
    }

    /** "Confirm your email" from Home: a code to the address on the account. At most 4 an hour. */
    public void sendConfirmCode(UUID user, String email, String fullName) {
        Integer recent = jdbc.queryForObject(
                "SELECT count(*) FROM login_codes WHERE user_id = ? AND purpose = 'CONFIRM' AND created_at > now() - interval '1 hour'", Integer.class, user);
        if (recent != null && recent >= MAX_PER_HOUR) {
            throw ApiException.bad("We've sent a few codes already. Check spam, or try again in an hour.");
        }
        String code = issue(user, "CONFIRM", RESET_TTL);
        EmailLayout.Msg m = confirmEmail(first(fullName), code);
        mailer.send(email, fullName, m.subject(), m.text(), m.html(), m.preheader());
    }

    /** The "confirm your email" email. The subject starts with the code on purpose; the inbox preview line has none. */
    public static EmailLayout.Msg confirmEmail(String name, String code) {
        String text = "Hi " + name + ",\n\nYour code to confirm this email for Busynez is " + code + ".\nWorks once, for 15 minutes.\n\n"
                + "Confirming means you can always get back into your account.\n\n"
                + "Didn't ask for this? Ignore this email and nothing changes.\n\n" + SAFETY + "\n\nBusynez";
        String html = EmailLayout.h("Confirm your email", "#101415")
                + EmailLayout.p("Hi " + Mailer.esc(name) + ",")
                + EmailLayout.p("Use this code to confirm this email for Busynez. Then you can always get back into your account.")
                + EmailLayout.code(code, "Works once, for 15 minutes")
                + EmailLayout.p("Didn't ask for this? Ignore this email and nothing changes.")
                + EmailLayout.safety(SAFETY);
        return new EmailLayout.Msg(code + " confirms your Busynez email", text, html, "Use it to confirm this email. It works for 15 minutes.");
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
