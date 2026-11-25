package com.lezitech.suite.notify;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Messages from LeziTech to a business about what it pays or gets: an offer, free days, a plan change, a payment recorded by hand,
 * a pause, a price change. One call does all three things at once: the notice is saved (it shows on the business's Home and Billing
 * pages until it taps "Got it" and stays listed under "Messages from Busynez"), the owner is emailed in the Busynez card layout, and
 * a WhatsApp text is prepared for the business's number.
 *
 * There is no WhatsApp sender for LeziTech's own messages (the WhatsApp Cloud connection belongs to each business and sends its
 * customers' receipts), so WhatsApp is hand-sent like the trial reminders: the text and number are kept on the notice, the control
 * panel offers a "Send on WhatsApp" link, and the team marks it sent. The email goes out once, when the notice is made.
 */
@Service
public class TenantNotices {

    private static final Logger log = LoggerFactory.getLogger(TenantNotices.class);

    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final TransactionTemplate tx;

    public TenantNotices(JdbcTemplate jdbc, Mailer mailer, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.tx = new TransactionTemplate(txm);
    }

    /** Run as this business inside one transaction (the notices table is fenced by business). */
    private <T> T asTenant(UUID tenant, Supplier<T> work) {
        return TenantContext.runAs(tenant, null, () -> tx.execute(s -> work.get()));
    }

    /** Tell a business. Never throws for a failed email: the notice is saved and its email status says what happened. */
    public UUID send(UUID tenant, String kind, String title, String body, String by) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.name, t.phone,
                       (SELECT u.email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS email,
                       (SELECT u.name FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS owner
                FROM tenants t WHERE t.id = ?""", tenant);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Workspace");
        }
        Map<String, Object> t = rows.get(0);
        String email = (String) t.get("email");
        String phone = t.get("phone") == null || ((String) t.get("phone")).isBlank() ? null : (String) t.get("phone");
        String link = mailer.appUrl() + "/billing";
        String first = first((String) t.get("owner"));
        String wa = phone == null ? null : "Hi " + first + ", this is LeziTech. " + body + " Open Busynez: " + link;
        UUID id = asTenant(tenant, () -> jdbc.queryForObject("""
                INSERT INTO tenant_notices (tenant_id, kind, title, body, created_by, email_to, email_status, whatsapp_to, whatsapp_text)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?) RETURNING id""", UUID.class,
                tenant, kind, clip(title, 200), body, by, email, phone, wa));
        String status;
        try {
            String text = "Hi " + first + ",\n\n" + body + "\n\nOpen Billing to see it: " + link + "\n\nThank you,\nLeziTech";
            String html = EmailLayout.h(title, null) + EmailLayout.p("Hi " + Mailer.esc(first) + ",") + EmailLayout.p(Mailer.esc(body).replace("\n", "<br>"))
                    + EmailLayout.button("Open Billing", link, null);
            status = mailer.send(email, (String) t.get("owner"), "Busynez: " + title, text, html, body.length() > 110 ? body.substring(0, 110) : body);
        } catch (RuntimeException e) {
            log.warn("Notice email for {} failed: {}", tenant, e.toString());
            status = "FAILED";
        }
        String done = status;
        asTenant(tenant, () -> jdbc.update("UPDATE tenant_notices SET email_status = ?, sent_email_at = CASE WHEN ? = 'SENT' THEN now() END WHERE id = ?", done, done, id));
        return id;
    }

    /** The notices LeziTech sent this business, newest first, with when each was sent and whether the owner has seen it (control panel). */
    public List<Map<String, Object>> sentTo(UUID tenant) {
        return asTenant(tenant, () -> jdbc.queryForList("""
                SELECT id, kind, title, body, created_by, created_at, seen_at, sent_email_at, email_status, email_to, whatsapp_to, whatsapp_text, sent_whatsapp_at
                FROM tenant_notices WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 100""", tenant));
    }

    /** The team sent the WhatsApp text from their phone. */
    public void whatsappSent(UUID tenant, UUID id) {
        int n = asTenant(tenant, () -> jdbc.update("UPDATE tenant_notices SET sent_whatsapp_at = now() WHERE id = ? AND tenant_id = ? AND sent_whatsapp_at IS NULL AND whatsapp_to IS NOT NULL",
                id, tenant));
        if (n == 0) {
            throw ApiException.bad("There is no WhatsApp message waiting on this notice");
        }
    }

    // ---------- the business's own side (inside its request) ----------

    /** This business's notices for the app: only the ones not yet seen, or all of them. Only the signed-in business's rows are visible. */
    public List<Map<String, Object>> mine(boolean unseenOnly) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT id, kind, title, body, created_at, seen_at FROM tenant_notices
                WHERE tenant_id = ? AND (? = false OR seen_at IS NULL) ORDER BY created_at DESC LIMIT 100""", TenantContext.tenantId(), unseenOnly)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id").toString());
            m.put("kind", r.get("kind"));
            m.put("title", r.get("title"));
            m.put("body", r.get("body"));
            m.put("createdAt", ((Timestamp) r.get("created_at")).toInstant().toString());
            m.put("seenAt", r.get("seen_at") == null ? null : ((Timestamp) r.get("seen_at")).toInstant().toString());
            out.add(m);
        }
        return out;
    }

    /** "Got it". */
    public void gotIt(UUID id) {
        TenantContext.requireRole("OWNER");
        if (jdbc.update("UPDATE tenant_notices SET seen_at = coalesce(seen_at, now()) WHERE id = ? AND tenant_id = ?", id, TenantContext.tenantId()) == 0) {
            throw ApiException.notFound("Message");
        }
    }

    private static String first(String name) {
        return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
