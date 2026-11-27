package com.lezitech.suite.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.auth.AuthController;
import com.lezitech.suite.auth.JwtService;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.demo.DemoService;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.settings.SettingsController;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * LeziTech's own control panel: watch every workspace, run trials and billing, help customers
 * (including signing in as them), switch sectors and modules, manage demos and the LeziTech team.
 * Every change is written to the platform audit log.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Set<String> ADMIN_ROLES = Set.of("SUPERADMIN", "ADMIN", "SUPPORT", "SALES", "DEVELOPER");

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final com.lezitech.suite.auth.LoginThrottle throttle;
    private final com.lezitech.suite.auth.TokenVersions versions;
    private final AdminService admin;
    @org.springframework.beans.factory.annotation.Autowired
    private AdminInvites invites;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlatformSettings platform;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlanFeatures planFeatures;

    /** Free days for new businesses, and the plans' names, prices and limits. */
    @GetMapping("/platform-settings")
    public Map<String, Object> platformSettings() {
        AdminContext.require("read");
        Map<String, String> kindNames = new java.util.LinkedHashMap<>();
        platform.kindPlans().keySet().forEach(k -> {
            var sub = sectors.findSub(k.substring(0, k.indexOf('/')), k.substring(k.indexOf('/') + 1));
            kindNames.put(k, sub == null ? k : sub.get("name").asText());
        });
        return Map.of("trialDays", platform.trialDays(), "plans", platform.plans().stream().map(platform::withPriceNow).toList(), "planOffers", platform.planOffers(), "tools", planFeatures.describe(),
                "kindPlans", platform.kindPlans(), "kindNames", kindNames,
                "minTrial", com.lezitech.suite.platform.PlatformSettings.MIN_TRIAL, "maxTrial", com.lezitech.suite.platform.PlatformSettings.MAX_TRIAL);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.Offers offers;

    /** Offers to businesses: percent off for some months, a set price for a plan, or extra free days. */
    @GetMapping("/offers")
    public List<Map<String, Object>> offersList() {
        AdminContext.require("read");
        return offers.all();
    }

    @PostMapping("/offers")
    public Map<String, Object> makeOffer(@RequestBody Map<String, Object> raw) {
        AdminContext.require("billing");
        Map<String, Object> o = offers.create(raw, AdminContext.admin().name());
        int told = actions.offerMade(o, AdminContext.admin().name());
        admin.audit("offer.create", null, Map.of("title", o.get("title"), "kind", o.get("kind"), "reached", o.get("reached"), "told", told, "reason", String.valueOf(raw.getOrDefault("reason", ""))));
        Map<String, Object> out = new java.util.LinkedHashMap<>(o);
        out.put("told", told);
        return out;
    }

    @PostMapping("/offers/{id}/stop")
    public Map<String, Object> stopOffer(@PathVariable UUID id) {
        AdminContext.require("billing");
        offers.stop(id);
        admin.audit("offer.stop", null, Map.of("id", id.toString()));
        return Map.of("ok", true);
    }

    @PutMapping("/platform-settings")
    public Map<String, Object> savePlatformSettings(@RequestBody Map<String, Object> raw) {
        AdminContext.require("system");
        if (raw.get("plans") instanceof List<?> || raw.get("planOffers") instanceof Map<?, ?> || raw.get("kindPlans") instanceof Map<?, ?>) {
            AdminContext.require("billing"); // what plans cost is a billing matter: the developer role cannot change it
        }
        String by = AdminContext.admin().name();
        Map<String, Long> priceBefore = actions.effectivePrices();
        Map<String, Long> kindPriceBefore = platform.kindEffectivePrices();
        if (raw.containsKey("trialDays")) {
            int before = platform.trialDays();
            int days;
            try {
                days = Integer.parseInt(String.valueOf(raw.get("trialDays")).trim());
            } catch (NumberFormatException e) {
                throw ApiException.bad("Type the number of free days");
            }
            platform.setTrialDays(days, by);
            admin.audit("platform.trial_days", null, Map.of("from", before, "to", days));
        }
        if (raw.get("plans") instanceof List<?> plans) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> p = (List<Map<String, Object>>) plans;
            platform.setPlans(p, by);
            admin.audit("platform.plans", null, Map.of("plans", p.toString()));
        }
        if (raw.get("planOffers") instanceof Map<?, ?> po) {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> o = (Map<String, Map<String, Object>>) po;
            Map<String, Map<String, Object>> before = platform.planOffers();
            Map<String, Map<String, Object>> saved = platform.setPlanOffers(o, by);
            if (!saved.equals(before)) {
                admin.audit("platform.plan_offers", null, Map.of("from", before.toString(), "to", saved.toString()));
            }
        }
        if (raw.get("kindPlans") instanceof Map<?, ?> kp) {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> k = (Map<String, Map<String, Object>>) kp;
            Map<String, Map<String, Object>> before = platform.kindPlans();
            Map<String, Map<String, Object>> saved = platform.setKindPlans(k, by);
            if (!saved.equals(before)) {
                admin.audit("platform.kind_plans", null, Map.of("from", before.toString(), "to", saved.toString()));
            }
        }
        if (raw.get("planTools") instanceof Map<?, ?> tools) {
            @SuppressWarnings("unchecked")
            Map<String, List<String>> t = (Map<String, List<String>>) tools;
            platform.setPlanFeatures(t, by);
            admin.audit("platform.plan_tools", null, Map.of("tools", t.toString()));
        }
        int told = 0;
        if (raw.get("plans") instanceof List<?> || raw.get("planOffers") instanceof Map<?, ?> || raw.get("kindPlans") instanceof Map<?, ?>) {
            told = actions.priceChanged(priceBefore, by) + actions.kindPriceChanged(kindPriceBefore, by);
            if (told > 0) {
                admin.audit("platform.price_notices", null, Map.of("told", told, "reason", String.valueOf(raw.getOrDefault("reason", ""))));
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(platformSettings());
        out.put("told", told);
        return out;
    }
    @org.springframework.beans.factory.annotation.Autowired
    private OversightActions actions;
    @org.springframework.beans.factory.annotation.Autowired
    private Oversight oversight;
    @org.springframework.beans.factory.annotation.Autowired
    private AdminCodes codes;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.auth.LoginCodes loginCodes;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.notify.Mailer mailer;

    /** LeziTech sends anyone in a business a code and link to set their password (lost access, new staff). */
    @PostMapping("/tenants/{id}/users/{userId}/code")
    public Map<String, Object> sendUserCode(@PathVariable UUID id, @PathVariable UUID userId) {
        AdminContext.require("setup");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.name, u.email, u.job_title, u.active, t.name AS business, t.slug FROM users u JOIN tenants t ON t.id = u.tenant_id
                WHERE u.id = ? AND u.tenant_id = ?""", userId, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Person");
        }
        Map<String, Object> u = rows.get(0);
        String email = (String) u.get("email");
        if (email == null || email.endsWith(".suite.local")) {
            throw ApiException.bad("This person has no email. The owner can add it under Staff");
        }
        if (!Boolean.TRUE.equals(u.get("active"))) {
            throw ApiException.bad("This person can't sign in (switched off by the business)");
        }
        String status = loginCodes.sendInvite(userId, email, (String) u.get("name"), (String) u.get("business"), (String) u.get("slug"),
                (String) u.get("job_title"), "LeziTech", mailer.appUrl());
        admin.audit("workspace.user_code", id, Map.of("user", userId.toString(), "email", status));
        return Map.of("ok", true, "emailStatus", status, "message", "Code and link sent to " + email);
    }
    private final AdminService.UsageReader usage;
    private final AuthController auth;
    private final DemoService demos;
    private final SectorCatalog sectors;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.settings.PlanActivation activation;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.settings.PlanBilling billing;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.auth.SupportAccess supportAccess;

    public AdminController(JdbcTemplate jdbc, JwtService jwt, PasswordEncoder encoder, AdminService admin,
                           AdminService.UsageReader usage, AuthController auth, DemoService demos, SectorCatalog sectors, ObjectMapper json,
                           com.lezitech.suite.auth.LoginThrottle throttle, com.lezitech.suite.auth.TokenVersions versions) {
        this.jdbc = jdbc;
        this.throttle = throttle;
        this.versions = versions;
        this.jwt = jwt;
        this.encoder = encoder;
        this.admin = admin;
        this.usage = usage;
        this.auth = auth;
        this.demos = demos;
        this.sectors = sectors;
        this.json = json;
    }

    // ---------- sign-in ----------

    @GetMapping("/auth/options")
    public Map<String, Object> options() {
        List<Map<String, Object>> roles = new ArrayList<>();
        if (admin.demoLogins()) {
            AdminService.DEMO_STAFF.forEach(s -> roles.add(Map.of("role", s[0], "label", s[1])));
        }
        return Map.of("demoLogins", admin.demoLogins(), "roles", roles);
    }

    /**
     * Step one of two: the password. A right password sends a 6-digit code to the person's own address; the token comes
     * only after the code (see {@link #code}). Unknown, switched-off, blocked and not-yet-accepted accounts are
     * refused exactly like a wrong password, and one bcrypt check always runs, so nothing tells them apart.
     */
    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> raw, jakarta.servlet.http.HttpServletRequest req) {
        Body b = new Body(raw);
        String email = b.str("email").trim().toLowerCase();
        // Counted by address only: a stranger who knows an email must not be able to lock that person out.
        throttle.checkAddress(req);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, role, password_hash, active, invite_pending, blocked_at FROM platform_admins WHERE email = ?", email);
        boolean found = !rows.isEmpty() && rows.get(0).get("password_hash") != null;
        String hash = found ? (String) rows.get(0).get("password_hash") : codes.dummyHash();
        boolean passwordRight = encoder.matches(b.str("password"), hash);
        boolean usable = found && Boolean.TRUE.equals(rows.get(0).get("active"))
                && !Boolean.TRUE.equals(rows.get(0).get("invite_pending")) && rows.get(0).get("blocked_at") == null;
        if (!passwordRight || !usable) {
            throttle.failedAddress(req);
            auditFailure("auth.signin_failed", null, email, req, !found ? "no such account" : !usable ? "account not allowed in" : "wrong password");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_LOGIN", "Wrong email or password");
        }
        String sent = codes.sendSignIn((UUID) rows.get(0).get("id"));
        if (!"SENT".equals(sent)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CODE_NOT_SENT", CODE_NOT_SENT_MESSAGE);
        }
        return Map.of("step", "code", "sentTo", mask(email));
    }

    /** Step two of two: the emailed code. Right code, token. Every failure answers the same. */
    @PostMapping("/auth/code")
    public Map<String, Object> code(@RequestBody Map<String, Object> raw, jakarta.servlet.http.HttpServletRequest req) {
        Body b = new Body(raw);
        String email = b.str("email").trim().toLowerCase();
        // By address only, and checked before the code is touched: a refused try never spends the live code.
        throttle.checkAddress(req);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, role, active, invite_pending, blocked_at FROM platform_admins WHERE email = ?", email);
        boolean usable = !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("active"))
                && !Boolean.TRUE.equals(rows.get(0).get("invite_pending")) && rows.get(0).get("blocked_at") == null;
        boolean ok = usable ? codes.checkSignIn((UUID) rows.get(0).get("id"), b.str("code", "")) : codes.burn(b.str("code", ""));
        if (!ok || !usable) {
            throttle.failedAddress(req);
            auditFailure("auth.code_failed", null, email, req, !usable ? "account not allowed in" : "wrong, spent or expired code");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CODE_WRONG", "That code is not right or has expired. Sign in again to get a new code.");
        }
        return adminSession(rows.get(0), req, "password and emailed code");
    }

    static final String CODE_NOT_SENT_MESSAGE =
            "We couldn't send your code. Try again in a minute. If it keeps failing, contact the person who runs the server.";

    /** A failed sign-in or step-up goes in the platform audit: who was tried, from where and why. Never the code or password. */
    private void auditFailure(String action, UUID tenant, String email, jakarta.servlet.http.HttpServletRequest req, String reason) {
        try {
            UUID adminId = jdbc.query("SELECT id FROM platform_admins WHERE email = ?", rs -> rs.next() ? (UUID) rs.getObject(1) : null, email);
            jdbc.update("INSERT INTO platform_audit (admin_id, admin_name, action, tenant_id, detail) VALUES (?, ?, ?, ?, cast(? as jsonb))",
                    adminId, null, action, tenant, json.writeValueAsString(Map.of("email", email, "ip", com.lezitech.suite.common.ClientIp.of(req), "reason", reason)));
        } catch (Exception e) {
            // An audit failure must not change the answer.
        }
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at < 2) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    @PostMapping("/auth/demo")
    public Map<String, Object> demoLogin(@RequestBody Map<String, Object> raw, jakarta.servlet.http.HttpServletRequest req) {
        if (!admin.demoLogins()) {
            throw ApiException.forbidden("Demo control-panel logins are only available on local runs");
        }
        String role = new Body(raw).str("role").toUpperCase();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, role FROM platform_admins WHERE email = ? AND active", role.toLowerCase() + "@lezitech.test");
        if (rows.isEmpty()) {
            throw ApiException.notFound("Demo staff account");
        }
        return adminSession(rows.get(0), req, "demo login");
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me() {
        AdminContext.Admin a = AdminContext.admin();
        Map<String, Object> row = jdbc.queryForMap("SELECT id, email, name, role FROM platform_admins WHERE id = ?", a.id());
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("id", row.get("id").toString());
        out.put("permissions", AdminContext.permissions(a.role()));
        return out;
    }

    /** Signed in: remember when, from where and on what kind of device, in the platform audit and on the person's row. */
    private Map<String, Object> adminSession(Map<String, Object> row, jakarta.servlet.http.HttpServletRequest req, String how) {
        UUID id = (UUID) row.get("id");
        String ip = com.lezitech.suite.common.ClientIp.of(req);
        String device = deviceType(req.getHeader("User-Agent"));
        jdbc.update("UPDATE platform_admins SET last_login_at = now(), last_seen_at = now(), last_login_ip = ?, last_login_device = ? WHERE id = ?", ip, device, id);
        try {
            jdbc.update("INSERT INTO platform_audit (admin_id, admin_name, action, detail) VALUES (?, ?, 'auth.signin', cast(? as jsonb))",
                    id, row.get("name"), json.writeValueAsString(Map.of("ip", ip, "device", device, "how", how)));
        } catch (Exception e) {
            // A failed audit write must not lock the person out.
        }
        String role = (String) row.get("role");
        return Map.of("token", jwt.issueAdmin(id, role, (String) row.get("name"), versions.current(id, true)),
                "admin", Map.of("id", id.toString(), "name", row.get("name"), "role", role, "permissions", AdminContext.permissions(role)));
    }

    static String deviceType(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.toLowerCase();
        if (ua.isBlank()) {
            return "Unknown";
        }
        if (ua.contains("ipad") || ua.contains("tablet")) {
            return "Tablet";
        }
        if (ua.contains("mobi") || ua.contains("iphone") || ua.contains("android")) {
            return "Phone";
        }
        return "Computer";
    }

    // ---------- overview ----------

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        AdminContext.require("read");
        Map<String, Object> out = new LinkedHashMap<>();
        String real = " FROM tenants WHERE NOT is_demo ";
        // One pass over the workspaces for every headline number.
        Map<String, Object> c = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE NOT is_demo) AS workspaces,
                       count(*) FILTER (WHERE NOT is_demo AND status = 'TRIAL' AND trial_ends_at > now()) AS on_trial,
                       count(*) FILTER (WHERE NOT is_demo AND status = 'TRIAL' AND trial_ends_at <= now()) AS trial_expired,
                       count(*) FILTER (WHERE NOT is_demo AND status = 'ACTIVE' AND (paid_until IS NULL OR paid_until > now())) AS paying,
                       count(*) FILTER (WHERE NOT is_demo AND status = 'SUSPENDED') AS suspended,
                       count(*) FILTER (WHERE NOT is_demo AND status = 'TRIAL' AND trial_ends_at BETWEEN now() AND now() + interval '48 hours') AS ending48,
                       count(*) FILTER (WHERE NOT is_demo AND last_active_at > now() - interval '24 hours') AS active_today,
                       count(*) FILTER (WHERE NOT is_demo AND created_at > now() - interval '7 days') AS signups7,
                       count(*) FILTER (WHERE is_demo) AS demos,
                       (SELECT count(*) FROM subscription_requests WHERE status = 'PENDING') AS pending
                FROM tenants""");
        out.put("workspaces", c.get("workspaces"));
        out.put("onTrial", c.get("on_trial"));
        out.put("trialExpired", c.get("trial_expired"));
        out.put("paying", c.get("paying"));
        out.put("suspended", c.get("suspended"));
        out.put("endingIn48h", c.get("ending48"));
        out.put("activeToday", c.get("active_today"));
        out.put("signups7d", c.get("signups7"));
        out.put("pendingRequests", c.get("pending"));
        out.put("demoWorkspaces", c.get("demos"));
        // Monthly recurring revenue from paying workspaces at list price.
        long mrr = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT plan, sector, sub_sector, count(*) AS n" + real + "AND status = 'ACTIVE' AND (paid_until IS NULL OR paid_until > now()) GROUP BY plan, sector, sub_sector")) {
            Map<String, Object> own = platform.kindPlan((String) r.get("sector"), (String) r.get("sub_sector")); // a kind of business has a price of its own
            mrr += platform.plans().stream().filter(p -> p.get("code").equals(r.get("plan")))
                    .mapToLong(p -> ((Number) (own != null ? own : p).get("monthly")).longValue() * ((Number) r.get("n")).longValue()).sum();
        }
        out.put("mrr", mrr);
        out.put("signupsByDay", jdbc.queryForList("""
                SELECT to_char(d, 'MM-DD') AS day, count(t.id) AS signups
                FROM generate_series(current_date - 29, current_date, interval '1 day') d
                LEFT JOIN tenants t ON t.created_at::date = d::date AND NOT t.is_demo
                GROUP BY d ORDER BY d"""));
        out.put("bySector", jdbc.queryForList("""
                SELECT sector, count(*) AS total, count(*) FILTER (WHERE status = 'ACTIVE') AS paying,
                       count(*) FILTER (WHERE status = 'TRIAL' AND trial_ends_at > now()) AS trial
                FROM tenants WHERE NOT is_demo GROUP BY sector ORDER BY total DESC"""));
        out.put("endingSoon", jdbc.queryForList("""
                SELECT t.id, t.name, t.sector, t.trial_ends_at, t.last_active_at, t.phone,
                       (SELECT email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' ORDER BY created_at LIMIT 1) AS owner_email
                FROM tenants t WHERE NOT is_demo AND status = 'TRIAL' AND trial_ends_at BETWEEN now() - interval '3 days' AND now() + interval '3 days'
                ORDER BY trial_ends_at LIMIT 20"""));
        out.put("recentAudit", jdbc.queryForList("""
                SELECT a.action, a.admin_name, a.created_at, t.name AS tenant_name FROM platform_audit a
                LEFT JOIN tenants t ON t.id = a.tenant_id ORDER BY a.created_at DESC LIMIT 10"""));
        return out;
    }

    // ---------- workspaces ----------

    @GetMapping("/tenants")
    public Object tenants(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                          @RequestParam(required = false) String sector, @RequestParam(defaultValue = "false") boolean demo,
                          @RequestParam(required = false) Integer limit, @RequestParam(required = false) String before) {
        AdminContext.require("read");
        String st = status == null || status.isBlank() ? null : status;
        String term = q == null ? null : q.trim();
        String pattern = term == null || term.length() < 2 ? null
                : "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        boolean paged = limit != null;
        int size = paged ? Math.max(1, Math.min(100, limit)) : 500;
        String cursorSql = "";
        List<Object> args = new ArrayList<>();
        args.add(demo);
        args.add(pattern);
        args.add(pattern);
        args.add(pattern);
        args.add(pattern);
        args.add(blank(sector));
        args.add(blank(sector));
        args.add(st);
        args.add(st);
        if (paged && before != null && !before.isBlank()) {
            int tilde = before.indexOf('~');
            try {
                if (tilde < 0) {
                    throw new IllegalArgumentException("no separator");
                }
                args.add(Timestamp.from(Instant.parse(before.substring(0, tilde))));
                args.add(UUID.fromString(before.substring(tilde + 1)));
            } catch (RuntimeException e) {
                throw ApiException.bad("Invalid cursor");
            }
            cursorSql = " AND (x.created_at, x.id) < (cast(? as timestamptz), cast(? as uuid))";
        }
        args.add(paged ? size + 1 : size);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.id, b.slug, b.name, b.sector, b.sub_sector, b.status, b.plan, b.trial_ends_at, b.paid_until, b.created_at,
                       b.last_active_at, b.phone, b.brand_color, b.logo_url, b.is_demo, b.state,
                       u.owner_email, u.users, u.last_login_at, r.pending_request
                FROM (
                    SELECT * FROM (
                        SELECT t.id, t.slug, t.name, t.sector, t.sub_sector, t.status, t.plan, t.trial_ends_at, t.paid_until, t.created_at,
                               t.last_active_at, t.phone, t.brand_color, t.logo_url, t.is_demo,
                               CASE WHEN t.in_setup THEN 'SETUP'
                                    WHEN t.status = 'TRIAL' AND t.trial_ends_at <= now() THEN 'EXPIRED'
                                    WHEN t.status = 'ACTIVE' AND t.paid_until < now() THEN 'LAPSED' ELSE t.status END AS state
                        FROM tenants t
                        WHERE t.is_demo = ?
                          AND (cast(? as text) IS NULL OR t.name ILIKE cast(? as text) ESCAPE '\\' OR t.slug ILIKE cast(? as text) ESCAPE '\\'
                               OR EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = t.id AND u.email ILIKE cast(? as text) ESCAPE '\\'))
                          AND (cast(? as text) IS NULL OR t.sector = cast(? as text))
                    ) x
                    WHERE (cast(? as text) IS NULL OR x.state = cast(? as text))""" + cursorSql + """

                    ORDER BY x.created_at DESC, x.id DESC LIMIT ?
                ) b
                LEFT JOIN LATERAL (
                    SELECT count(*) AS users, max(last_login_at) AS last_login_at,
                           (array_agg(email ORDER BY created_at) FILTER (WHERE role = 'OWNER'))[1] AS owner_email
                    FROM users WHERE tenant_id = b.id) u ON true
                LEFT JOIN LATERAL (
                    SELECT plan || ' ' || billing_cycle AS pending_request FROM subscription_requests
                    WHERE tenant_id = b.id AND status = 'PENDING' ORDER BY created_at DESC LIMIT 1) r ON true
                ORDER BY b.created_at DESC, b.id DESC""", args.toArray());
        if (!paged) {
            return rows.stream().map(this::clean).toList();
        }
        boolean more = rows.size() > size;
        List<Map<String, Object>> page = more ? rows.subList(0, size) : rows;
        String next = null;
        if (more) {
            Map<String, Object> last = page.get(page.size() - 1);
            next = MICROS.format(((Timestamp) last.get("created_at")).toInstant()) + "~" + last.get("id");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", page.stream().map(this::clean).toList());
        out.put("next", next);
        return out;
    }

    private static final java.time.format.DateTimeFormatter MICROS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(java.time.ZoneOffset.UTC);

    @GetMapping("/tenants/{id}")
    public Map<String, Object> tenant(@PathVariable UUID id) throws Exception {
        AdminContext.require("read");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, slug, name, sector, sub_sector, products, status, plan, trial_ends_at, paid_until, created_at, last_active_at,
                       phone, brand_color, logo_url, currency, is_demo, admin_note, display_key, in_setup, handed_over_at, settings::text AS settings,
                       signup_source::text AS signup_source FROM tenants WHERE id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Workspace");
        }
        Map<String, Object> t = clean(rows.get(0));
        t.put("settings", json.readTree((String) rows.get(0).get("settings")));
        t.put("signupSource", rows.get(0).get("signup_source") == null ? null : json.readTree((String) rows.get(0).get("signup_source")));
        t.put("products", List.of((Object[]) ((java.sql.Array) rows.get(0).get("products")).getArray()));
        t.put("users", jdbc.queryForList("""
                SELECT id, name, email, role, job_title, active, last_login_at, created_at FROM users WHERE tenant_id = ? ORDER BY role = 'OWNER' DESC, name""", id)
                .stream().map(this::clean).toList());
        // Whether we may sign in as them now (the owner's grant, or a demo / still being set up).
        t.put("supportAccess", supportAccess.forAdmin(id));
        t.put("requests", jdbc.queryForList("""
                SELECT id, plan, billing_cycle, amount, mpesa_phone, status, created_at, lines::text AS lines FROM subscription_requests WHERE tenant_id = ?
                ORDER BY created_at DESC""", id).stream().map(this::clean).toList());
        t.put("audit", jdbc.queryForList("""
                SELECT action, admin_name, detail::text AS detail, created_at FROM platform_audit WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 50""", id)
                .stream().map(this::clean).toList());
        t.put("usage", TenantContext.runAs(id, null, usage::usage));
        if (t.get("plan") != null) {
            t.put("planName", platform.planName((String) rows.get(0).get("sector"), (String) rows.get(0).get("sub_sector"), (String) t.get("plan")));
        }
        t.put("nextBill", TenantContext.runAs(id, null, () -> billing.nextBill(id, "MONTHLY")));
        t.put("planChanges", billing.changes(id));
        JsonNode pack = sectors.get((String) rows.get(0).get("sector"));
        t.put("packModules", pack.get("modules"));
        t.put("packName", pack.get("name").asText());
        return t;
    }

    @PostMapping("/tenants/{id}/extend-trial")
    public Map<String, Object> extendTrial(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("billing");
        return actions.freeDays(id, raw, AdminContext.admin().name());
    }

    @PostMapping("/tenants/{id}/suspend")
    public Map<String, Object> suspend(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        AdminContext.require("suspend");
        return actions.suspend(id, raw, AdminContext.admin().name());
    }

    @PostMapping("/tenants/{id}/reactivate")
    public Map<String, Object> reactivate(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        AdminContext.require("suspend");
        return actions.unsuspend(id, raw, AdminContext.admin().name());
    }

    @PostMapping("/tenants/{id}/note")
    public Map<String, Object> note(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("notes");
        String note = new Body(raw).str("note", "").trim();
        if (note.isEmpty()) {
            throw ApiException.bad("Type the note first");
        }
        oversight.addNote(id, AdminContext.admin().id(), AdminContext.admin().name(), note);
        admin.audit("workspace.note", id, Map.of("note", note.length() > 200 ? note.substring(0, 200) : note));
        return Map.of("ok", true);
    }

    /** Turn screens and features on or off for one workspace (e.g. give a shop the rooms module). */
    @PostMapping("/tenants/{id}/modules")
    public Map<String, Object> modules(@PathVariable UUID id, @RequestBody Map<String, Object> raw) throws Exception {
        AdminContext.require("modules");
        Body b = new Body(raw);
        Map<String, Object> patch = new LinkedHashMap<>();
        if (raw.get("modules") instanceof List<?> m) {
            patch.put("modules", m);
        }
        if (!b.map("features").isEmpty()) {
            JsonNode current = json.readTree(jdbc.queryForObject("SELECT settings::text FROM tenants WHERE id = ?", String.class, id));
            Map<String, Object> features = new LinkedHashMap<>(json.convertValue(current.path("features"), Map.class));
            features.putAll(b.map("features"));
            patch.put("features", features);
        }
        jdbc.update("UPDATE tenants SET settings = settings || cast(? as jsonb) WHERE id = ?", json.writeValueAsString(patch), id);
        admin.audit("workspace.modules", id, patch);
        return Map.of("ok", true);
    }

    /**
     * Step one of opening a business: a 6-digit code goes to the admin's own address (10 minutes, single use, for that
     * business only). Demo businesses need no code.
     */
    @PostMapping("/tenants/{id}/open-code")
    public Map<String, Object> openCode(@PathVariable UUID id) {
        AdminContext.require("impersonate");
        if (Boolean.TRUE.equals(supportAccess.forAdmin(id).get("exempt"))) {
            return Map.of("needed", false);
        }
        AdminContext.Admin a = AdminContext.admin();
        String sent = codes.sendStepUp(a.id(), id);
        if (!"SENT".equals(sent)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CODE_NOT_SENT", CODE_NOT_SENT_MESSAGE);
        }
        admin.audit("workspace.open_code", id, Map.of());
        String email = jdbc.queryForObject("SELECT email FROM platform_admins WHERE id = ?", String.class, a.id());
        return Map.of("needed", true, "sentTo", mask(email));
    }

    /**
     * Open a business from the control panel, in one of two modes. LOOK needs no grant from the owner: the session is
     * look only (the server refuses every change) and lasts at most 2 hours. HELP can change things and needs the
     * owner's grant (Settings, Let LeziTech help). Both need a short reason and, except for demo businesses, the code
     * from {@link #openCode}. Every opening is in the platform audit with the mode, the business and the reason, and in
     * the business's activity log.
     */
    @PostMapping("/tenants/{id}/impersonate")
    public Map<String, Object> impersonate(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw,
                                           jakarta.servlet.http.HttpServletRequest req) {
        AdminContext.require("impersonate");
        Body b = new Body(raw);
        String role = b.str("role", "OWNER").toUpperCase();
        String mode = b.str("mode", "LOOK").toUpperCase();
        if (!"LOOK".equals(mode) && !"HELP".equals(mode)) {
            throw ApiException.bad("Choose to look only, or to help");
        }
        String reason = b.str("reason", "").trim();
        if (reason.length() > 200) {
            reason = reason.substring(0, 200);
        }
        Map<String, Object> access = supportAccess.forAdmin(id);
        boolean demo = Boolean.TRUE.equals(access.get("exempt"));
        if (!demo && reason.length() < 3) {
            throw ApiException.bad("Say why you are opening this business, in a few words");
        }
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, name, role FROM users WHERE tenant_id = ? AND role = ? AND active ORDER BY created_at LIMIT 1", id, role);
        if (users.isEmpty()) {
            throw ApiException.notFound("Active " + role.toLowerCase() + " in that workspace");
        }
        Map<String, Object> u = users.get(0);
        AdminContext.Admin a = AdminContext.admin();
        if (demo) {
            admin.audit("workspace.impersonate", id, Map.of("as", u.get("name"), "role", role, "mode", mode, "reason", reason, "grant", "not needed (demo)"));
            return auth.session((UUID) u.get("id"), id, role, (String) u.get("name"));
        }
        Optional<Map<String, Object>> grant = "HELP".equals(mode) ? supportAccess.active(id) : Optional.empty();
        if ("HELP".equals(mode) && grant.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NO_SUPPORT_GRANT",
                    "The owner hasn't let LeziTech help yet. Ask the owner for help access, or look only.");
        }
        throttle.check("stepup:" + a.id(), null);
        if (!codes.checkStepUp(a.id(), id, b.str("code", ""))) {
            throttle.failed("stepup:" + a.id(), null);
            admin.audit("workspace.open_code_failed", id, Map.of("ip", com.lezitech.suite.common.ClientIp.of(req), "reason", "wrong, spent or expired code"));
            throw new ApiException(HttpStatus.FORBIDDEN, "STEP_UP_CODE", "That code is not right or has expired. Ask for a new code.");
        }
        throttle.succeeded("stepup:" + a.id());
        String business = (String) jdbc.queryForMap("SELECT name FROM tenants WHERE id = ?", id).get("name");
        if (grant.isPresent()) {
            Map<String, Object> g = grant.get();
            admin.audit("workspace.impersonate", id, Map.of("as", u.get("name"), "role", role, "mode", mode, "business", business, "reason", reason,
                    "grant", g.get("id").toString(), "until", String.valueOf(g.get("expires_at"))));
            supportAccess.logOpening(id, (UUID) u.get("id"), a.name(), mode, reason);
            return auth.supportSession((UUID) u.get("id"), id, role, a.name(), (UUID) g.get("id"),
                    com.lezitech.suite.auth.SupportAccess.expiry(g).toInstant());
        }
        Map<String, Object> look = supportAccess.openLook(id, a.id(), a.name(), reason);
        admin.audit("workspace.impersonate", id, Map.of("as", u.get("name"), "role", role, "mode", mode, "business", business, "reason", reason,
                "until", String.valueOf(look.get("expires"))));
        supportAccess.logOpening(id, (UUID) u.get("id"), a.name(), mode, reason);
        return auth.lookSession((UUID) u.get("id"), id, role, a.name(), (UUID) look.get("id"), (java.time.Instant) look.get("expires"));
    }

    /** No grant yet: ask the owner for help access. The owner sees it in the app and answers Allow or Not now. */
    @PostMapping("/tenants/{id}/ask-help")
    public Map<String, Object> askHelp(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        AdminContext.require("impersonate");
        if (Boolean.TRUE.equals(supportAccess.forAdmin(id).get("exempt"))) {
            throw ApiException.bad("Demo businesses don't need the owner's permission");
        }
        String reason = new Body(raw).str("reason", "").trim();
        if (reason.length() < 3) {
            throw ApiException.bad("Say what you need to help with, in a few words");
        }
        if (reason.length() > 200) {
            reason = reason.substring(0, 200);
        }
        AdminContext.Admin a = AdminContext.admin();
        supportAccess.requestHelp(id, a.id(), a.name(), reason);
        admin.audit("workspace.ask_help", id, Map.of("reason", reason));
        return Map.of("ok", true);
    }

    // ---------- billing queue ----------

    @GetMapping("/requests")
    public List<Map<String, Object>> requests(@RequestParam(defaultValue = "PENDING") String status) {
        AdminContext.require("read");
        return jdbc.queryForList("""
                SELECT r.id, r.tenant_id, t.name AS tenant_name, t.sector, t.sub_sector, r.plan, r.billing_cycle, r.amount, r.mpesa_phone, r.status, r.created_at,
                       r.lines::text AS lines
                FROM subscription_requests r JOIN tenants t ON t.id = r.tenant_id
                WHERE (? = 'ALL' OR r.status = ?) ORDER BY r.created_at DESC LIMIT 200""", status, status).stream().map(r -> withPlanName(r)).map(this::clean).toList();
    }

    @PostMapping("/requests/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        AdminContext.require("billing");
        return actions.approveRequest(id, raw, AdminContext.admin().name());
    }

    @PostMapping("/requests/{id}/reject")
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        AdminContext.require("billing");
        return actions.rejectRequest(id, raw, AdminContext.admin().name());
    }

    // ---------- sectors ----------

    @GetMapping("/sectors")
    public List<Map<String, Object>> sectorList() {
        AdminContext.require("read");
        List<String> disabled = disabledSectors();
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> counts = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT sector, count(*) AS workspaces, count(*) FILTER (WHERE status = 'ACTIVE') AS paying
                FROM tenants WHERE NOT is_demo GROUP BY sector""")) {
            counts.put((String) r.get("sector"), r);
        }
        for (JsonNode p : sectors.all()) {
            String key = p.get("key").asText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("name", p.get("name").asText());
            row.put("icon", p.get("icon").asText());
            row.put("color", p.get("color").asText());
            row.put("modules", p.get("modules"));
            row.put("subSectors", p.get("subSectors"));
            row.put("planNames", platform.planNames(key).entrySet().stream().map(e -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("code", e.getKey());
                n.put("name", e.getValue());
                n.put("default", platform.defaultPlanName(key, e.getKey()));
                return n;
            }).toList());
            row.put("enabled", !disabled.contains(key));
            Map<String, Object> n = counts.getOrDefault(key, Map.of());
            row.put("workspaces", n.getOrDefault("workspaces", 0L));
            row.put("paying", n.getOrDefault("paying", 0L));
            out.add(row);
        }
        return out;
    }

    /** The names a kind of business sees for the Lite, Growth and Pro plans (the codes and prices stay). A blank name goes back to the default. */
    @PutMapping("/sectors/{key}/plan-names")
    public Map<String, Object> savePlanNames(@PathVariable String key, @RequestBody Map<String, Object> raw) {
        AdminContext.require("sectors");
        sectors.get(key);
        Map<String, String> before = platform.planNames(key);
        Map<String, String> after = platform.setSectorPlanNames(key, raw, AdminContext.admin().name());
        if (!after.equals(before)) {
            admin.audit("sector.plan_names", null, Map.of("sector", key, "from", before.toString(), "to", after.toString()));
        }
        return Map.of("sector", key, "names", after);
    }

    /** Hide a sector from signup (e.g. while its pack is being reworked). Existing workspaces keep working. */
    @PostMapping("/sectors/{key}/toggle")
    public Map<String, Object> toggleSector(@PathVariable String key) throws Exception {
        AdminContext.require("sectors");
        sectors.get(key);
        List<String> disabled = new ArrayList<>(disabledSectors());
        boolean nowEnabled = disabled.remove(key);
        if (!nowEnabled) {
            disabled.add(key);
        }
        jdbc.update("""
                INSERT INTO platform_settings (key, value) VALUES ('disabled_sectors', cast(? as jsonb))
                ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = now()""", json.writeValueAsString(disabled));
        admin.audit(nowEnabled ? "sector.enable" : "sector.disable", null, Map.of("sector", key));
        return Map.of("enabled", nowEnabled);
    }

    public List<String> disabledSectors() {
        List<String> rows = jdbc.queryForList("SELECT value::text FROM platform_settings WHERE key = 'disabled_sectors'", String.class);
        if (rows.isEmpty()) {
            return List.of();
        }
        try {
            return List.of(json.readValue(rows.get(0), String[].class));
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------- demos ----------

    @GetMapping("/demos")
    public List<Map<String, Object>> demoList() {
        AdminContext.require("read");
        return demos.list();
    }

    @PostMapping("/demos/{sector}/reset")
    public Map<String, Object> resetDemo(@PathVariable String sector) throws Exception {
        AdminContext.require("demos");
        demos.reset(sector);
        admin.audit("demo.reset", null, Map.of("sector", sector));
        return Map.of("ok", true);
    }

    // ---------- LeziTech team ----------

    @GetMapping("/team")
    public List<Map<String, Object>> team() {
        AdminContext.require("read");
        return jdbc.queryForList("""
                SELECT id, email, name, role, active, last_login_at, last_login_device, created_at, invite_pending, blocked_at,
                       (SELECT max(i.created_at) FROM admin_invites i WHERE i.admin_id = platform_admins.id AND i.kind = 'invite') AS invite_sent_at
                FROM platform_admins ORDER BY role, name""")
                .stream().map(this::clean).toList();
    }

    /**
     * Adds a colleague with no password: the row is switched off with a password nobody knows, and they are emailed a
     * one-off link to choose their own (AdminInvites). Until they do, they cannot sign in.
     */
    @PostMapping("/team")
    public Map<String, Object> addAdmin(@RequestBody Map<String, Object> raw) {
        AdminContext.require("team");
        Body b = new Body(raw);
        String role = b.str("role", "SUPPORT").toUpperCase();
        if (!ADMIN_ROLES.contains(role)) {
            throw ApiException.bad("Unknown role");
        }
        String email = b.str("email").trim().toLowerCase();
        String name = b.str("name").trim();
        if (name.isEmpty() || !email.matches("[^@\\s]+@[^@\\s]+[.][^@\\s]+")) {
            throw ApiException.bad("Type their name and a real email address");
        }
        Integer same = jdbc.queryForObject("SELECT count(*) FROM platform_admins WHERE email = ?", Integer.class, email);
        if (same != null && same > 0) {
            throw ApiException.bad("Someone on the team already has that email");
        }
        UUID id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO platform_admins (email, name, password_hash, role, active, invite_pending) VALUES (?, ?, ?, ?, false, true) RETURNING id",
                    UUID.class, email, name, invites.unusablePasswordHash(), role);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.bad("Someone on the team already has that email");
        }
        admin.audit("team.add", null, Map.of("email", email, "role", role));
        String sent = invites.send(id);
        admin.audit("team.invite_send", null, Map.of("email", email, "result", sent));
        return Map.of("ok", true, "id", id.toString(), "invite", sent);
    }

    /** Send the invite again (the first email went missing or the link ran out). Refused once they have accepted. */
    @PostMapping("/team/{id}/invite")
    public Map<String, Object> resendInvite(@PathVariable UUID id) {
        AdminContext.require("team");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT email, invite_pending, blocked_at FROM platform_admins WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Teammate");
        }
        if (rows.get(0).get("blocked_at") != null) {
            throw ApiException.bad("They are switched off. Switch them on first if you want them to join.");
        }
        if (!Boolean.TRUE.equals(rows.get(0).get("invite_pending"))) {
            throw ApiException.bad("They have already set their password, so there is nothing to send again");
        }
        String sent = invites.send(id);
        admin.audit("team.invite_send", null, Map.of("email", rows.get(0).get("email"), "result", sent));
        return Map.of("ok", true, "invite", sent);
    }

    @PutMapping("/team/{id}")
    public Map<String, Object> updateAdmin(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("team");
        Body b = new Body(raw);
        String role = b.str("role", "SUPPORT").toUpperCase();
        if (!ADMIN_ROLES.contains(role)) {
            throw ApiException.bad("Unknown role");
        }
        if (id.equals(AdminContext.admin().id()) && (!"SUPERADMIN".equals(role) || !b.bool("active", true))) {
            throw ApiException.bad("You can't demote or switch off your own account");
        }
        Map<String, Object> before = jdbc.queryForMap("SELECT role, active FROM platform_admins WHERE id = ?", id);
        if (!role.equals(before.get("role")) || b.bool("active", true) != Boolean.TRUE.equals(before.get("active"))) {
            versions.bump(id, true);
        }
        boolean on = b.bool("active", true);
        // Off is durable: blocked_at records the refusal, and every door checks it — the resend, the link and
        // accepting one. invite_pending is left alone on purpose: it only ever means "has not accepted yet", so
        // switching someone back on returns them to waiting and the invite can be sent again. Clearing it here
        // would leave an unblocked colleague who never accepted with no way in at all.
        jdbc.update("UPDATE platform_admins SET role = ?, active = ?, name = coalesce(?, name), "
                        + "blocked_at = CASE WHEN ? THEN NULL ELSE coalesce(blocked_at, now()) END WHERE id = ?",
                role, on, b.str("name", null), on, id);
        if (!on) {
            invites.cancel(id);
        }
        admin.audit("team.update", null, Map.of("admin", id.toString(), "role", role, "active", b.bool("active", true)));
        return Map.of("ok", true);
    }

    // ---------- audit & system ----------

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(required = false) UUID tenantId) {
        AdminContext.require("read");
        return jdbc.queryForList("""
                SELECT a.id, a.action, a.admin_name, a.detail::text AS detail, a.created_at, t.name AS tenant_name, a.tenant_id
                FROM platform_audit a LEFT JOIN tenants t ON t.id = a.tenant_id
                WHERE (cast(? as uuid) IS NULL OR a.tenant_id = cast(? as uuid)) ORDER BY a.created_at DESC LIMIT 300""", tenantId, tenantId)
                .stream().map(this::clean).toList();
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        AdminContext.require("system");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database", jdbc.queryForObject("SELECT version()", String.class));
        out.put("databaseSize", jdbc.queryForObject("SELECT pg_size_pretty(pg_database_size(current_database()))", String.class));
        out.put("schemaVersion", jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history WHERE success", String.class));
        out.put("migrations", jdbc.queryForList("SELECT version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank")
                .stream().map(this::clean).toList());
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        out.put("uptimeMinutes", uptime / 60000);
        Runtime rt = Runtime.getRuntime();
        out.put("memoryUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1_048_576);
        out.put("memoryMaxMb", rt.maxMemory() / 1_048_576);
        out.put("java", System.getProperty("java.version"));
        out.put("startedAt", Instant.now().minus(uptime, ChronoUnit.MILLIS).toString());
        out.put("demoLogins", demos.enabled());
        out.put("rows", jdbc.queryForList("""
                SELECT relname AS table_name, n_live_tup AS rows FROM pg_stat_user_tables
                WHERE relname IN ('tenants','users','orders','order_lines','items','customers','stays','trips','leases','stock_moves','platform_audit')
                ORDER BY n_live_tup DESC"""));
        return out;
    }

    // ---------- helpers ----------

    private Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            StringBuilder sb = new StringBuilder();
            boolean up = false;
            for (char ch : k.toCharArray()) {
                if (ch == '_') {
                    up = true;
                } else {
                    sb.append(up ? Character.toUpperCase(ch) : ch);
                    up = false;
                }
            }
            Object val = v instanceof Timestamp ts ? ts.toInstant().toString() : v instanceof UUID u ? u.toString() : v;
            if (("detail".equals(k) || "lines".equals(k)) && v instanceof String s) {
                try {
                    val = json.readTree(s);
                } catch (Exception ignored) {
                    // keep as text
                }
            }
            out.put(sb.toString(), val);
        });
        return out;
    }

    /** A payment request with the name this business sees for its plan next to the code. */
    private Map<String, Object> withPlanName(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("plan_name", platform.planName((String) r.get("sector"), (String) r.get("sub_sector"), (String) r.get("plan")));
        return m;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
