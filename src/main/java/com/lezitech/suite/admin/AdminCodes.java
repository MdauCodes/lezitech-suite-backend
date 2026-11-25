package com.lezitech.suite.admin;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.Mailer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The 6-digit codes emailed to a LeziTech person's own address: one at every control-panel sign-in (after the
 * password), and one before opening a business (step-up). A code lives 10 minutes, is spent after 5 wrong tries,
 * works once, and is bound to that person (and, for opening a business, to that one business). Only a bcrypt hash
 * is stored. Every failure answers the same and costs one bcrypt check, so timing says nothing.
 */
@Service
public class AdminCodes {

    public static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 5;
    /** At most this many sign-in codes per person per hour, and (higher, as opening several businesses is normal work) step-up codes. */
    static final int MAX_SIGNIN_PER_HOUR = 6;
    static final int MAX_STEPUP_PER_HOUR = 20;
    static final String SIGNIN = "signin";
    static final String STEPUP = "stepup";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Mailer mailer;
    private final TransactionTemplate tx;
    /** A real bcrypt hash to check against when there is no code to check, so every failure costs the same time. */
    private final String dummyHash;

    public AdminCodes(JdbcTemplate jdbc, PasswordEncoder encoder, Mailer mailer, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mailer = mailer;
        this.tx = new TransactionTemplate(txm);
        this.dummyHash = encoder.encode("000000");
    }

    /** The hash every "no such person" check is run against, so unknown emails cost the same bcrypt work as real ones. */
    public String dummyHash() {
        return dummyHash;
    }

    /** One bcrypt check for nothing, so a failure with no code to check takes as long as one with a code. Always false. */
    public boolean burn(String code) {
        encoder.matches(code == null ? "" : code, dummyHash);
        return false;
    }

    /** Make a sign-in code for this person (cancelling older ones) and email it to their own address. */
    public String sendSignIn(UUID adminId) {
        String code = tx.execute(st -> make(adminId, SIGNIN, null));
        Map<String, Object> a = jdbc.queryForMap("SELECT email, name FROM platform_admins WHERE id = ?", adminId);
        EmailLayout.Msg m = signInEmail(first((String) a.get("name")), code);
        return mailer.send((String) a.get("email"), (String) a.get("name"), m.subject(), m.text(), m.html(), m.preheader());
    }

    /** Make a code for opening this business and email it to the person's own address. */
    public String sendStepUp(UUID adminId, UUID tenantId) {
        String business = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
        String code = tx.execute(st -> make(adminId, STEPUP, tenantId));
        Map<String, Object> a = jdbc.queryForMap("SELECT email, name FROM platform_admins WHERE id = ?", adminId);
        EmailLayout.Msg m = stepUpEmail(first((String) a.get("name")), business, code);
        return mailer.send((String) a.get("email"), (String) a.get("name"), m.subject(), m.text(), m.html(), m.preheader());
    }

