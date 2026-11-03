package com.lezitech.suite.crm;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customers, suppliers and the sales pipeline (cf. Odoo crm.lead / crm.stage). */
@RestController
@RequestMapping("/api")
@Transactional
public class CrmController {

    private final Db db;

    public CrmController(Db db) {
        this.db = db;
    }

    // ---------- customers ----------

    @GetMapping("/customers")
    public List<Map<String, Object>> customers(@RequestParam(required = false) String q) {
        return db.list("""
                SELECT c.*,
                       (SELECT coalesce(sum(total), 0) FROM orders o WHERE o.customer_id = c.id AND o.status IN ('PAID','CREDIT')) AS lifetime_value,
                       (SELECT max(created_at) FROM orders o WHERE o.customer_id = c.id AND o.status IN ('PAID','CREDIT')) AS last_visit,
                       (SELECT count(*) FROM orders o WHERE o.customer_id = c.id AND o.status IN ('PAID','CREDIT')) AS visits
                FROM customers c
                WHERE cast(:q as text) IS NULL OR c.name ILIKE '%' || cast(:q as text) || '%' OR c.phone ILIKE '%' || cast(:q as text) || '%'
                ORDER BY c.name LIMIT 500""", "q", q == null || q.isBlank() ? null : q.trim());
    }

    @GetMapping("/customers/{id}")
    public Map<String, Object> customer(@PathVariable UUID id) {
        Map<String, Object> c = new LinkedHashMap<>(db.one("Customer", "SELECT * FROM customers WHERE id = :id", "id", id));
        c.put("orders", db.list("""
                SELECT id, number, kind, status, total, paid, created_at FROM orders WHERE customer_id = :id
                ORDER BY created_at DESC LIMIT 50""", "id", id));
        c.put("activities", db.list("""
                SELECT a.*, u.name AS user_name FROM activities a LEFT JOIN users u ON u.id = a.user_id
                WHERE a.customer_id = :id ORDER BY a.created_at DESC LIMIT 50""", "id", id));
        c.put("appointments", db.list("""
                SELECT a.*, i.name AS service_name FROM appointments a LEFT JOIN items i ON i.id = a.item_id
                WHERE a.customer_id = :id ORDER BY a.starts_at DESC LIMIT 20""", "id", id));
        return c;
    }

    @PostMapping("/customers")
    public Map<String, Object> createCustomer(@RequestBody Map<String, Object> raw) {
        UUID id = db.insert("INSERT INTO customers (tenant_id, name) VALUES (:t, :name) RETURNING id", "name", new Body(raw).str("name"));
        return updateCustomer(id, raw);
    }

    @PutMapping("/customers/{id}")
    public Map<String, Object> updateCustomer(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        db.exec("""
                UPDATE customers SET name = :name, phone = :phone, email = :email, company = :company, kind = :kind,
                       credit_limit = :credit, notes = :notes WHERE id = :id""",
                "id", id, "name", b.str("name"), "phone", b.str("phone", null), "email", b.str("email", null),
                "company", b.str("company", null), "kind", b.str("kind", "RETAIL"),
                "credit", b.num("creditLimit", BigDecimal.ZERO), "notes", b.str("notes", null));
        return db.one("Customer", "SELECT * FROM customers WHERE id = :id", "id", id);
    }

    /** Customer pays down what they owe on account. */
    @PostMapping("/customers/{id}/settle")
    public Map<String, Object> settle(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        BigDecimal amount = b.num("amount");
        if (amount.signum() <= 0) {
            throw ApiException.bad("Enter the amount received");
        }
        db.exec("UPDATE customers SET balance = balance - :a WHERE id = :id", "id", id, "a", amount);
        db.exec("""
                INSERT INTO activities (tenant_id, customer_id, kind, body, done, user_id)
                VALUES (:t, :id, 'NOTE', :body, true, :u)""",
                "id", id, "body", "Received " + amount.toPlainString() + " on account via " + b.str("method", "CASH")
                        + (b.has("reference") ? " (" + b.str("reference") + ")" : ""));
        return db.one("Customer", "SELECT * FROM customers WHERE id = :id", "id", id);
    }

