package com.lezitech.suite.property.portal;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.lezitech.suite.notify.Mailer.esc;

/**
 * Complaints and repairs (owner's decisions, 2026-11-13). A tenant reports a problem from the portal (with photos) and
 * follows it: received, given to someone, being fixed, done. The owner and managers see everything; a caretaker or
 * technician (role TECHNICIAN) sees only the jobs given to them and marks them done with a photo. The owner can record
 * the cost and charge it to the tenant (a bill on their lease). When it's done the tenant can rate the fix.
 * Callers run inside the workspace's transaction.
 */
@Service
public class Maintenance {

    static final Set<String> CATEGORIES = Set.of("REPAIR", "WATER", "POWER", "SECURITY", "CLEANING", "NOISE", "OTHER");
    static final Set<String> STATUSES = Set.of("NEW", "ASSIGNED", "IN_PROGRESS", "DONE", "CLOSED", "CANCELLED");

    private final Db db;
    private final FileService files;
    private final OrderService orders;
    private final Mailer mailer;

    public Maintenance(Db db, FileService files, OrderService orders, Mailer mailer) {
        this.db = db;
        this.files = files;
        this.orders = orders;
        this.mailer = mailer;
    }

    /** A new request. {@code leaseId} null = a common area (staff only). Returns the request. */
    public Map<String, Object> create(UUID leaseId, Body b, String reportedBy, String reporterName) {
        String title = b.str("title", "").trim();
        if (title.isEmpty() || title.length() > 150) {
            throw ApiException.bad("Say in a few words what's wrong (up to 150 letters)");
        }
        String category = b.str("category", "REPAIR").toUpperCase();
        if (!CATEGORIES.contains(category)) {
            category = "OTHER";
        }
        String priority = "URGENT".equalsIgnoreCase(b.str("priority", "NORMAL")) ? "URGENT" : "NORMAL";
        UUID space = null;
        UUID customer = null;
        if (leaseId != null) {
            Map<String, Object> l = db.one("Lease", "SELECT space_id, customer_id FROM leases WHERE id = :id", "id", leaseId);
            space = UUID.fromString(l.get("spaceId").toString());
            customer = UUID.fromString(l.get("customerId").toString());
        }
        Integer number = db.value("SELECT coalesce(max(number), 0) + 1 FROM maintenance_requests WHERE tenant_id = :t", Integer.class);
        String details = b.str("details", null);
        UUID id = db.insert("""
                INSERT INTO maintenance_requests (tenant_id, number, lease_id, space_id, customer_id, category, title, details, priority, reported_by, reporter_name)
                VALUES (:t, :n, :lease, :space, :customer, :cat, :title, :details, :prio, :by, :name) RETURNING id""",
                "n", number, "lease", leaseId, "space", space, "customer", customer, "cat", category, "title", title,
                "details", details == null || details.isBlank() ? null : details.trim(), "prio", priority, "by", reportedBy, "name", reporterName);
        update(id, reportedBy, reporterName, details == null || details.isBlank() ? "Reported" : details.trim(), "NEW", List.of(), false);
        if ("TENANT".equals(reportedBy)) {
            tellOwner(id);
        }
        return detail(id, false);
    }

    private void update(UUID id, String kind, String name, String message, String status, List<UUID> photos, boolean internal) {
        db.exec("""
                INSERT INTO maintenance_updates (tenant_id, request_id, author_kind, author_name, message, status, photos, internal)
                VALUES (:t, :r, :k, :n, :m, :s, cast(:p as jsonb), :i)""",
                "r", id, "k", kind, "n", name, "m", message, "s", status, "p", db.toJson(photos.stream().map(UUID::toString).toList()), "i", internal);
        db.exec("UPDATE maintenance_requests SET updated_at = now() WHERE id = :id", "id", id);
    }

