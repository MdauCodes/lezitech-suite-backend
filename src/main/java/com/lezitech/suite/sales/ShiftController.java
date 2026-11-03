package com.lezitech.suite.sales;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Till sessions. Closing compares what the system recorded per payment method with what
 * was counted, and breaks it down per cashier (cf. Odoo pos_hr's per-employee closing).
 */
@RestController
@RequestMapping("/api/shifts")
@Transactional
public class ShiftController {

    private final Db db;

    public ShiftController(Db db) {
        this.db = db;
    }

    @GetMapping("/current")
    public Map<String, Object> current() {
        return db.find("SELECT * FROM shifts WHERE user_id = :u AND status = 'OPEN' LIMIT 1")
                .map(s -> summary(UUID.fromString(s.get("id").toString())))
                .orElse(Map.of("open", false));
    }

    @GetMapping
    public List<Map<String, Object>> recent() {
        return db.list("""
                SELECT s.*, u.name AS user_name,
                       (SELECT coalesce(sum(amount), 0) FROM payments p WHERE p.shift_id = s.id) AS takings
                FROM shifts s LEFT JOIN users u ON u.id = s.user_id ORDER BY s.opened_at DESC LIMIT 60""");
    }

    @PostMapping("/open")
    public Map<String, Object> open(@RequestBody Map<String, Object> raw) {
        if (db.find("SELECT id FROM shifts WHERE user_id = :u AND status = 'OPEN'").isPresent()) {
            throw ApiException.conflict("You already have an open till");
        }
        UUID id = db.insert("""
                INSERT INTO shifts (tenant_id, user_id, location_id, opening_cash)
                VALUES (:t, :u, (SELECT id FROM locations WHERE is_default LIMIT 1), :cash) RETURNING id""",
                "cash", new Body(raw).num("openingCash", BigDecimal.ZERO));
        return summary(id);
    }

    @PostMapping("/{id}/close")
    public Map<String, Object> close(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        db.exec("""
                UPDATE shifts SET status = 'CLOSED', closed_at = now(), counted_cash = :cash, counted_mpesa = :mpesa,
                       counted_card = :card, note = :note WHERE id = :id AND status = 'OPEN'""",
                "id", id, "cash", b.num("countedCash", null), "mpesa", b.num("countedMpesa", null),
                "card", b.num("countedCard", null), "note", b.str("note", null));
        return summary(id);
    }

    @GetMapping("/{id}")
    public Map<String, Object> summary(@PathVariable UUID id) {
        Map<String, Object> s = new LinkedHashMap<>(db.one("Shift", """
                SELECT s.*, u.name AS user_name FROM shifts s LEFT JOIN users u ON u.id = s.user_id WHERE s.id = :id""", "id", id));
        List<Map<String, Object>> byMethod = db.list("""
                SELECT method, sum(amount) AS amount, count(*) AS count FROM payments WHERE shift_id = :id GROUP BY method""", "id", id);
        BigDecimal cash = byMethod.stream().filter(m -> "CASH".equals(m.get("method")))
                .map(m -> (BigDecimal) m.get("amount")).findFirst().orElse(BigDecimal.ZERO);
        s.put("open", "OPEN".equals(s.get("status")));
        s.put("byMethod", byMethod);
        s.put("expectedCash", ((BigDecimal) s.get("openingCash")).add(cash));
        s.put("byCashier", db.list("""
                SELECT u.name AS user_name, p.method, sum(p.amount) AS amount
                FROM payments p LEFT JOIN users u ON u.id = p.user_id WHERE p.shift_id = :id
                GROUP BY u.name, p.method ORDER BY u.name, p.method""", "id", id));
        s.put("orders", db.value("SELECT count(*) FROM orders WHERE shift_id = :id AND status IN ('PAID','CREDIT')", Long.class, "id", id));
        return s;
    }
}
