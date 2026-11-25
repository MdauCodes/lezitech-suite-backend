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
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlatformSettings platform;
    private final com.lezitech.suite.growth.Referrals referrals;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lezitech.suite.growth.MallOffer mallOffer;
    private final com.lezitech.suite.growth.MetaConversions meta;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt, SectorCatalog sectors,
                          SeedService seeder, ObjectMapper json, @Value("${suite.trial-days}") int trialDays,
                          LoginThrottle throttle, TokenVersions versions,
                          com.lezitech.suite.growth.Referrals referrals, com.lezitech.suite.growth.MetaConversions meta) {
        this.referrals = referrals;
        this.meta = meta;
        // Made once, here, so no request ever pays for it or races to make it.
        this.dummyHash = encoder.encode("no-one-has-this-password-" + UUID.randomUUID());
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
            ObjectNode first = com.lezitech.suite.traffic.SignupSource.of(json, acq);
            jdbc.update("UPDATE tenants SET signup_source = cast(? as jsonb) WHERE id = ?", first.toString(), tenantId);
        }
        Object ref = raw.get("referralCode") != null ? raw.get("referralCode") : acq == null ? null : acq.path("ref").asText(null);
        referrals.link(tenantId, ref == null ? null : ref.toString());
        referrals.codeFor(tenantId);
        // A tenant of a mall that offers Busynez at a discount keeps that discount on every payment.
        if (mallOffer != null && acq != null && acq.hasNonNull("mall")) {
            mallOffer.linkSignup(tenantId, acq.get("mall").asText());
        }
        Object eventId = raw.get("eventId");
        meta.track("StartTrial", eventId == null ? null : eventId.toString(), tenantId, email, new Body(raw).str("phone", null),
                com.lezitech.suite.growth.Acquisition.visitor(acq, req), java.math.BigDecimal.ZERO);
        return session;
    }

    /** Used by signup and by the demo seeder (which passes a fixed slug like demo-restaurant). */
    public Map<String, Object> createWorkspace(Map<String, Object> raw, String fixedSlug) throws Exception {
        return createWorkspace(raw, fixedSlug, null);
    }

    /** Who a new business belongs to when an owner adds another one: they keep signing in with their first entry. */
    public record LinkedOwner(UUID personId, UUID groupId, String name) {
    }

    /**
     * As above; with {@code owner} it's another business of an owner already on Busynez: no email or password of its own
     * (they sign in as before and switch), and it joins their group.
     */
    public Map<String, Object> createWorkspace(Map<String, Object> raw, String fixedSlug, LinkedOwner owner) throws Exception {
        Body b = new Body(raw);
        String email = owner != null ? null : b.str("email").toLowerCase();
        String password = owner != null ? null : b.str("password");
        if (owner == null) {
            if (!EMAIL.matcher(email).matches()) {
                throw ApiException.bad("Enter a valid email address");
            }
            if (password.length() < 8) {
                throw ApiException.bad("Use a password of at least 8 characters");
            }
            Boolean taken = jdbc.queryForObject("SELECT app_email_taken(cast(? as text))", Boolean.class, email);
            if (Boolean.TRUE.equals(taken)) {
                throw ApiException.conflict("That email already has a business with us. Sign in instead.");
            }
        }
        String business = b.str("businessName");
        // The pack personalised for the chosen sub-sector (its own wording, features, KPIs and sample items).
        JsonNode pack = sectors.get(b.str("sector"), b.str("subSector", null));
        // Coming-soon sectors (pharmacy, clinics: patients' health data) can't be opened yet, by anyone.
        if (sectors.comingSoon(b.str("sector"), b.str("subSector", null))) {
            throw ApiException.bad("Busynez for " + pack.path("subSector").asText(pack.get("name").asText()).toLowerCase()
                    + " is coming soon. Ask us to tell you when it's ready.");
        }
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
        // The sub-sector's own switches decide the screens: no tables, no kitchen or no appointments take those out.
        SectorCatalog.fitModulesToFeatures(modules, features);
        // Screens the owner left unticked at sign-up ("what you'll use"). The basics every business needs stay on:
        // the till, products with stock counts, sales, customers, reports, staff and settings.
        if (raw.get("modulesOff") instanceof List<?> off && !off.isEmpty()) {
            java.util.Set<String> always = java.util.Set.of("dashboard", "pos", "items", "stock", "sales", "customers", "reports", "team", "settings");
            for (int i = modules.size() - 1; i >= 0; i--) {
                String m = modules.get(i).asText();
                if (off.contains(m) && !always.contains(m)) {
                    modules.remove(i);
                    switch (m) {
                        case "floor" -> features.put("tables", false);
                        case "kitchen" -> features.put("kitchen", false);
                        case "bookings" -> features.put("appointments", false);
                        default -> { }
                    }
                }
            }
        }
        // Screens added at sign-up from "you can add".
        if (raw.get("modulesOn") instanceof List<?> on) {
            for (Object o : on) {
                String m = String.valueOf(o);
                if (java.util.Set.of("crm", "quotes", "purchasing").contains(m) && !modules.toString().contains("\"" + m + "\"")) {
                    modules.add(m);
                }
            }
        }
        settings.set("modules", modules);
        // The extra tools the owner said they need ("what you'll use"); this is their setup when the free days end.
        com.fasterxml.jackson.databind.node.ArrayNode chosen = settings.putArray("chosenTools");
        if (raw.get("tools") instanceof List<?> tools) {
            tools.stream().map(String::valueOf).filter(com.lezitech.suite.platform.PlanFeatures.KEYS::contains).distinct().forEach(chosen::add);
            // Screens that are tools follow the choice exactly, so nobody lands on a bigger plan by a default they never saw.
            java.util.Set<String> picked = new java.util.HashSet<>();
            chosen.forEach(n -> picked.add(n.asText()));
            for (int i = modules.size() - 1; i >= 0; i--) {
                String m = modules.get(i).asText();
                if (java.util.Set.of("crm", "quotes", "purchasing").contains(m) && !picked.contains(m)) {
                    modules.remove(i);
                }
            }
            for (String m : List.of("crm", "quotes", "purchasing")) {
                if (picked.contains(m) && !modules.toString().contains("\"" + m + "\"")) {
                    modules.add(m);
                }
            }
            settings.set("modules", modules);
        }
        // "Do your products have barcodes printed on them?" Most Kenyan shops' goods don't, so barcode words only show
        // when the owner says yes (or saves a product with a barcode later).
        if (raw.containsKey("barcodes")) {
            features.put("barcode", b.bool("barcodes", false));
        }
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
            // "How do customers order?" At the counter: the till is their screen, so the table plan stays out of the
            // menu (Settings can put it back) and the till opens first.
            if ("Order at the counter".equals(answers.get("service"))) {
                for (int i = modules.size() - 1; i >= 0; i--) {
                    if ("floor".equals(modules.get(i).asText())) {
                        modules.remove(i);
                    }
                }
                features.put("tables", false);
            }
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
        Instant trialEnds = Instant.now().plus(platform.trialDays(), ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO tenants (id, slug, name, sector, sub_sector, products, trial_ends_at, brand_color, logo_url, phone, settings)
                VALUES (?, ?, ?, ?, ?, cast(? as text[]), ?, ?, ?, ?, cast(? as jsonb))""",
                tenantId, slug, business, pack.get("key").asText(), pack.has("subSector") ? pack.get("subSector").asText() : b.str("subSector", null),
                "{" + String.join(",", products.stream().filter(p -> List.of("POS", "CRM", "ERP").contains(p)).toList()) + "}",
                Timestamp.from(trialEnds), color, logo, b.str("phone", null), settings.toString());
        String name = owner != null ? owner.name() : b.str("name");
        jdbc.update("INSERT INTO users (id, tenant_id, email, name, password_hash, role, person_id) VALUES (?, ?, ?, ?, ?, 'OWNER', ?)",
                userId, tenantId, email, name, password == null ? null : encoder.encode(password), owner != null ? owner.personId() : userId);
        jdbc.update("UPDATE tenants SET group_id = ? WHERE id = ?", owner != null ? owner.groupId() : tenantId, tenantId);

        boolean samples = b.bool("withSamples", false);
        TenantContext.runAs(tenantId, userId, () -> {
            seeder.seed(pack, samples, slug);
            if (samples && extras.contains("rooms")) {
                seeder.seedExtraRooms();
            }
            return null;
        });
        if (samples) {
            // Everything created so far is sample data; "Remove the samples" removes rows created up to this moment.
            jdbc.update("UPDATE tenants SET settings = settings || jsonb_build_object('samplesUntil', clock_timestamp()) WHERE id = ?", tenantId);
        }
        return session(userId, tenantId, "OWNER", name);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> raw, jakarta.servlet.http.HttpServletRequest req) {
        Body b = new Body(raw);
        String account = "login:" + b.str("email").toLowerCase();
        throttle.check(account, req);
        String email = b.str("email").toLowerCase();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tenant_id, name, role, password_hash, active FROM users WHERE email = ?", email);
        if (rows.isEmpty()) {
            // Nobody in the business list. A control-panel person may have used this page by mistake: tell them where to go,
            // but only when the password is right, and never hand out a token. One bcrypt match either way.
            String adminAccount = "admin:" + email;
            throttle.check(adminAccount, req);
            List<Map<String, Object>> admins = jdbc.queryForList(
                    "SELECT password_hash FROM platform_admins WHERE email = ? AND active AND NOT invite_pending AND blocked_at IS NULL", email);
            boolean found = !admins.isEmpty() && admins.get(0).get("password_hash") != null;
            String hash = found ? (String) admins.get(0).get("password_hash") : dummyHash();
            if (encoder.matches(b.str("password"), hash) && found) {
                throttle.succeeded(adminAccount);
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "USE_CONTROL_PANEL",
                        "This is a control-panel account. Sign in on the control-panel page.");
            }
            throttle.failed(account, req);
            throttle.failed(adminAccount, null);
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "BAD_LOGIN", "Wrong email or password");
        }
        if (rows.get(0).get("password_hash") == null
                || !encoder.matches(b.str("password"), (String) rows.get(0).get("password_hash"))) {
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

    private final String dummyHash;

    /** A fixed hash nobody can match, so "no such email" costs the same bcrypt work as a real one. */
    private String dummyHash() {
        return dummyHash;
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

    /**
     * Staff sign in on their business's staff page (/w/<slug>) with their name and till PIN, no email needed. The name
     * is matched without case, in full or by first name; when two staff share a first name, the PIN decides (and if it
     * still matches both, they use their full name). Wrong tries are slowed down per name and per address.
     */
    @PostMapping("/staff-pin")
    public Map<String, Object> staffPin(@RequestBody Map<String, Object> raw, jakarta.servlet.http.HttpServletRequest req) {
        Body b = new Body(raw);
        String slug = b.str("workspace", "").trim().toLowerCase();
        String name = b.str("name", "").trim().replaceAll("\\s+", " ").toLowerCase();
        String pin = b.str("pin", "").trim();
        if (name.isEmpty() || !pin.matches("\\d{4,6}")) {
            throw ApiException.bad("Type your name and your PIN (4 to 6 numbers)");
        }
        String account = "staffpin:" + slug + ":" + name;
        throttle.check(account, req);
        List<Map<String, Object>> tenants = jdbc.queryForList("SELECT id, status FROM tenants WHERE slug = ?", slug);
        UUID tenantId = tenants.isEmpty() || "SUSPENDED".equals(tenants.get(0).get("status")) ? null : (UUID) tenants.get(0).get("id");
        List<Map<String, Object>> matched = tenantId == null ? List.of() : jdbc.queryForList("""
                SELECT id, name, role, pin_hash FROM users
                WHERE tenant_id = ? AND active AND pin_hash IS NOT NULL
                  AND (lower(regexp_replace(trim(name), '\\s+', ' ', 'g')) = ? OR lower(split_part(trim(name), ' ', 1)) = ?)""",
                tenantId, name, name).stream().filter(u -> encoder.matches(pin, (String) u.get("pin_hash"))).toList();
        if (matched.size() != 1) {
            throttle.failed(account, req);
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "BAD_PIN",
                    matched.isEmpty() ? "That name and PIN don't match. Check both, or ask the owner to set a new PIN."
                            : "Two staff share that name. Type your full name.");
        }
        throttle.succeeded(account);
        Map<String, Object> u = matched.get(0);
        return session((UUID) u.get("id"), tenantId, (String) u.get("role"), (String) u.get("name"));
    }

    /** Silent renewal: a signed-in till swaps its token for a fresh one before it expires. */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        refuseWhenSupport();
        TenantContext.Caller c = TenantContext.caller();
        return Map.of("token", jwt.issue(c.userId(), c.tenantId(), c.role(), c.name(), versions.current(c.userId(), false)));
    }

    /**
     * A LeziTech support sign-in must never be handed a plain workspace token. Such a token carries no grant, so it
     * would outlive the owner's 5 hours and its changes would stop being recorded as support. This sits on the one
     * place that mints those tokens, so it covers renewing, switching to another business (which also reached
     * businesses that never granted support anything) and anything added later that calls session().
     * A real support session never needs one: a grant is shorter than a token's life, so ask the owner again.
     */
    private void refuseWhenSupport() {
        if (SupportAccess.current().isPresent()) {
            throw ApiException.forbidden("A LeziTech support sign-in can't start or renew an ordinary session."
                    + " Ask the owner to give access again.");
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        TenantContext.Caller c = TenantContext.caller();
        return profile(c.userId(), c.tenantId());
    }

    public Map<String, Object> session(UUID userId, UUID tenantId, String role, String name) {
        refuseWhenSupport();
        jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", userId);
        Map<String, Object> out = profile(userId, tenantId);
        out.put("token", jwt.issue(userId, tenantId, effectiveRole(userId, role), name, versions.current(userId, false)));
        return out;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlanFeatures planFeatures;

    /** Someone the owner gave owner powers to (e.g. an operations manager) works as an owner, on plans that include it. */
    private String effectiveRole(UUID userId, String role) {
        Map<String, Object> u = jdbc.queryForMap("SELECT owner_powers, tenant_id FROM users WHERE id = ?", userId);
        return Boolean.TRUE.equals(u.get("owner_powers")) && planFeatures.has((UUID) u.get("tenant_id"), "ownerPowers") ? "OWNER" : role;
    }

    /**
     * LeziTech support signed in as the business under the owner's grant. The name shown everywhere (activity log
     * included) is "LeziTech support (name)", and the session ends with the grant. Doesn't touch the user's last sign-in.
     */
    public Map<String, Object> supportSession(UUID userId, UUID tenantId, String role, String supportName, UUID grant, Instant ends) {
        Map<String, Object> out = profile(userId, tenantId);
        out.put("token", jwt.issueSupport(userId, tenantId, role, "LeziTech support (" + supportName + ")",
                versions.current(userId, false), grant, ends));
        out.put("support", Map.of("until", ends.toString(), "by", supportName));
        return out;
    }

    /**
     * LeziTech support looking at a business with no grant: look only, at most 2 hours. The name shown everywhere is
     * "LeziTech support (name)". The server refuses every change made with this token.
     */
    public Map<String, Object> lookSession(UUID userId, UUID tenantId, String role, String supportName, UUID look, Instant ends) {
        Map<String, Object> out = profile(userId, tenantId);
        out.put("token", jwt.issueLook(userId, tenantId, role, "LeziTech support (" + supportName + ")",
                versions.current(userId, false), look, ends));
        out.put("support", Map.of("until", ends.toString(), "by", supportName, "lookOnly", true));
        return out;
    }

    private Map<String, Object> profile(UUID userId, UUID tenantId) {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT id, slug, name, sector, sub_sector, products, status, plan, trial_ends_at, paid_until,
                       brand_color, logo_url, currency, phone, display_key, is_demo, settings::text AS settings
                FROM tenants WHERE id = ?""", tenantId);
        Map<String, Object> u = new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT id, email, name, role, job_title, owner_powers, email_confirmed_at IS NOT NULL AS confirmed FROM users WHERE id = ?", userId));
        // The screens follow what they may do: owner powers open the owner's screens; their own job stays as baseRole.
        u.put("base_role", u.get("role"));
        if (Boolean.TRUE.equals(u.get("owner_powers")) && planFeatures.has(tenantId, "ownerPowers")) {
            u.put("role", "OWNER");
        }

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
        // The name this business sees for its plan, and the days of grace after a bill date (a banner, then the lock).
        tenant.put("planName", t.get("plan") == null ? null : platform.planName((String) t.get("sector"), (String) t.get("plan")));
        boolean pastBill = "ACTIVE".equals(status) && paidUntil != null && paidUntil.toInstant().isBefore(Instant.now());
        tenant.put("inGrace", pastBill && !TrialGuard.isLocked(status, trialEnds, paidUntil));
        tenant.put("graceEndsAt", pastBill ? paidUntil.toInstant().plus(TrialGuard.GRACE_DAYS, java.time.temporal.ChronoUnit.DAYS).toString() : null);
        tenant.put("brandColor", t.get("brand_color"));
        tenant.put("logoUrl", t.get("logo_url"));
        tenant.put("currency", t.get("currency"));
        tenant.put("phone", t.get("phone"));
        tenant.put("displayKey", t.get("display_key"));
        tenant.put("isDemo", t.get("is_demo"));
        // The plan's extra tools (all of them during the free days), so screens can show what needs an upgrade.
        tenant.put("planTools", planFeatures.allowed(tenantId));

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", u.get("id").toString());
        user.put("email", u.get("email"));
        user.put("emailConfirmed", Boolean.TRUE.equals(u.get("confirmed")));
        user.put("name", u.get("name"));
        user.put("role", u.get("role"));
        user.put("baseRole", u.get("base_role"));
        user.put("jobTitle", u.get("job_title"));
        user.put("ownerPowers", Boolean.TRUE.equals(u.get("owner_powers")));

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