    /** One request with its history. For the tenant, staff-only notes are left out. */
    public Map<String, Object> detail(UUID id, boolean forTenant) {
        Map<String, Object> r = new LinkedHashMap<>(db.one("Request", """
                SELECT m.*, sp.name AS unit_name, c.name AS tenant_name, c.phone AS tenant_phone, u.name AS assigned_name
                FROM maintenance_requests m LEFT JOIN spaces sp ON sp.id = m.space_id LEFT JOIN customers c ON c.id = m.customer_id
                LEFT JOIN users u ON u.id = m.assigned_to WHERE m.id = :id""", "id", id));
        r.put("updates", db.list("SELECT author_kind, author_name, message, status, photos, internal, created_at FROM maintenance_updates WHERE request_id = :id"
                + (forTenant ? " AND NOT internal" : "") + " ORDER BY created_at", "id", id));
        if (forTenant) {
            r.remove("cost");
            r.remove("assignedTo");
        }
        return r;
    }

    public List<Map<String, Object>> list(String where, Object... params) {
        return db.list("""
                SELECT m.id, m.number, m.title, m.category, m.priority, m.status, m.created_at, m.updated_at, m.done_at, m.rating, m.cost,
                       m.lease_id, m.assigned_to, sp.name AS unit_name, c.name AS tenant_name, u.name AS assigned_name, m.reported_by,
                       (SELECT count(*) FROM maintenance_updates x WHERE x.request_id = m.id AND jsonb_array_length(x.photos) > 0) AS photo_updates
                FROM maintenance_requests m LEFT JOIN spaces sp ON sp.id = m.space_id LEFT JOIN customers c ON c.id = m.customer_id
                LEFT JOIN users u ON u.id = m.assigned_to WHERE """ + " " + where + " " + """
                 ORDER BY CASE WHEN m.status IN ('DONE','CLOSED','CANCELLED') THEN 1 ELSE 0 END, m.priority = 'URGENT' DESC, m.created_at DESC LIMIT 300""", params);
    }