    private String make(UUID adminId, String kind, UUID tenantId) {
        Integer recent = jdbc.queryForObject(
                "SELECT count(*) FROM admin_codes WHERE admin_id = ? AND kind = ? AND created_at > now() - interval '1 hour'", Integer.class, adminId, kind);
        if (recent != null && recent >= (STEPUP.equals(kind) ? MAX_STEPUP_PER_HOUR : MAX_SIGNIN_PER_HOUR)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                    "We've sent a few codes to your email already. Check your inbox and spam, or try again in an hour.");
        }
        jdbc.update("UPDATE admin_codes SET used_at = now() WHERE admin_id = ? AND kind = ? AND used_at IS NULL", adminId, kind);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("INSERT INTO admin_codes (admin_id, kind, tenant_id, code_hash, expires_at) VALUES (?, ?, ?, ?, ?)",
                adminId, kind, tenantId, encoder.encode(code), Timestamp.from(Instant.now().plus(TTL)));
        return code;
    }

    /** True when the code is right for this person's sign-in. A right code is spent; 5 wrong ones spend it too. */
    public boolean checkSignIn(UUID adminId, String code) {
        return check(adminId, SIGNIN, null, code);
    }

    /** True when the code is right for this person opening this business. Works once. */
    public boolean checkStepUp(UUID adminId, UUID tenantId, String code) {
        return check(adminId, STEPUP, tenantId, code);
    }

    private boolean check(UUID adminId, String kind, UUID tenantId, String raw) {
        String code = raw == null ? "" : raw.trim();
        Boolean ok = tx.execute(st -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, code_hash, attempts, tenant_id FROM admin_codes
                    WHERE admin_id = ? AND kind = ? AND used_at IS NULL AND expires_at > now()
                    ORDER BY created_at DESC LIMIT 1 FOR UPDATE""", adminId, kind);
            boolean formatOk = code.matches("\\d{6}");
            if (rows.isEmpty()) {
                encoder.matches(code, dummyHash);
                return false;
            }
            Map<String, Object> r = rows.get(0);
            // Exactly one bcrypt check either way.
            boolean right = encoder.matches(code, formatOk ? (String) r.get("code_hash") : dummyHash) && formatOk;
            // A step-up code belongs to one business: the right digits for another business are a wrong code.
            if (right && tenantId != null && !tenantId.equals(r.get("tenant_id"))) {
                right = false;
            }
            if (right) {
                jdbc.update("UPDATE admin_codes SET used_at = now() WHERE id = ?", r.get("id"));
                return true;
            }
            int attempts = ((Number) r.get("attempts")).intValue() + 1;
            jdbc.update("UPDATE admin_codes SET attempts = ?, used_at = CASE WHEN ? >= ? THEN now() ELSE used_at END WHERE id = ?",
                    attempts, attempts, MAX_ATTEMPTS, r.get("id"));
            return false;
        });
        return Boolean.TRUE.equals(ok);
    }

    public static EmailLayout.Msg signInEmail(String name, String code) {
        String text = "Hi " + name + ",\n\nSomeone just signed in to the Busynez control panel with your password. Your code is:\n\n"
                + code + "\n\nWorks once, for 10 minutes. Type it on the sign-in page.\n\n"
                + "Wasn't you? Don't type the code, and change your password.\n\n"
                + "Busynez will never phone, text or WhatsApp you to ask for this code. Don't share it with anyone.\n\nBusynez";
        String html = EmailLayout.h("Your control panel sign-in code", "#101415")
                + EmailLayout.p("Hi " + Mailer.esc(name) + ",")
                + EmailLayout.p("Someone just signed in to the Busynez control panel with your password. Type this code on the sign-in page to finish.")
                + EmailLayout.code(code, "Works once, for 10 minutes")
                + EmailLayout.p("Wasn't you? Don't type the code, and change your password.")
                + EmailLayout.safety("Busynez will never phone, text or WhatsApp you to ask for this code. Don't share it with anyone.");
        return new EmailLayout.Msg("Your Busynez control panel sign-in code", text, html, "Use it on the sign-in page. It works once, for 10 minutes.");
    }

    public static EmailLayout.Msg stepUpEmail(String name, String business, String code) {
        String text = "Hi " + name + ",\n\nYou asked to open the business \"" + business + "\" from the Busynez control panel. Your code is:\n\n"
                + code + "\n\nWorks once, for 10 minutes, for that business only.\n\n"
                + "Wasn't you? Don't type the code, and change your password.\n\n"
                + "Busynez will never phone, text or WhatsApp you to ask for this code. Don't share it with anyone.\n\nBusynez";
        String html = EmailLayout.h("Your code to open a business", "#101415")
                + EmailLayout.p("Hi " + Mailer.esc(name) + ",")
                + EmailLayout.p("You asked to open the business <strong>" + Mailer.esc(business) + "</strong> from the control panel. Type this code to go in.")
                + EmailLayout.code(code, "Works once, for 10 minutes, for that business only")
                + EmailLayout.p("Wasn't you? Don't type the code, and change your password.")
                + EmailLayout.safety("Busynez will never phone, text or WhatsApp you to ask for this code. Don't share it with anyone.");
        return new EmailLayout.Msg("Your Busynez control panel code to open a business", text, html, "Use it in the control panel. It works once, for 10 minutes.");
    }

    private static String first(String name) {
        return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
    }
}