    @PostMapping("/customers/import")
    public Map<String, Object> importCustomers(@RequestBody Map<String, Object> raw) {
        int n = 0;
        for (Map<String, Object> r : new Body(raw).list("rows")) {
            Body b = new Body(r);
            if (!b.has("name")) {
                continue;
            }
            db.exec("""
                    INSERT INTO customers (tenant_id, name, phone, email, company, kind, credit_limit)
                    VALUES (:t, :name, :phone, :email, :company, :kind, :credit)""",
                    "name", b.str("name"), "phone", b.str("phone", null), "email", b.str("email", null),
                    "company", b.str("company", null), "kind", b.str("kind", "RETAIL").toUpperCase(),
                    "credit", b.num("creditLimit", BigDecimal.ZERO));
            n++;
        }
        return Map.of("created", n);
    }

    // ---------- suppliers ----------

    @GetMapping("/suppliers")
    public List<Map<String, Object>> suppliers() {
        return db.list("SELECT * FROM suppliers ORDER BY name");
    }

    @PostMapping("/suppliers")
    public Map<String, Object> createSupplier(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        UUID id = db.insert("INSERT INTO suppliers (tenant_id, name, phone, email) VALUES (:t, :name, :phone, :email) RETURNING id",
                "name", b.str("name"), "phone", b.str("phone", null), "email", b.str("email", null));
        return db.one("Supplier", "SELECT * FROM suppliers WHERE id = :id", "id", id);
    }

    // ---------- pipeline ----------

