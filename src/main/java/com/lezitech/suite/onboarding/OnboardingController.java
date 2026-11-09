package com.lezitech.suite.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lezitech.suite.admin.AdminContext;
import com.lezitech.suite.admin.AdminService;
import com.lezitech.suite.auth.AuthController;
import com.lezitech.suite.auth.LoginCodes;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.sector.SectorCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assisted onboarding, the main way businesses join:
 * 1. A business asks to be set up (public form): contact, kind of business, branches, staff, payments, hours.
 * 2. The LeziTech team calls them and creates the workspace from the control panel. It stays "in setup":
 *    no trial clock, no reminders, while the team adds their items, staff and M-Pesa.
 * 3. Hand-over: the 7-day trial starts and the owner gets a welcome email with a 6-digit code to set
 *    their password, plus a WhatsApp message the team can send.
 */
@RestController
public class OnboardingController {

    static final Set<String> STATUSES = Set.of("NEW", "CONTACTED", "IN_SETUP", "DONE", "LOST");
    static final Set<String> PAYMENTS = Set.of("CASH", "MPESA_TILL", "MPESA_PAYBILL", "CARD", "CREDIT", "BANK");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AuthController auth;
    private final AdminService admin;
    private final LoginCodes codes;
    private final Mailer mailer;
    private final SectorCatalog sectors;
    private final ObjectMapper json;
    private final int trialDays;
    /** Requests per address in the last hour, to keep bots from filling the list. */
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public OnboardingController(JdbcTemplate jdbc, AuthController auth, AdminService admin, LoginCodes codes, Mailer mailer,
                                SectorCatalog sectors, ObjectMapper json, @Value("${suite.trial-days}") int trialDays) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.admin = admin;
        this.codes = codes;
        this.mailer = mailer;
        this.sectors = sectors;
        this.json = json;
        this.trialDays = trialDays;
    }

    // ---------- 1. the business asks ----------

    @PostMapping("/api/public/setup-requests")
    public Map<String, Object> request(@RequestBody Map<String, Object> raw, HttpServletRequest req) {
        Body b = new Body(raw);
        if (!b.str("website", "").isBlank()) {
            return Map.of("ok", true); // honeypot field: only bots fill it
        }
        limit(req);
        String business = clip(b.str("businessName", "").trim(), 200);
        String contact = clip(b.str("contactName", "").trim(), 120);
        String phone = b.str("phone", "").replaceAll("[^0-9+]", "");
        if (business.isEmpty() || contact.isEmpty()) {
            throw ApiException.bad("Tell us your business name and your name");
        }
        if (phone.replaceAll("\\D", "").length() < 9) {
            throw ApiException.bad("Enter a phone number we can call or WhatsApp, like 0712 345 678");
        }
        String email = b.str("email", "").trim().toLowerCase();
        if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.bad("That email address doesn't look right");
        }
        String sector = b.str("sector", null);
        if (sector != null && !sector.equals("other")) {
            try {
                sectors.get(sector);
            } catch (RuntimeException e) {
                sector = "other";
            }
        }
        @SuppressWarnings("unchecked")
        List<String> pays = raw.get("payments") instanceof List<?> l ? ((List<Object>) l).stream().map(String::valueOf).filter(PAYMENTS::contains).toList() : List.of();
        UUID id = jdbc.queryForObject("""
                INSERT INTO setup_requests (business_name, sector, sub_sector, contact_name, phone, email, town, branches, staff, payments,
                                            current_tool, opening_hours, notes, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as text[]), ?, ?, ?, ?) RETURNING id""", UUID.class,
                business, sector, clip(b.str("subSector", null), 80), contact, phone, email.isEmpty() ? null : email,
                clip(b.str("town", null), 80), b.integer("branches", null), b.integer("staff", null), "{" + String.join(",", pays) + "}",
                clip(b.str("currentTool", null), 120), clip(b.str("openingHours", null), 120), clip(b.str("notes", null), 2000),
                clip(b.str("source", null), 120));
        String team = mailer.teamEmail();
        if (!team.isBlank()) {
            String line = business + " (" + (sector == null ? "kind not given" : sector) + ") · " + contact + " · " + phone
                    + (b.str("town", null) == null ? "" : " · " + b.str("town", null));
            mailer.send(team, "LeziTech team", "New setup request: " + business, line + "\n\n" + mailer.appUrl() + "/admin/setup",
                    "<p><b>New setup request</b></p><p>" + Mailer.esc(line) + "</p><p><a href=\"" + mailer.appUrl() + "/admin/setup\">Open setup requests</a></p>");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("whatsapp", waLink(mailer.support(), "Hi LeziTech, I've just asked for Lezi Suite to be set up for " + business + ". My name is " + contact + "."));
        return out;
    }

    // ---------- 2. the team works the list ----------

    @GetMapping("/api/admin/setup-requests")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "OPEN") String status) {
        AdminContext.require("setup");
        String s = status.toUpperCase();
        return jdbc.queryForList("""
                SELECT r.*, t.slug AS tenant_slug, t.in_setup AS tenant_in_setup FROM setup_requests r LEFT JOIN tenants t ON t.id = r.tenant_id
                WHERE (? = 'ALL' OR (? = 'OPEN' AND r.status IN ('NEW', 'CONTACTED', 'IN_SETUP')) OR r.status = ?)
                ORDER BY (r.status = 'NEW') DESC, r.created_at DESC LIMIT 300""", s, s, s).stream().map(this::clean).toList();
    }

    @PutMapping("/api/admin/setup-requests/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("setup");
        Body b = new Body(raw);
        String status = b.str("status", null);
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.bad("Unknown status");
        }
        int n = jdbc.update("""
                UPDATE setup_requests SET status = coalesce(?, status), admin_note = coalesce(?, admin_note), updated_at = now() WHERE id = ?""",
                status, b.str("adminNote", null), id);
        if (n == 0) {
            throw ApiException.notFound("Setup request");
        }
        admin.audit("setup.update", null, Map.of("request", id.toString(), "status", status == null ? "" : status));
        return clean(jdbc.queryForMap("SELECT * FROM setup_requests WHERE id = ?", id));
    }

    /**
     * Create a workspace for a business, usually from its setup request. The owner can't sign in yet (their
     * password is set with the welcome code at hand-over); the team works in it with "Open as".
     * Not one transaction: like signup, seeding runs inside the new workspace's own row-level-security context.
     */
    @PostMapping("/api/admin/workspaces")
    public Map<String, Object> create(@RequestBody Map<String, Object> raw) throws Exception {
        AdminContext.require("setup");
        Body b = new Body(raw);
        UUID requestId = b.has("requestId") && b.str("requestId", "").length() > 0 ? UUID.fromString(b.str("requestId")) : null;
        Map<String, Object> request = null;
        if (requestId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM setup_requests WHERE id = ?", requestId);
            if (rows.isEmpty()) {
                throw ApiException.notFound("Setup request");
            }
            request = rows.get(0);
            if (request.get("tenant_id") != null) {
                throw ApiException.conflict("A workspace was already made for this request");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>(raw);
        byte[] pw = new byte[24];
        RANDOM.nextBytes(pw);
        body.put("password", Base64.getUrlEncoder().encodeToString(pw)); // nobody knows it; replaced at hand-over
        body.putIfAbsent("withSamples", false);
        Map<String, Object> session = auth.createWorkspace(body, null);
        @SuppressWarnings("unchecked")
        UUID tenant = UUID.fromString(String.valueOf(((Map<String, Object>) session.get("tenant")).get("id")));

        // What the team learned on the call, kept with the workspace to tailor it.
        ObjectNode profile = json.createObjectNode();
        Map<String, Object> source = request != null ? request : raw;
        for (String[] k : new String[][]{{"town", "town"}, {"branches", "branches"}, {"staff", "staff"}, {"current_tool", "currentTool"},
                {"opening_hours", "openingHours"}, {"currentTool", "currentTool"}, {"openingHours", "openingHours"}}) {
            Object v = source.get(k[0]);
            if (v != null && !profile.has(k[1])) {
                profile.put(k[1], v.toString());
            }
        }
        Object pays = source.get("payments");
        if (pays instanceof java.sql.Array arr) {
            pays = List.of((Object[]) arr.getArray());
        }
        if (pays != null) {
            profile.set("payments", json.valueToTree(pays));
        }
        profile.put("requestId", requestId == null ? null : requestId.toString());
        // createWorkspace signs the owner in; they haven't really, so don't show a sign-in time.
        jdbc.update("UPDATE users SET last_login_at = NULL WHERE tenant_id = ?", tenant);
        jdbc.update("""
                UPDATE tenants SET in_setup = true, trial_ends_at = now() + interval '60 days',
                       settings = settings || jsonb_build_object('setupProfile', cast(? as jsonb)) WHERE id = ?""", profile.toString(), tenant);
        if (requestId != null) {
            jdbc.update("UPDATE setup_requests SET tenant_id = ?, status = 'IN_SETUP', updated_at = now() WHERE id = ?", tenant, requestId);
        }
        admin.audit("workspace.create", tenant, Map.of("business", b.str("businessName"), "sector", b.str("sector")));
        return Map.of("tenantId", tenant, "slug", jdbc.queryForObject("SELECT slug FROM tenants WHERE id = ?", String.class, tenant));
    }

    // ---------- 3. hand-over ----------

    /** Start the trial and send the owner a welcome email with a code to set their password. */
    @PostMapping("/api/admin/tenants/{id}/handover")
    @Transactional
    public Map<String, Object> handover(@PathVariable UUID id) {
        AdminContext.require("setup");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT name, slug, in_setup FROM tenants WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Workspace");
        }
        if (!Boolean.TRUE.equals(rows.get(0).get("in_setup"))) {
            throw ApiException.conflict("This workspace was already handed over. Use 'Send a new sign-in code' instead.");
        }
        jdbc.update("""
                UPDATE tenants SET in_setup = false, handed_over_at = now(), status = 'TRIAL',
                       trial_ends_at = now() + make_interval(days => ?) WHERE id = ?""", trialDays, id);
        jdbc.update("UPDATE setup_requests SET status = 'DONE', updated_at = now() WHERE tenant_id = ?", id);
        Map<String, Object> out = sendWelcome(id, true);
        admin.audit("workspace.handover", id, Map.of("email", out.get("emailStatus")));
        return out;
    }

    /** A fresh 6-digit code for the owner (lost the email, or it expired). */
    @PostMapping("/api/admin/tenants/{id}/welcome-code")
    @Transactional
    public Map<String, Object> resendCode(@PathVariable UUID id) {
        AdminContext.require("setup");
        Map<String, Object> out = sendWelcome(id, false);
        admin.audit("workspace.welcome_code", id, Map.of("email", out.get("emailStatus")));
        return out;
    }

    private Map<String, Object> sendWelcome(UUID tenant, boolean first) {
        Map<String, Object> t = jdbc.queryForMap("SELECT name, slug, phone FROM tenants WHERE id = ?", tenant);
        List<Map<String, Object>> owners = jdbc.queryForList(
                "SELECT id, name, email FROM users WHERE tenant_id = ? AND role = 'OWNER' AND active ORDER BY created_at LIMIT 1", tenant);
        if (owners.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_OWNER", "This workspace has no active owner to send the code to");
        }
        Map<String, Object> o = owners.get(0);
        String code = codes.issue((UUID) o.get("id"), "WELCOME", LoginCodes.WELCOME_TTL);
        String business = (String) t.get("name");
        String email = (String) o.get("email");
        String name = first((String) o.get("name"));
        String setPassword = mailer.appUrl() + "/reset?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
        String signIn = mailer.appUrl() + "/w/" + t.get("slug");
        String subject = first ? "Your Lezi Suite for " + business + " is ready" : "Your Lezi Suite sign-in code";
        String text = "Hi " + name + ",\n\n" + (first ? "Lezi Suite for " + business + " is set up and ready. Your 7-day free trial starts today.\n\n" : "")
                + "1. Open " + setPassword + "\n2. Enter the code " + code + " (it works for 3 days) and choose your password.\n"
                + "3. After that, sign in any time at " + signIn + " with " + email + ".\n\n"
                + (mailer.support().isBlank() ? "" : "Questions? WhatsApp or call us on " + mailer.support() + ".\n\n") + "Karibu,\nLeziTech";
        String html = "<p>Hi " + Mailer.esc(name) + ",</p>"
                + (first ? "<p>Lezi Suite for <b>" + Mailer.esc(business) + "</b> is set up and ready. Your 7-day free trial starts today.</p>" : "")
                + "<p>Your code to set your password:</p><p style=\"font-size:28px;font-weight:bold;letter-spacing:6px;margin:8px 0\">" + code + "</p>"
                + "<p><a href=\"" + setPassword + "\" style=\"display:inline-block;background:#101415;color:#ffffff;padding:10px 16px;border-radius:8px;text-decoration:none\">Set my password</a></p>"
                + "<p>The code works for 3 days. After that, sign in any time at <a href=\"" + signIn + "\">" + Mailer.esc(signIn) + "</a> with " + Mailer.esc(email) + ".</p>"
                + "<p>Karibu,<br>LeziTech</p>";
        String status = mailer.send(email, (String) o.get("name"), subject, text, html);
        String wa = "Hi " + name + ", this is LeziTech. " + (first ? "Lezi Suite for " + business + " is ready and your 7-day free trial starts today. " : "")
                + "To set your password, open " + setPassword + " and enter the code " + code + ". Then sign in at " + signIn + ". Any questions, reply here.";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emailStatus", status);
        out.put("email", email);
        out.put("whatsappText", wa);
        out.put("whatsappLink", waLink((String) t.get("phone"), wa));
        return out;
    }

    // ---------- helpers ----------

    private void limit(HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        Deque<Instant> d = recent.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (d) {
            Instant hourAgo = Instant.now().minusSeconds(3600);
            while (!d.isEmpty() && d.peekFirst().isBefore(hourAgo)) {
                d.pollFirst();
            }
            if (d.size() >= 5) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY", "We already have your request. We'll call you soon, or WhatsApp us.");
            }
            d.addLast(Instant.now());
        }
    }

    /** wa.me link to a Kenyan number (07..., 01..., +254...), or null without a usable number. */
    static String waLink(String phone, String text) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        if (d.startsWith("0") && d.length() == 10) {
            d = "254" + d.substring(1);
        }
        if (!d.matches("254[0-9]{9}")) {
            return null;
        }
        return "https://wa.me/" + d + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            Object val = v;
            if (v instanceof java.sql.Array a) {
                try {
                    val = List.of((Object[]) a.getArray());
                } catch (Exception e) {
                    val = List.of();
                }
            }
            m.put(camel(k), val);
        });
        return m;
    }

    private static String camel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String first(String name) {
        return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
    }
}
