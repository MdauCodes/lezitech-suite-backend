package com.lezitech.suite.onboarding;

import com.lezitech.suite.admin.AdminContext;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Reach us" in one tap, on every screen: WhatsApp the LeziTech team, call them, or leave a question, feedback, a
 * problem, an idea or a call-back request. Messages land in the control panel's Messages list and the team's email.
 */
@RestController
public class ContactController {

    static final Set<String> KINDS = Set.of("QUESTION", "FEEDBACK", "PROBLEM", "IDEA", "CALLBACK");
    /** From one network when not signed in: 6 an hour. */
    static final RateLimit PUBLIC = new RateLimit(6, 3_600_000);
    static final RateLimit SIGNED_IN = new RateLimit(20, 3_600_000);

    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final String number;

    public ContactController(JdbcTemplate jdbc, Mailer mailer, @Value("${suite.support-whatsapp:}") String support) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        // The BizNez team's line (WhatsApp and calls); SUPPORT_WHATSAPP changes it.
        String digits = support == null ? "" : support.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            digits = "254796814154";
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            digits = "254" + digits.substring(1);
        }
        this.number = digits;
    }

    private String display() {
        return number.startsWith("254") && number.length() == 12
                ? "0" + number.substring(3, 6) + " " + number.substring(6, 9) + " " + number.substring(9) : "+" + number;
    }

    private String wa(String text) {
        return "https://wa.me/" + number + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** The team's numbers, for the button (also on the sign-in and sign-up pages). */
    @GetMapping("/api/public/contact")
    public Map<String, Object> numbers() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("whatsapp", number);
        out.put("call", "+" + number);
        out.put("display", display());
        return out;
    }

    /** From inside BizNez: who and where are filled in. {kind, message, page, phone}. */
    @PostMapping("/api/contact")
    public Map<String, Object> fromApp(@RequestBody Map<String, Object> raw) {
        TenantContext.Caller c = TenantContext.caller();
        if (!SIGNED_IN.allow(String.valueOf(c.userId()))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Lots of messages in the last hour. Please WhatsApp us instead.");
        }
        Map<String, Object> t = jdbc.queryForMap("SELECT name, phone FROM tenants WHERE id = ?", c.tenantId());
        Map<String, Object> u = jdbc.queryForMap("SELECT name, email FROM users WHERE id = ?", c.userId());
        Body b = new Body(raw);
        String phone = clip(b.str("phone", t.get("phone") == null ? null : t.get("phone").toString()), 30);
        return save(b, c.tenantId(), (String) t.get("name"), (String) u.get("name"), phone, (String) u.get("email"), c.role());
    }

    /** From the sign-in, sign-up or demo pages (not signed in). {kind, message, page, name, phone, email, business}. */
    @PostMapping("/api/public/contact")
    public Map<String, Object> fromPublic(@RequestBody Map<String, Object> raw, HttpServletRequest req) {
        String client = req.getHeader("X-Forwarded-For") != null ? req.getHeader("X-Forwarded-For").split(",")[0].trim() : req.getRemoteAddr();
        if (!PUBLIC.allow(client)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Lots of messages from here in the last hour. Please WhatsApp us instead.");
        }
        Body b = new Body(raw);
        String phone = clip(b.str("phone", ""), 30);
        if (phone.replaceAll("\\D", "").length() < 9) {
            throw ApiException.bad("Add a phone number we can call or WhatsApp you on");
        }
        return save(b, null, clip(b.str("business", null), 200), clip(b.str("name", null), 120), phone, clip(b.str("email", null), 200), null);
    }

    private Map<String, Object> save(Body b, UUID tenant, String business, String name, String phone, String email, String role) {
        String kind = b.str("kind", "QUESTION").toUpperCase();
        if (!KINDS.contains(kind)) {
            throw ApiException.bad("Choose what it's about");
        }
        String message = clip(b.str("message", "").trim(), 3000);
        if (message.isEmpty() && !"CALLBACK".equals(kind)) {
            throw ApiException.bad("Type your message");
        }
        if ("CALLBACK".equals(kind) && (phone == null || phone.replaceAll("\\D", "").length() < 9)) {
            throw ApiException.bad("Add the number we should call");
        }
        String page = clip(b.str("page", null), 200);
        UUID id = jdbc.queryForObject("""
                INSERT INTO team_messages (tenant_id, kind, message, page, business_name, contact_name, phone, email, role)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                tenant, kind, message.isEmpty() ? null : message, page, business, name, phone, email, role);
        String team = mailer.teamEmail();
        if (!team.isBlank()) {
            String line = label(kind) + " from " + (business == null ? "someone not signed in" : business) + (name != null ? " · " + name : "")
                    + (phone != null ? " · " + phone : "") + (page != null ? " · on " + page : "");
            mailer.send(team, "LeziTech team", label(kind) + ": " + (business == null ? (name == null ? "website visitor" : name) : business),
                    line + "\n\n" + message + "\n\n" + mailer.appUrl() + "/admin/messages",
                    "<p><b>" + Mailer.esc(line) + "</b></p><p>" + Mailer.esc(message).replace("\n", "<br>") + "</p><p><a href=\""
                            + mailer.appUrl() + "/admin/messages\">Open messages</a></p>");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("whatsapp", wa("Hi BizNez team, I've just sent a " + label(kind).toLowerCase() + (business != null ? " from " + business : "") + ": "
                + (message.length() > 300 ? message.substring(0, 300) + "..." : message)));
        return out;
    }

    static String label(String kind) {
        return switch (kind) {
            case "FEEDBACK" -> "Feedback";
            case "PROBLEM" -> "Problem";
            case "IDEA" -> "Feature idea";
            case "CALLBACK" -> "Call-back request";
            default -> "Question";
        };
    }

    // ---------- the team's list ----------

    @GetMapping("/api/admin/messages")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "OPEN") String status) {
        AdminContext.require("setup");
        String s = status.toUpperCase();
        return jdbc.queryForList("""
                SELECT m.*, t.slug AS tenant_slug FROM team_messages m LEFT JOIN tenants t ON t.id = m.tenant_id
                WHERE (? = 'ALL' OR (? = 'OPEN' AND m.status IN ('NEW', 'SEEN')) OR m.status = ?)
                ORDER BY (m.status = 'NEW') DESC, m.created_at DESC LIMIT 300""", s, s, s).stream().map(r -> {
            Map<String, Object> o = new LinkedHashMap<>();
            r.forEach((k, v) -> o.put(camel(k), v));
            return o;
        }).toList();
    }

    @PutMapping("/api/admin/messages/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("setup");
        Body b = new Body(raw);
        String status = b.has("status") ? b.str("status").toUpperCase() : null;
        if (status != null && !Set.of("NEW", "SEEN", "DONE").contains(status)) {
            throw ApiException.bad("Unknown status");
        }
        jdbc.update("""
                UPDATE team_messages SET status = coalesce(?, status), admin_note = coalesce(?, admin_note),
                       handled_at = CASE WHEN ? = 'DONE' THEN now() ELSE handled_at END WHERE id = ?""",
                status, clip(b.str("note", null), 2000), status, id);
        return Map.of("ok", true);
    }

    private static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return b.toString();
    }

    private static String clip(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }
}