    @GetMapping("/pipeline")
    public Map<String, Object> pipeline() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", db.list("SELECT * FROM pipeline_stages ORDER BY sort"));
        out.put("deals", db.list("""
                SELECT d.*, u.name AS owner_name, c.name AS customer_name, s.rot_days,
                       (now() - d.last_activity_at) > make_interval(days => s.rot_days) AND NOT s.is_won AND NOT s.is_lost AS rotting,
                       (SELECT min(due_at) FROM activities a WHERE a.deal_id = d.id AND NOT a.done) AS next_due
                FROM deals d JOIN pipeline_stages s ON s.id = d.stage_id
                LEFT JOIN users u ON u.id = d.owner_id LEFT JOIN customers c ON c.id = d.customer_id
                ORDER BY d.last_activity_at DESC"""));
        return out;
    }

    @PostMapping("/deals")
    public Map<String, Object> createDeal(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        UUID stage = b.has("stageId") ? UUID.fromString(b.str("stageId"))
                : db.value("SELECT id FROM pipeline_stages ORDER BY sort LIMIT 1", UUID.class);
        UUID id = db.insert("""
                INSERT INTO deals (tenant_id, title, stage_id, owner_id) VALUES (:t, :title, :stage, :u) RETURNING id""",
                "title", b.str("title"), "stage", stage);
        return updateDeal(id, raw);
    }

    @PutMapping("/deals/{id}")
    public Map<String, Object> updateDeal(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        db.exec("""
                UPDATE deals SET title = :title, contact_name = :contact, phone = :phone, email = :email, value = :value,
                       source = :source, expected_close = :close,
                       customer_id = :customerId, last_activity_at = now()
                WHERE id = :id""",
                "id", id, "title", b.str("title"), "contact", b.str("contactName", null), "phone", b.str("phone", null),
                "email", b.str("email", null), "value", b.num("value", BigDecimal.ZERO), "source", b.str("source", null),
                "close", b.date("expectedClose"),
                "customerId", b.has("customerId") ? UUID.fromString(b.str("customerId")) : null);
        return deal(id);
    }

    @PatchMapping("/deals/{id}/stage")
    public Map<String, Object> moveDeal(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        UUID stageId = UUID.fromString(b.str("stageId"));
        Map<String, Object> stage = db.one("Stage", "SELECT * FROM pipeline_stages WHERE id = :id", "id", stageId);
        boolean closed = Boolean.TRUE.equals(stage.get("isWon")) || Boolean.TRUE.equals(stage.get("isLost"));
        db.exec("""
                UPDATE deals SET stage_id = :stageId, last_activity_at = now(), lost_reason = :reason,
                       closed_at = CASE WHEN :closed THEN now() ELSE NULL END WHERE id = :id""",
                "id", id, "stageId", stageId, "closed", closed,
                "reason", Boolean.TRUE.equals(stage.get("isLost")) ? b.str("lostReason", "Not given") : null);
        // A won deal becomes a customer if it wasn't one already.
        Map<String, Object> d = db.one("Deal", "SELECT * FROM deals WHERE id = :id", "id", id);
        if (Boolean.TRUE.equals(stage.get("isWon")) && d.get("customerId") == null) {
            UUID customerId = db.insert("""
                    INSERT INTO customers (tenant_id, name, phone, email, kind) VALUES (:t, :name, :phone, :email, 'CORPORATE') RETURNING id""",
                    "name", d.get("contactName") != null ? d.get("contactName") : d.get("title"),
                    "phone", d.get("phone"), "email", d.get("email"));
            db.exec("UPDATE deals SET customer_id = :c WHERE id = :id", "id", id, "c", customerId);
        }
        db.exec("""
                INSERT INTO activities (tenant_id, deal_id, kind, body, done, user_id)
                VALUES (:t, :id, 'NOTE', :body, true, :u)""", "id", id, "body", "Moved to " + stage.get("name"));
        return deal(id);
    }

    @DeleteMapping("/deals/{id}")
    public void deleteDeal(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER", "SALES");
        db.exec("DELETE FROM deals WHERE id = :id", "id", id);
    }

    @GetMapping("/deals/{id}")
    public Map<String, Object> deal(@PathVariable UUID id) {
        Map<String, Object> d = new LinkedHashMap<>(db.one("Deal", """
                SELECT d.*, s.name AS stage_name, u.name AS owner_name, c.name AS customer_name
                FROM deals d JOIN pipeline_stages s ON s.id = d.stage_id LEFT JOIN users u ON u.id = d.owner_id
                LEFT JOIN customers c ON c.id = d.customer_id WHERE d.id = :id""", "id", id));
        d.put("activities", db.list("""
                SELECT a.*, u.name AS user_name FROM activities a LEFT JOIN users u ON u.id = a.user_id
                WHERE a.deal_id = :id ORDER BY a.created_at DESC""", "id", id));
        return d;
    }

    // ---------- activities ----------

    @GetMapping("/activities")
    public List<Map<String, Object>> myActivities() {
        return db.list("""
                SELECT a.*, d.title AS deal_title, c.name AS customer_name FROM activities a
                LEFT JOIN deals d ON d.id = a.deal_id LEFT JOIN customers c ON c.id = a.customer_id
                WHERE NOT a.done AND a.due_at IS NOT NULL ORDER BY a.due_at LIMIT 100""");
    }

    @PostMapping("/activities")
    public Map<String, Object> createActivity(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        UUID dealId = b.has("dealId") ? UUID.fromString(b.str("dealId")) : null;
        UUID id = db.insert("""
                INSERT INTO activities (tenant_id, deal_id, customer_id, kind, body, due_at, done, user_id)
                VALUES (:t, :dealId, :customerId, :kind, :body, :due, :done, :u) RETURNING id""",
                "dealId", dealId, "customerId", b.has("customerId") ? UUID.fromString(b.str("customerId")) : null,
                "kind", b.str("kind", "NOTE"), "body", b.str("body"),
                "due", b.has("dueAt") ? java.sql.Timestamp.from(b.instant("dueAt")) : null, "done", !b.has("dueAt"));
        if (dealId != null) {
            db.exec("UPDATE deals SET last_activity_at = now() WHERE id = :dealId", "dealId", dealId);
        }
        return db.one("Activity", "SELECT * FROM activities WHERE id = :id", "id", id);
    }

    @PostMapping("/activities/{id}/done")
    public Map<String, Object> done(@PathVariable UUID id) {
        db.exec("UPDATE activities SET done = true WHERE id = :id", "id", id);
        return db.one("Activity", "SELECT * FROM activities WHERE id = :id", "id", id);
    }
}
