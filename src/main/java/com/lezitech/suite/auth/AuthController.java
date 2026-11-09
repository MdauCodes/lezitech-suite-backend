package com.lezitech.suite.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.config.TrialGuard;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.sector.SeedService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;
    private final TokenVersions versions;
    private final JwtService jwt;
    private final SectorCatalog sectors;
    private final SeedService seeder;
    private final ObjectMapper json;
    private final int trialDays;
    private final com.lezitech.suite.growth.Referrals referrals;
    private final com.lezitech.suite.growth.MetaConversions meta;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt, SectorCatalog sectors,
                          SeedService seeder, ObjectMapper json, @Value("${suite.trial-days}") int trialDays,
                          LoginThrottle throttle, TokenVersions versions,
                          com.lezitech.suite.growth.Referrals referrals, com.lezitech.suite.growth.MetaConversions meta) {
        this.referrals = referrals;
        this.meta = meta;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.throttle = throttle;
        this.versions = versions;
        this.jwt = jwt;
        this.sectors = sectors;
        this.seeder = seeder;
        this.json = json;
        this.trialDays = trialDays;
    }

    /** Start a free trial: workspace, owner login and sector sample data in one step. */
    @PostMapping("/signup")
    public Map<String, Object> signup(@RequestBody Map<String, Object> raw, jakarta.servlet.http.HttpServletRequest req) throws Exception {
        Map<String, Object> session = createWorkspace(raw, null);
        // Which ad or referral brought this business, and tell Meta a trial started (same event id as the browser pixel).
        String email = new Body(raw).str("email").toLowerCase();
        UUID tenantId = jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, email);
        ObjectNode acq = com.lezitech.suite.growth.Acquisition.clean(json, raw.get("acquisition"));
        if (acq != null) {
            jdbc.update("UPDATE tenants SET acquisition = cast(? as jsonb) WHERE id = ?", acq.toString(), tenantId);
        }
        Object ref = raw.get("referralCode") != null ? raw.get("referralCode") : acq == null ? null : acq.path("ref").asText(null);
        referrals.link(tenantId, ref == null ? null : ref.toString());
        referrals.codeFor(tenantId);
        Object eventId = raw.get("eventId");
        meta.track("StartTrial", eventId == null ? null : eventId.toString(), tenantId, email, new Body(raw).str("phone", null),
                com.lezitech.suite.growth.Acquisition.visitor(acq, req), java.math.BigDecimal.ZERO);
        return session;
    }

    /** Used by signup and by the demo seeder (which passes a fixed slug like demo-restaurant). */
    public Map<String, Object> createWorkspace(Map<String, Object> raw, String fixedSlug) throws Exception {
        Body b = new Body(raw);
        String email = b.str("email").toLowerCase();
        if (!EMAIL.matcher(email).matches()) {
            throw ApiException.bad("Enter a valid email address");
        }
        String password = b.str("password");
        if (password.length() < 8) {
            throw ApiException.bad("Use a password of at least 8 characters");
        }
        Integer taken = jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        if (taken != null && taken > 0) {
            throw ApiException.conflict("That email already has a workspace. Sign in instead.");
        }
        String business = b.str("businessName");
        // The pack personalised for the chosen sub-sector (its own wording, features, KPIs and sample items).
        JsonNode pack = sectors.get(b.str("sector"), b.str("subSector", null));
        if (fixedSlug == null && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT exists(SELECT 1 FROM platform_settings WHERE key = 'disabled_sectors' AND value @> to_jsonb(cast(? as text)))""", Boolean.class, pack.get("key").asText()))) {
            throw ApiException.bad("Signups for this kind of business are paused. Please contact LeziTech.");
        }
        String color = b.str("brandColor", pack.get("color").asText());
        if (!HEX.matcher(color).matches()) {
            color = pack.get("color").asText();
        }
        @SuppressWarnings("unchecked")
        List<String> products = raw.get("products") instanceof List<?> l && !l.isEmpty()
                ? (List<String>) l : json.convertValue(pack.get("products"), List.class);

        ObjectNode settings = json.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode modules = pack.get("modules").deepCopy();
        ObjectNode features = pack.get("features").deepCopy();
        // Optional add-ons the owner ticked at signup, e.g. a restaurant that also lets rooms.
        @SuppressWarnings("unchecked")
        List<String> extras = raw.get("extras") instanceof List<?> el ? (List<String>) el : List.of();
        for (JsonNode extra : pack.path("extras")) {
            if (extras.contains(extra.get("key").asText())) {
                extra.path("modules").forEach(m -> {
                    if (!modules.toString().contains("\"" + m.asText() + "\"")) {
                        modules.insert(1, m.asText());
                    }
                });
                extra.path("features").fields().forEachRemaining(f -> features.set(f.getKey(), f.getValue()));
            }
        }
        settings.set("modules", modules);
        settings.set("features", features);
        settings.put("receiptFooter", b.str("receiptFooter", "Thank you for your business!"));
        settings.put("storeFeedsShops", pack.path("features").path("storeFeedsShops").asBoolean(false)
                && b.bool("storeFeedsShops", true));
        String accent = b.str("accentColor", null);
        if (accent != null && HEX.matcher(accent).matches()) {
            settings.put("accentColor", accent);
        }
        settings.put("address", b.str("address", ""));
        // Answers to the sub-sector's setup questions (e.g. "Do you sell by weight?"), to tailor the workspace later.
        if (raw.get("setupAnswers") instanceof Map<?, ?> answers && !answers.isEmpty()) {
            settings.set("setupAnswers", json.valueToTree(answers));
        }
        settings.put("kraPin", b.str("kraPin", "").toUpperCase());
        settings.put("tagline", b.str("tagline", ""));
        String logo = b.str("logoUrl", null);
        if (logo != null && (!logo.startsWith("data:image/") || logo.length() > 400_000)) {
            throw ApiException.bad("Logo must be an image under 300 KB");
        }

        String slug = fixedSlug != null ? fixedSlug : uniqueSlug(business);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant trialEnds = Instant.now().plus(trialDays, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO tenants (id, slug, name, sector, sub_sector, products, trial_ends_at, brand_color, logo_url, phone, settings)
                VALUES (?, ?, ?, ?, ?, cast(? as text[]), ?, ?, ?, ?, cast(? as jsonb))""",
                tenantId, slug, business, pack.get("key").asText(), pack.has("subSector") ? pack.get("subSector").asText() : b.str("subSector", null),
                "{" + String.join(",", products.stream().filter(p -> List.of("POS", "CRM", "ERP").contains(p)).toList()) + "}",
                Timestamp.from(trialEnds), color, logo, b.str("phone", null), settings.toString());
        String name = b.str("name");
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, password_hash, role) VALUES (?, ?, ?, ?, ?, 'OWNER')",
                userId, tenantId, email, name, encoder.encode(password));

        boolean samples = b.bool("withSamples", true);
        TenantContext.runAs(tenantId, userId, () -> {
            seeder.seed(pack, samples, slug);
            if (samples && extras.contains("rooms")) {
                seeder.seedExtraRooms();
            }
            return null;
        });
        return session(userId, tenantId, "OWNER", name);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> raw, jakarta.servlet.http.HttpServletRequest req) {
        Body b = new Body(raw);
        String account = "login:" + b.str("email").toLowerCase();
        throttle.check(account, req);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tenant_id, name, role, password_hash, active FROM users WHERE email = ?",
                b.str("email").toLowerCase());
        if (rows.isEmpty() || !encoder.matches(b.str("password"), (String) rows.get(0).get("password_hash"))) {
            throttle.failed(account, req);
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "BAD_LOGIN", "Wrong email or password");
        }
        throttle.succeeded(account);
        Map<String, Object> u = rows.get(0);
        if (!Boolean.TRUE.equals(u.get("active"))) {
            throw ApiException.forbidden("This account has been switched off by the owner");
        }
        return session((UUID) u.get("id"), (UUID) u.get("tenant_id"), (String) u.get("role"), (String) u.get("name"));
    }

    /** Hand the till to another staff member with their 4-digit PIN (cf. Odoo pos_hr cashier switch). */
    @PostMapping("/pin")
    public Map<String, Object> pin(@RequestBody Map<String, Object> raw, jakarta.servlet.http.HttpServletRequest req) {
        Body b = new Body(raw);
        UUID tenantId = TenantContext.tenantId();
        String account = "pin:" + b.str("userId");
        throttle.check(account, req);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, role, pin_hash FROM users WHERE id = ? AND tenant_id = ? AND active",
                UUID.fromString(b.str("userId")), tenantId);
        if (rows.isEmpty() || rows.get(0).get("pin_hash") == null
                || !encoder.matches(b.str("pin"), (String) rows.get(0).get("pin_hash"))) {
            throttle.failed(account, req);
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "BAD_PIN", "Wrong PIN");
        }
        throttle.succeeded(account);
        Map<String, Object> u = rows.get(0);
        return session((UUID) u.get("id"), tenantId, (String) u.get("role"), (String) u.get("name"));
    }

    /** Silent renewal: a signed-in till swaps its token for a fresh one before it expires. */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        TenantContext.Caller c = TenantContext.caller();
        return Map.of("token", jwt.issue(c.userId(), c.tenantId(), c.role(), c.name(), versions.current(c.userId(), false)));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        TenantContext.Caller c = TenantContext.caller();
        return profile(c.userId(), c.tenantId());
    }

    public Map<String, Object> session(UUID userId, UUID tenantId, String role, String name) {
        jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", userId);
        Map<String, Object> out = profile(userId, tenantId);
        out.put("token", jwt.issue(userId, tenantId, role, name, versions.current(userId, false)));
        return out;
    }

    private Map<String, Object> profile(UUID userId, UUID tenantId) {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT id, slug, name, sector, sub_sector, products, status, plan, trial_ends_at, paid_until,
                       brand_color, logo_url, currency, phone, display_key, is_demo, settings::text AS settings
                FROM tenants WHERE id = ?""", tenantId);
        Map<String, Object> u = jdbc.queryForMap("SELECT id, email, name, role FROM users WHERE id = ?", userId);

        Timestamp trialEnds = (Timestamp) t.get("trial_ends_at");
        Timestamp paidUntil = (Timestamp) t.get("paid_until");
        String status = (String) t.get("status");
        long daysLeft = Math.max(0, (long) Math.ceil(
                Duration.between(Instant.now(), trialEnds.toInstant()).toHours() / 24.0));

        Map<String, Object> tenant = new LinkedHashMap<>();
        tenant.put("id", t.get("id").toString());
        tenant.put("slug", t.get("slug"));
        tenant.put("name", t.get("name"));
        tenant.put("sector", t.get("sector"));
        tenant.put("subSector", t.get("sub_sector"));
        try {
            tenant.put("products", List.of((Object[]) ((java.sql.Array) t.get("products")).getArray()));
            tenant.put("settings", json.readTree((String) t.get("settings")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        tenant.put("status", status);
        tenant.put("plan", t.get("plan"));
        tenant.put("trialEndsAt", trialEnds.toInstant().toString());
        tenant.put("paidUntil", paidUntil == null ? null : paidUntil.toInstant().toString());
        tenant.put("trialDaysLeft", "TRIAL".equals(status) ? daysLeft : null);
        tenant.put("locked", TrialGuard.isLocked(status, trialEnds, paidUntil));
        tenant.put("brandColor", t.get("brand_color"));
        tenant.put("logoUrl", t.get("logo_url"));
        tenant.put("currency", t.get("currency"));
        tenant.put("phone", t.get("phone"));
        tenant.put("displayKey", t.get("display_key"));
        tenant.put("isDemo", t.get("is_demo"));

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", u.get("id").toString());
        user.put("email", u.get("email"));
        user.put("name", u.get("name"));
        user.put("role", u.get("role"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user);
        out.put("tenant", tenant);
        out.put("pack", sectors.publicView((String) t.get("sector"), (String) t.get("sub_sector")));
        return out;
    }

    private String uniqueSlug(String business) {
        String base = business.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "shop";
        }
        if (base.equals("demo") || base.startsWith("demo-")) {
            base = "my-" + base; // demo-* addresses belong to the one-click demo businesses
        }
        base = base.length() > 40 ? base.substring(0, 40) : base;
        String slug = base;
        int n = 2;
        while (Boolean.TRUE.equals(jdbc.queryForObject("SELECT exists(SELECT 1 FROM tenants WHERE slug = ?)", Boolean.class, slug))) {
            slug = base + "-" + n++;
        }
        return slug;
    }
}
