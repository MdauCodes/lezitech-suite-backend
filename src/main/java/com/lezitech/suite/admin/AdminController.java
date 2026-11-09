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
    private final AdminService.UsageReader usage;
    private final AuthController auth;
    private final DemoService demos;
    private final SectorCatalog sectors;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.settings.PlanActivation activation;

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

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> raw, jakarta.servlet.http.HttpServletRequest req) {
        Body b = new Body(raw);
        String account = "admin:" + b.str("email").toLowerCase();
        throttle.check(account, req);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, role, password_hash, active FROM platform_admins WHERE email = ?", b.str("email").toLowerCase());
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("active"))
                || !encoder.matches(b.str("password"), (String) rows.get(0).get("password_hash"))) {
            throttle.failed(account, req);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_LOGIN", "Wrong email or password");
        }
        throttle.succeeded(account);
        return adminSession(rows.get(0));
    }

    @PostMapping("/auth/demo")
    public Map<String, Object> demoLogin(@RequestBody Map<String, Object> raw) {
        if (!admin.demoLogins()) {
            throw ApiException.forbidden("Demo control-panel logins are only available on local runs");
        }
        String role = new Body(raw).str("role").toUpperCase();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, role FROM platform_admins WHERE email = ? AND active", role.toLowerCase() + "@lezitech.test");
        if (rows.isEmpty()) {
            throw ApiException.notFound("Demo staff account");
        }
        return adminSession(rows.get(0));
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

    private Map<String, Object> adminSession(Map<String, Object> row) {
        UUID id = (UUID) row.get("id");
        jdbc.update("UPDATE platform_admins SET last_login_at = now() WHERE id = ?", id);
        String role = (String) row.get("role");
        return Map.of("token", jwt.issueAdmin(id, role, (String) row.get("name"), versions.current(id, true)),
                "admin", Map.of("id", id.toString(), "name", row.get("name"), "role", role, "permissions", AdminContext.permissions(role)));
    }

    // ---------- overview ----------

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        AdminContext.require("read");
        Map<String, Object> out = new LinkedHashMap<>();
        String real = " FROM tenants WHERE NOT is_demo ";
        out.put("workspaces", jdbc.queryForObject("SELECT count(*)" + real, Long.class));
        out.put("onTrial", jdbc.queryForObject("SELECT count(*)" + real + "AND status = 'TRIAL' AND trial_ends_at > now()", Long.class));
        out.put("trialExpired", jdbc.queryForObject("SELECT count(*)" + real + "AND status = 'TRIAL' AND trial_ends_at <= now()", Long.class));
        out.put("paying", jdbc.queryForObject("SELECT count(*)" + real + "AND status = 'ACTIVE' AND (paid_until IS NULL OR paid_until > now())", Long.class));
        out.put("suspended", jdbc.queryForObject("SELECT count(*)" + real + "AND status = 'SUSPENDED'", Long.class));
        out.put("endingIn48h", jdbc.queryForObject("SELECT count(*)" + real + "AND status = 'TRIAL' AND trial_ends_at BETWEEN now() AND now() + interval '48 hours'", Long.class));
        out.put("activeToday", jdbc.queryForObject("SELECT count(*)" + real + "AND last_active_at > now() - interval '24 hours'", Long.class));
        out.put("signups7d", jdbc.queryForObject("SELECT count(*)" + real + "AND created_at > now() - interval '7 days'", Long.class));
        out.put("pendingRequests", jdbc.queryForObject("SELECT count(*) FROM subscription_requests WHERE status = 'PENDING'", Long.class));
        out.put("demoWorkspaces", jdbc.queryForObject("SELECT count(*) FROM tenants WHERE is_demo", Long.class));
        // Monthly recurring revenue from paying workspaces at list price.
        long mrr = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT plan, count(*) AS n" + real + "AND status = 'ACTIVE' GROUP BY plan")) {
            mrr += SettingsController.PLANS.stream().filter(p -> p.get("code").equals(r.get("plan")))
                    .mapToLong(p -> ((Integer) p.get("monthly")).longValue() * ((Number) r.get("n")).longValue()).sum();
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
    public List<Map<String, Object>> tenants(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String sector, @RequestParam(defaultValue = "false") boolean demo) {
        AdminContext.require("read");
        String st = status == null || status.isBlank() ? null : status;
        return jdbc.queryForList("""
                SELECT t.id, t.slug, t.name, t.sector, t.sub_sector, t.status, t.plan, t.trial_ends_at, t.paid_until, t.created_at,
                       t.last_active_at, t.phone, t.brand_color, t.logo_url, t.is_demo,
                       CASE WHEN t.in_setup THEN 'SETUP'
                            WHEN t.status = 'TRIAL' AND t.trial_ends_at <= now() THEN 'EXPIRED'
                            WHEN t.status = 'ACTIVE' AND t.paid_until < now() THEN 'LAPSED' ELSE t.status END AS state,
                       (SELECT email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' ORDER BY created_at LIMIT 1) AS owner_email,
                       (SELECT count(*) FROM users u WHERE u.tenant_id = t.id) AS users,
                       (SELECT max(last_login_at) FROM users u WHERE u.tenant_id = t.id) AS last_login_at,
                       (SELECT plan || ' ' || billing_cycle FROM subscription_requests r WHERE r.tenant_id = t.id AND r.status = 'PENDING'
                        ORDER BY created_at DESC LIMIT 1) AS pending_request
                FROM tenants t
                WHERE t.is_demo = ?
                  AND (cast(? as text) IS NULL OR t.name ILIKE '%' || cast(? as text) || '%' OR t.slug ILIKE '%' || cast(? as text) || '%'
                       OR EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = t.id AND u.email ILIKE '%' || cast(? as text) || '%'))
                  AND (cast(? as text) IS NULL OR t.sector = cast(? as text))
                ORDER BY t.created_at DESC LIMIT 500""",
                demo, blank(q), blank(q), blank(q), blank(q), blank(sector), blank(sector))
                .stream().filter(t -> st == null || st.equals(t.get("state"))).map(this::clean).toList();
    }

    @GetMapping("/tenants/{id}")
    public Map<String, Object> tenant(@PathVariable UUID id) throws Exception {
        AdminContext.require("read");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, slug, name, sector, sub_sector, products, status, plan, trial_ends_at, paid_until, created_at, last_active_at,
                       phone, brand_color, logo_url, currency, is_demo, admin_note, display_key, in_setup, handed_over_at, settings::text AS settings FROM tenants WHERE id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Workspace");
        }
        Map<String, Object> t = clean(rows.get(0));
        t.put("settings", json.readTree((String) rows.get(0).get("settings")));
        t.put("products", List.of((Object[]) ((java.sql.Array) rows.get(0).get("products")).getArray()));
        t.put("users", jdbc.queryForList("""
                SELECT id, name, email, role, active, last_login_at, created_at FROM users WHERE tenant_id = ? ORDER BY role = 'OWNER' DESC, name""", id)
                .stream().map(this::clean).toList());
        t.put("requests", jdbc.queryForList("""
                SELECT id, plan, billing_cycle, amount, mpesa_phone, status, created_at FROM subscription_requests WHERE tenant_id = ?
                ORDER BY created_at DESC""", id).stream().map(this::clean).toList());
        t.put("audit", jdbc.queryForList("""
                SELECT action, admin_name, detail::text AS detail, created_at FROM platform_audit WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 50""", id)
                .stream().map(this::clean).toList());
        t.put("usage", TenantContext.runAs(id, null, usage::usage));
        JsonNode pack = sectors.get((String) rows.get(0).get("sector"));
        t.put("packModules", pack.get("modules"));
        t.put("packName", pack.get("name").asText());
        return t;
    }

    @PostMapping("/tenants/{id}/activate")
    public Map<String, Object> activate(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("billing");
        Body b = new Body(raw);
        String plan = b.str("plan").toUpperCase();
        int months = b.integer("months", 1);
        jdbc.update("""
                UPDATE tenants SET status = 'ACTIVE', plan = ?,
                       paid_until = greatest(coalesce(paid_until, now()), now()) + make_interval(months => ?) WHERE id = ?""", plan, months, id);
        jdbc.update("UPDATE subscription_requests SET status = 'PAID' WHERE tenant_id = ? AND status = 'PENDING'", id);
        admin.audit("plan.activate", id, Map.of("plan", plan, "months", months, "reference", b.str("reference", "")));
        return Map.of("ok", true);
    }

    @PostMapping("/tenants/{id}/extend-trial")
    public Map<String, Object> extendTrial(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("trials");
        int days = new Body(raw).integer("days", 7);
        jdbc.update("""
                UPDATE tenants SET status = 'TRIAL', trial_ends_at = greatest(trial_ends_at, now()) + make_interval(days => ?) WHERE id = ?""", days, id);
        admin.audit("trial.extend", id, Map.of("days", days));
        return Map.of("ok", true);
    }

    @PostMapping("/tenants/{id}/suspend")
    public Map<String, Object> suspend(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        AdminContext.require("suspend");
        jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", id);
        admin.audit("workspace.suspend", id, Map.of("reason", new Body(raw).str("reason", "")));
        return Map.of("ok", true);
    }

    @PostMapping("/tenants/{id}/reactivate")
    public Map<String, Object> reactivate(@PathVariable UUID id) {
        AdminContext.require("suspend");
        jdbc.update("""
                UPDATE tenants SET status = CASE WHEN paid_until IS NOT NULL AND paid_until > now() THEN 'ACTIVE' ELSE 'TRIAL' END,
                       trial_ends_at = CASE WHEN paid_until IS NULL OR paid_until <= now() THEN greatest(trial_ends_at, now() + interval '3 days') ELSE trial_ends_at END
                WHERE id = ?""", id);
        admin.audit("workspace.reactivate", id, Map.of());
        return Map.of("ok", true);
    }

    @PostMapping("/tenants/{id}/note")
    public Map<String, Object> note(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("notes");
        String note = new Body(raw).str("note", "");
        jdbc.update("UPDATE tenants SET admin_note = ? WHERE id = ?", note, id);
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

    /** Sign in as the workspace owner to see exactly what the customer sees. Always audited. */
    @PostMapping("/tenants/{id}/impersonate")
    public Map<String, Object> impersonate(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        AdminContext.require("impersonate");
        String role = new Body(raw).str("role", "OWNER").toUpperCase();
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, name, role FROM users WHERE tenant_id = ? AND role = ? AND active ORDER BY created_at LIMIT 1", id, role);
        if (users.isEmpty()) {
            throw ApiException.notFound("Active " + role.toLowerCase() + " in that workspace");
        }
        Map<String, Object> u = users.get(0);
        admin.audit("workspace.impersonate", id, Map.of("as", u.get("name"), "role", role));
        return auth.session((UUID) u.get("id"), id, role, (String) u.get("name"));
    }

    // ---------- billing queue ----------

    @GetMapping("/requests")
    public List<Map<String, Object>> requests(@RequestParam(defaultValue = "PENDING") String status) {
        AdminContext.require("read");
        return jdbc.queryForList("""
                SELECT r.id, r.tenant_id, t.name AS tenant_name, t.sector, r.plan, r.billing_cycle, r.amount, r.mpesa_phone, r.status, r.created_at
                FROM subscription_requests r JOIN tenants t ON t.id = r.tenant_id
                WHERE (? = 'ALL' OR r.status = ?) ORDER BY r.created_at DESC LIMIT 200""", status, status).stream().map(this::clean).toList();
    }

    @PostMapping("/requests/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        AdminContext.require("billing");
        Map<String, Object> r = activation.activate(id, "MANUAL", null);
        admin.audit("billing.approve", (UUID) r.get("tenant_id"), Map.of("plan", r.get("plan"), "amount", r.get("amount"),
                "reference", new Body(raw).str("reference", "")));
        return Map.of("ok", true);
    }

    @PostMapping("/requests/{id}/reject")
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        AdminContext.require("billing");
        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM subscription_requests WHERE id = ?", UUID.class, id);
        jdbc.update("UPDATE subscription_requests SET status = 'REJECTED' WHERE id = ?", id);
        admin.audit("billing.reject", tenant, Map.of("reason", new Body(raw).str("reason", "")));
        return Map.of("ok", true);
    }

    // ---------- sectors ----------

    @GetMapping("/sectors")
    public List<Map<String, Object>> sectorList() {
        AdminContext.require("read");
        List<String> disabled = disabledSectors();
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode p : sectors.all()) {
            String key = p.get("key").asText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("name", p.get("name").asText());
            row.put("icon", p.get("icon").asText());
            row.put("color", p.get("color").asText());
            row.put("modules", p.get("modules"));
            row.put("subSectors", p.get("subSectors"));
            row.put("enabled", !disabled.contains(key));
            row.put("workspaces", jdbc.queryForObject("SELECT count(*) FROM tenants WHERE sector = ? AND NOT is_demo", Long.class, key));
            row.put("paying", jdbc.queryForObject("SELECT count(*) FROM tenants WHERE sector = ? AND NOT is_demo AND status = 'ACTIVE'", Long.class, key));
            out.add(row);
        }
        return out;
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
        return jdbc.queryForList("SELECT id, email, name, role, active, last_login_at, created_at FROM platform_admins ORDER BY role, name")
                .stream().map(this::clean).toList();
    }

    @PostMapping("/team")
    public Map<String, Object> addAdmin(@RequestBody Map<String, Object> raw) {
        AdminContext.require("team");
        Body b = new Body(raw);
        String role = b.str("role", "SUPPORT").toUpperCase();
        if (!ADMIN_ROLES.contains(role)) {
            throw ApiException.bad("Unknown role");
        }
        String password = b.str("password");
        if (password.length() < 10) {
            throw ApiException.bad("Use at least 10 characters for control-panel passwords");
        }
        jdbc.update("INSERT INTO platform_admins (email, name, password_hash, role) VALUES (?, ?, ?, ?)",
                b.str("email").toLowerCase(), b.str("name"), encoder.encode(password), role);
        admin.audit("team.add", null, Map.of("email", b.str("email"), "role", role));
        return Map.of("ok", true);
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
        jdbc.update("UPDATE platform_admins SET role = ?, active = ?, name = coalesce(?, name) WHERE id = ?",
                role, b.bool("active", true), b.str("name", null), id);
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
            if ("detail".equals(k) && v instanceof String s) {
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

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