    /**
     * The owner, a manager or the technician moves a request on: status, who it's given to, priority, cost, a message
     * (to the tenant, or a staff-only note). The tenant is emailed about what they can see.
     */
    public Map<String, Object> staffUpdate(UUID id, Body b, String staffName, boolean technician) {
        Map<String, Object> r = db.one("Request", "SELECT * FROM maintenance_requests WHERE id = :id FOR UPDATE", "id", id);
        String status = b.has("status") ? b.str("status").toUpperCase() : null;
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.bad("Unknown status");
        }
        if (technician && status != null && !Set.of("IN_PROGRESS", "DONE").contains(status)) {
            throw ApiException.forbidden("You can mark a job as being fixed or done");
        }
        if (!technician && b.has("assignedTo")) {
            String to = b.str("assignedTo", "");
            UUID user = to.isBlank() ? null : UUID.fromString(to);
            if (user != null) {
                db.one("Staff member", "SELECT id FROM users WHERE id = :u AND tenant_id = :t AND active", "u", user);
            }
            db.exec("UPDATE maintenance_requests SET assigned_to = :u WHERE id = :id", "id", id, "u", user);
            if (user != null && status == null && "NEW".equals(r.get("status"))) {
                status = "ASSIGNED";
            }
        }
        if (!technician && b.has("priority")) {
            db.exec("UPDATE maintenance_requests SET priority = :p WHERE id = :id", "id", id, "p", "URGENT".equalsIgnoreCase(b.str("priority")) ? "URGENT" : "NORMAL");
        }
        if (!technician && b.has("cost")) {
            BigDecimal cost = b.num("cost", BigDecimal.ZERO);
            if (cost.signum() < 0) {
                throw ApiException.bad("The cost can't be negative");
            }
            db.exec("UPDATE maintenance_requests SET cost = :c WHERE id = :id", "id", id, "c", cost.signum() == 0 ? null : cost);
        }
        if (status != null) {
            db.exec("UPDATE maintenance_requests SET status = :s, done_at = CASE WHEN :s IN ('DONE','CLOSED') THEN coalesce(done_at, now()) ELSE done_at END WHERE id = :id",
                    "id", id, "s", status);
        }
        String message = b.str("message", "").trim();
        boolean internal = b.bool("internal", false);
        if (status != null || !message.isEmpty()) {
            update(id, "STAFF", staffName, message.isEmpty() ? null : message, status, List.of(), internal);
        }
        if (!internal && (status != null || !message.isEmpty())) {
            tellTenant(id, status, message.isEmpty() ? null : message);
        }
        return detail(id, false);
    }

    /** A photo added to a request (by the tenant or staff). */
    public Map<String, Object> addPhoto(UUID id, byte[] bytes, String kind, String name, String caption) {
        db.one("Request", "SELECT id FROM maintenance_requests WHERE id = :id", "id", id);
        UUID file = files.save(FileService.Purpose.REPAIR_PHOTO, bytes);
        update(id, kind, name, caption == null || caption.isBlank() ? null : caption.trim(), null, List.of(file), false);
        return detail(id, "TENANT".equals(kind));
    }

    /** A message from the tenant about their own request. */
    public Map<String, Object> tenantMessage(UUID id, String name, String message) {
        if (message == null || message.isBlank()) {
            throw ApiException.bad("Write a message");
        }
        Map<String, Object> r = db.one("Request", "SELECT status FROM maintenance_requests WHERE id = :id", "id", id);
        String reopen = "DONE".equals(r.get("status")) ? "IN_PROGRESS" : null;
        if (reopen != null) {
            db.exec("UPDATE maintenance_requests SET status = 'IN_PROGRESS' WHERE id = :id", "id", id);
        }
        update(id, "TENANT", name, message.trim(), reopen, List.of(), false);
        return detail(id, true);
    }

    /** The tenant rates a finished job (1–5), which closes it. */
    public Map<String, Object> rate(UUID id, int rating, String comment) {
        Map<String, Object> r = db.one("Request", "SELECT status FROM maintenance_requests WHERE id = :id FOR UPDATE", "id", id);
        if (!"DONE".equals(r.get("status"))) {
            throw ApiException.conflict("You can rate it once it's marked done");
        }
        if (rating < 1 || rating > 5) {
            throw ApiException.bad("Rate from 1 to 5");
        }
        db.exec("UPDATE maintenance_requests SET rating = :r, rating_comment = :c, status = 'CLOSED' WHERE id = :id",
                "id", id, "r", rating, "c", comment == null || comment.isBlank() ? null : comment.trim());
        return detail(id, true);
    }

    /** Charge the repair to the tenant: a bill on their lease (shows on their statement and is paid like rent). */
    public Map<String, Object> charge(UUID id, BigDecimal amount, String what) {
        Map<String, Object> r = db.one("Request", "SELECT * FROM maintenance_requests WHERE id = :id FOR UPDATE", "id", id);
        if (r.get("leaseId") == null) {
            throw ApiException.bad("This request isn't for a tenant's unit, so there's no one to charge");
        }
        if (r.get("chargedOrderId") != null) {
            throw ApiException.conflict("This repair was already charged to the tenant");
        }
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.bad("Enter the amount to charge");
        }
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("kind", "INVOICE");
        inv.put("channel", "REPAIR");
        inv.put("customerId", r.get("customerId"));
        inv.put("dueOn", java.time.LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")).plusDays(7).toString());
        inv.put("lines", List.of(Map.of("name", (what == null || what.isBlank() ? "Repair" : what.trim()) + " · request #" + r.get("number"),
                "unitPrice", amount.setScale(2, RoundingMode.HALF_UP), "qty", 1, "taxRate", 0)));
        UUID order = orders.createTrusted(new Body(inv));
        db.exec("UPDATE orders SET lease_id = :l WHERE id = :o", "l", r.get("leaseId"), "o", order);
        db.exec("UPDATE maintenance_requests SET charged_order_id = :o, cost = coalesce(cost, :a) WHERE id = :id", "id", id, "o", order, "a", amount);
        update(id, "STAFF", TenantContext.caller().name(), "Charged to you: " + amount.setScale(0, RoundingMode.HALF_UP).toPlainString() + " (on your statement)", null, List.of(), false);
        return detail(id, false);
    }

    /** Is this file a photo on one of this customer's requests? (Tenants see only their own.) */
    public boolean photoBelongsToCustomer(UUID fileId, UUID customerId) {
        return Boolean.TRUE.equals(db.value("""
                SELECT EXISTS (SELECT 1 FROM maintenance_updates x JOIN maintenance_requests m ON m.id = x.request_id
                               WHERE m.customer_id = :c AND NOT x.internal AND x.photos @> cast(:f as jsonb))""",
                Boolean.class, "c", customerId, "f", db.toJson(List.of(fileId.toString()))));
    }

    public boolean photoOfJobFor(UUID fileId, UUID userId) {
        return Boolean.TRUE.equals(db.value("""
                SELECT EXISTS (SELECT 1 FROM maintenance_updates x JOIN maintenance_requests m ON m.id = x.request_id
                               WHERE m.assigned_to = :u AND x.photos @> cast(:f as jsonb))""",
                Boolean.class, "u", userId, "f", db.toJson(List.of(fileId.toString()))));
    }

    // ---------------- emails ----------------

    private void tellOwner(UUID id) {
        Map<String, Object> r = detail(id, false);
        List<Map<String, Object>> owners = db.list("SELECT email, name FROM users WHERE tenant_id = :t AND role IN ('OWNER','MANAGER') AND active AND email IS NOT NULL");
        String subject = ("URGENT".equals(r.get("priority")) ? "Urgent: " : "") + "New problem reported at " + r.get("unitName") + ": " + r.get("title");
        String text = r.get("tenantName") + " (" + r.get("unitName") + ") reported: " + r.get("title")
                + (r.get("details") == null ? "" : "\n\n" + r.get("details")) + "\n\nOpen Rent & tenants, Repairs: " + mailer.appUrl() + "/property";
        for (Map<String, Object> o : owners) {
            mailer.send((String) o.get("email"), (String) o.get("name"), subject, text,
                    "<p><b>" + esc(String.valueOf(r.get("tenantName"))) + "</b> (" + esc(String.valueOf(r.get("unitName"))) + ") reported:</p><p><b>"
                            + esc((String) r.get("title")) + "</b></p>" + (r.get("details") == null ? "" : "<p>" + esc((String) r.get("details")) + "</p>")
                            + "<p><a href=\"" + esc(mailer.appUrl() + "/property") + "\">Open Rent &amp; tenants, Repairs</a></p>");
        }
    }

    private void tellTenant(UUID id, String status, String message) {
        Map<String, Object> r = db.one("Request", """
                SELECT m.number, m.title, m.customer_id, a.email, a.name, t.name AS business, t.slug, t.brand_color
                FROM maintenance_requests m JOIN tenants t ON t.id = m.tenant_id
                LEFT JOIN renter_accounts a ON a.customer_id = m.customer_id AND a.tenant_id = m.tenant_id AND a.active
                WHERE m.id = :id""", "id", id);
        String to = (String) r.get("email");
        if (to == null) {
            return;
        }
        String what = status == null ? "There's a new message" : switch (status) {
            case "ASSIGNED" -> "Someone has been given the job";
            case "IN_PROGRESS" -> "It's being fixed";
            case "DONE" -> "It's marked as done. Please check, and rate the fix on your tenant page";
            case "CANCELLED" -> "It was closed without a repair";
            default -> "It was updated";
        };
        String business = (String) r.get("business");
        String link = mailer.appUrl() + "/t/" + r.get("slug");
        mailer.trySend(to, (String) r.get("name"), "Update on your request #" + r.get("number") + ": " + r.get("title"),
                what + "." + (message == null ? "" : "\n\n\"" + message + "\"") + "\n\nSee it on your tenant page: " + link + "\n\n" + business,
                RenterAuth.branded((String) r.get("brandColor"), business, "<p>Your request <b>#" + r.get("number") + " · " + esc((String) r.get("title"))
                        + "</b>: " + esc(what) + ".</p>" + (message == null ? "" : "<p style=\"background:#f6f7f3;border-radius:10px;padding:12px 14px\">" + esc(message) + "</p>")
                        + RenterAuth.button((String) r.get("brandColor"), link, "Open your tenant page")));
    }
}
