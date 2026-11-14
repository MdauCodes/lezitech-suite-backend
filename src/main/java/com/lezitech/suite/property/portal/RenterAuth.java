package com.lezitech.suite.property.portal;

import com.lezitech.suite.auth.JwtService;
import com.lezitech.suite.auth.LoginThrottle;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.notify.Mailer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.lezitech.suite.notify.Mailer.esc;

/**
 * Tenants' sign-in to their landlord's portal (owner's decisions, 2026-11-13):
 * <ul>
 *   <li>The landlord invites a tenant (an email with a 6-digit code), or the tenant enters their email on the portal and,
 *       if it's on an active lease there, gets a code.</li>
 *   <li>The first sign-in is the code, then they set a password. After that: email and password. A forgotten password is
 *       the same code route again. The landlord can send a new code at any time.</li>
 * </ul>
 * Accounts are per business. Nothing here says whether an email is known (no account fishing).
 */
@Service
public class RenterAuth {

    static final Duration INVITE_TTL = Duration.ofDays(3);
    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final int MAX_CODES_PER_HOUR = 4;
    private static final int MAX_TRIES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Mailer mailer;
    private final JwtService jwt;
    private final LoginThrottle throttle;

    public RenterAuth(JdbcTemplate jdbc, PasswordEncoder encoder, Mailer mailer, JwtService jwt, LoginThrottle throttle) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mailer = mailer;
        this.jwt = jwt;
        this.throttle = throttle;
    }

    public Map<String, Object> business(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, slug, name, phone, brand_color, logo_url, status, sector FROM tenants WHERE slug = ?""", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status")) || !"property".equals(rows.get(0).get("sector"))) {
            throw ApiException.notFound("Tenant portal");
        }
        return rows.get(0);
    }

    static String clean(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /**
     * The landlord invites the tenant on a lease (or sends them a new code). Needs the tenant's email on the lease.
     * Runs inside the landlord's transaction (customers are fenced by row-level security).
     */
    @Transactional
    public Map<String, Object> invite(UUID tenant, UUID customerId, String email, String name) {
        String e = clean(email);
        if (e.isEmpty() || !e.contains("@")) {
            throw ApiException.bad("Add the tenant's email first: the invitation and their code go there");
        }
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id, customer_id FROM renter_accounts WHERE tenant_id = ? AND email = ?", tenant, e);
        UUID account;
        if (existing.isEmpty()) {
            account = jdbc.queryForObject("""
                    INSERT INTO renter_accounts (tenant_id, customer_id, email, name, invited_at) VALUES (?, ?, ?, ?, now()) RETURNING id""",
                    UUID.class, tenant, customerId, e, name);
        } else {
            account = (UUID) existing.get(0).get("id");
            if (!customerId.equals(existing.get(0).get("customer_id"))) {
                throw ApiException.conflict("That email already signs in for another tenant here. Use a different email for this tenant.");
            }
            jdbc.update("UPDATE renter_accounts SET invited_at = now(), active = true WHERE id = ?", account);
        }
        String code = issue(account, "INVITE", INVITE_TTL);
        Map<String, Object> biz = jdbc.queryForMap("SELECT name, slug, brand_color, phone FROM tenants WHERE id = ?", tenant);
        String link = mailer.appUrl() + "/t/" + biz.get("slug");
        String business = (String) biz.get("name");
        String first = first(name);
        String text = "Hi " + first + ",\n\n" + business + " has opened your tenant page. There you can pay rent by M-Pesa, see your statement, "
                + "report a problem and read notices.\n\nOpen " + link + " , choose \"First time here\", enter this email and the code " + code
                + ", then choose a password.\nThe code works for 3 days.\n\n" + business;
        String html = branded((String) biz.get("brand_color"), business,
                "<p>Hi " + esc(first) + ",</p><p><b>" + esc(business) + "</b> has opened your tenant page. There you can pay rent by M-Pesa, "
                        + "see your statement, report a problem and read notices.</p><p>Your code:</p>"
                        + "<p style=\"font-size:28px;font-weight:bold;letter-spacing:6px;margin:8px 0\">" + code + "</p>"
                        + button((String) biz.get("brand_color"), link, "Open your tenant page")
                        + "<p style=\"color:#6b7280\">Choose “First time here”, enter this email and the code, then choose a password. The code works for 3 days.</p>");
        String err = mailer.trySend(e, name, "Your tenant page at " + business, text, html);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invited", err == null);
        out.put("email", e);
        out.put("problem", err);
        return out;
    }

    /** The tenant asks for a code (first time, or forgot their password). Says nothing about whether the email is known. */
    @Transactional
    public void requestCode(String slug, String email) {
        Map<String, Object> biz = business(slug);
        UUID tenant = (UUID) biz.get("id");
        String e = clean(email);
        if (e.isEmpty()) {
            return;
        }
        List<Map<String, Object>> acc = jdbc.queryForList("SELECT id, name, active FROM renter_accounts WHERE tenant_id = ? AND email = ?", tenant, e);
        UUID account;
        String name;
        if (acc.isEmpty()) {
            // Not invited yet: an email on an active lease here may start an account. Read as the business (fenced tables).
            List<Map<String, Object>> c = asBusiness(tenant, () -> jdbc.queryForList("""
                    SELECT c.id, c.name FROM customers c WHERE c.tenant_id = ? AND lower(c.email) = ?
                      AND EXISTS (SELECT 1 FROM leases l WHERE l.customer_id = c.id AND l.status = 'ACTIVE') LIMIT 2""", tenant, e));
            if (c.size() != 1) {
                return;
            }
            name = (String) c.get(0).get("name");
            account = jdbc.queryForObject("INSERT INTO renter_accounts (tenant_id, customer_id, email, name) VALUES (?, ?, ?, ?) RETURNING id",
                    UUID.class, tenant, c.get(0).get("id"), e, name);
        } else {
            if (!Boolean.TRUE.equals(acc.get(0).get("active"))) {
                return;
            }
            account = (UUID) acc.get(0).get("id");
            name = (String) acc.get(0).get("name");
        }
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM renter_codes WHERE account_id = ? AND created_at > now() - interval '1 hour'",
                Integer.class, account);
        if (recent != null && recent >= MAX_CODES_PER_HOUR) {
            return;
        }
        String code = issue(account, "ACCESS", ACCESS_TTL);
        String business = (String) biz.get("name");
        mailer.trySend(e, name, code + " is your code for " + business,
                "Hi " + first(name) + ",\n\nYour code for your tenant page at " + business + " is " + code + ".\nIt works for 15 minutes.\n\n"
                        + "If you didn't ask for it, ignore this email.\n\n" + business,
                branded((String) biz.get("brand_color"), business, "<p>Hi " + esc(first(name)) + ",</p><p>Your code for your tenant page is:</p>"
                        + "<p style=\"font-size:28px;font-weight:bold;letter-spacing:6px;margin:8px 0\">" + code + "</p>"
                        + "<p style=\"color:#6b7280\">It works for 15 minutes. If you didn't ask for it, ignore this email.</p>"));
    }

    /** Code from the email plus a new password: the account is confirmed, older sign-ins end, and the tenant is signed in. */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> setPassword(String slug, String email, String codeRaw, String password, HttpServletRequest req) {
        Map<String, Object> biz = business(slug);
        UUID tenant = (UUID) biz.get("id");
        String e = clean(email);
        String key = "renter:" + tenant + ":" + e;
        throttle.check(key, req);
        if (password == null || password.length() < 8) {
            throw ApiException.bad("Choose a password of at least 8 characters");
        }
        String code = codeRaw == null ? "" : codeRaw.replaceAll("\\s", "");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.account_id, c.code_hash, c.attempts, c.expires_at FROM renter_codes c JOIN renter_accounts a ON a.id = c.account_id
                WHERE a.tenant_id = ? AND a.email = ? AND a.active AND c.used_at IS NULL ORDER BY c.created_at DESC LIMIT 1 FOR UPDATE OF c""", tenant, e);
        ApiException wrong = new ApiException(HttpStatus.BAD_REQUEST, "BAD_CODE", "That code is wrong or has expired. Ask for a new one.");
        if (rows.isEmpty()) {
            throttle.failed(key, req);
            throw wrong;
        }
        Map<String, Object> c = rows.get(0);
        UUID id = (UUID) c.get("id");
        if (((Timestamp) c.get("expires_at")).toInstant().isBefore(Instant.now()) || (Integer) c.get("attempts") >= MAX_TRIES) {
            jdbc.update("UPDATE renter_codes SET used_at = now() WHERE id = ?", id);
            throw wrong;
        }
        if (!code.matches("[0-9]{6}") || !encoder.matches(code, (String) c.get("code_hash"))) {
            jdbc.update("UPDATE renter_codes SET attempts = attempts + 1 WHERE id = ?", id);
            throttle.failed(key, req);
            throw wrong;
        }
        jdbc.update("UPDATE renter_codes SET used_at = now() WHERE id = ?", id);
        UUID account = (UUID) c.get("account_id");
        jdbc.update("""
                UPDATE renter_accounts SET password_hash = ?, confirmed_at = coalesce(confirmed_at, now()), token_version = token_version + 1,
                       last_login_at = now() WHERE id = ?""", encoder.encode(password), account);
        throttle.succeeded(key);
        return session(account, biz);
    }

    public Map<String, Object> login(String slug, String email, String password, HttpServletRequest req) {
        Map<String, Object> biz = business(slug);
        UUID tenant = (UUID) biz.get("id");
        String e = clean(email);
        String key = "renter:" + tenant + ":" + e;
        throttle.check(key, req);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash FROM renter_accounts WHERE tenant_id = ? AND email = ? AND active", tenant, e);
        if (rows.isEmpty() || rows.get(0).get("password_hash") == null
                || !encoder.matches(password == null ? "" : password, (String) rows.get(0).get("password_hash"))) {
            throttle.failed(key, req);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_LOGIN",
                    "Wrong email or password. First time here, or forgot your password? Ask for a code instead.");
        }
        throttle.succeeded(key);
        UUID account = (UUID) rows.get(0).get("id");
        jdbc.update("UPDATE renter_accounts SET last_login_at = now() WHERE id = ?", account);
        return session(account, biz);
    }

    private Map<String, Object> session(UUID account, Map<String, Object> biz) {
        Map<String, Object> a = jdbc.queryForMap("SELECT name, email, token_version FROM renter_accounts WHERE id = ?", account);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwt.issueRenter(account, (UUID) biz.get("id"), (String) a.get("name"), (Integer) a.get("token_version")));
        out.put("name", a.get("name"));
        out.put("email", a.get("email"));
        return out;
    }

    private String issue(UUID account, String purpose, Duration ttl) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("UPDATE renter_codes SET used_at = now() WHERE account_id = ? AND used_at IS NULL", account);
        jdbc.update("INSERT INTO renter_codes (account_id, purpose, code_hash, expires_at) VALUES (?, ?, ?, ?)",
                account, purpose, encoder.encode(code), Timestamp.from(Instant.now().plus(ttl)));
        return code;
    }

    /** Read fenced tables as the business (sets the tenant for row-level security on this connection's transaction). */
    private <T> T asBusiness(UUID tenant, java.util.function.Supplier<T> work) {
        jdbc.queryForList("SELECT set_config('app.tenant', ?, true)", tenant.toString());
        return work.get();
    }

    static String first(String name) {
        return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
    }

    public static String branded(String color, String business, String body) {
        String brand = color == null ? "#101415" : color;
        return "<div style=\"background:#f3f4ef;padding:24px 12px;font-family:Arial,Helvetica,sans-serif;color:#101415\">"
                + "<div style=\"max-width:560px;margin:0 auto;background:#fff;border-radius:16px;overflow:hidden;border:1px solid #e4e6df\">"
                + "<div style=\"height:6px;background:" + esc(brand) + "\"></div><div style=\"padding:22px 24px;font-size:15px;line-height:1.55\">"
                + "<div style=\"font-size:18px;font-weight:bold;margin-bottom:14px\">" + esc(business) + "</div>" + body + "</div></div></div>";
    }

    public static String button(String color, String link, String label) {
        return "<p style=\"margin:20px 0\"><a href=\"" + esc(link) + "\" style=\"background:" + esc(color == null ? "#101415" : color)
                + ";color:#fff;text-decoration:none;padding:12px 20px;border-radius:10px;font-weight:bold;display:inline-block\">" + esc(label) + "</a></p>";
    }
}
