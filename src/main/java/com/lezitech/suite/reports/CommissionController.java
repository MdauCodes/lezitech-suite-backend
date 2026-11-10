package com.lezitech.suite.reports;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Commissions: what each stylist, barber, washer, trainer or mechanic earned on the sales credited to them, what has
 * been paid out, and what is still owed. A line earns the item's own rate if it has one, otherwise the staff
 * member's rate. Only paid and credit sales count; voided and refunded sales don't.
 */
@RestController
public class CommissionController {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final Set<String> METHODS = Set.of("CASH", "MPESA", "BANK");

    private final Db db;

    public CommissionController(Db db) {
        this.db = db;
    }

    @GetMapping("/api/reports/commissions")
    @Transactional(readOnly = true)
    public Map<String, Object> report(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate today = LocalDate.now(NAIROBI);
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate t = to == null ? today : to;
        if (t.isBefore(f)) {
            throw ApiException.bad("The end date is before the start date");
        }
        Object[] range = {"from", Timestamp.from(f.atStartOfDay(NAIROBI).toInstant()), "to", Timestamp.from(t.plusDays(1).atStartOfDay(NAIROBI).toInstant()),
                "pf", f, "pt", t};
        List<Map<String, Object>> staff = db.list("""
                WITH earned AS (
                  SELECT coalesce(l.staff_id, o.user_id) AS user_id, count(*) AS lines, count(DISTINCT o.id) AS sales,
                         coalesce(sum(l.line_total), 0) AS amount,
                         coalesce(sum(round(l.line_total * coalesce(i.commission_rate, u.commission_rate, 0) / 100, 2)), 0) AS commission
                  FROM order_lines l
                  JOIN orders o ON o.id = l.order_id
                  LEFT JOIN items i ON i.id = l.item_id
                  JOIN users u ON u.id = coalesce(l.staff_id, o.user_id)
                  WHERE o.status IN ('PAID', 'CREDIT') AND o.kind <> 'QUOTE' AND o.closed_at >= :from AND o.closed_at < :to
                  GROUP BY 1),
                paid AS (
                  SELECT user_id, sum(amount) AS paid FROM commission_payouts
                  WHERE period_from = :pf AND period_to = :pt GROUP BY 1)
                SELECT u.id AS user_id, u.name, u.role, u.commission_rate,
                       coalesce(e.lines, 0) AS lines, coalesce(e.sales, 0) AS sales, coalesce(e.amount, 0) AS amount,
                       coalesce(e.commission, 0) AS commission, coalesce(p.paid, 0) AS paid,
                       greatest(coalesce(e.commission, 0) - coalesce(p.paid, 0), 0) AS owed
                FROM users u
                LEFT JOIN earned e ON e.user_id = u.id
                LEFT JOIN paid p ON p.user_id = u.id
                WHERE u.tenant_id = :t AND (e.user_id IS NOT NULL OR u.commission_rate IS NOT NULL OR p.user_id IS NOT NULL)
                ORDER BY coalesce(e.commission, 0) DESC, u.name""", range);
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal owed = BigDecimal.ZERO;
        for (Map<String, Object> r : staff) {
            commission = commission.add((BigDecimal) r.get("commission"));
            paid = paid.add((BigDecimal) r.get("paid"));
            owed = owed.add((BigDecimal) r.get("owed"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("staff", staff);
        out.put("totals", Map.of("commission", commission, "paid", paid, "owed", owed));
        out.put("payouts", db.list("""
                SELECT p.id, p.user_id, u.name, p.amount, p.method, p.reference, p.note, p.created_at, pb.name AS paid_by
                FROM commission_payouts p JOIN users u ON u.id = p.user_id LEFT JOIN users pb ON pb.id = p.paid_by
                WHERE p.period_from = :pf AND p.period_to = :pt ORDER BY p.created_at DESC""", range));
        return out;
    }

    /** Record that a staff member was paid their commission for a period (cash, M-Pesa or bank). */
    @PostMapping("/api/reports/commissions/payouts")
    @Transactional
    public Map<String, Object> pay(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        UUID user = UUID.fromString(b.str("userId"));
        db.one("Staff member", "SELECT id FROM users WHERE id = :id AND tenant_id = :t", "id", user);
        LocalDate f = LocalDate.parse(b.str("from"));
        LocalDate t = LocalDate.parse(b.str("to"));
        BigDecimal amount = b.num("amount", null);
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.bad("Enter the amount paid");
        }
        String method = b.str("method", "CASH").toUpperCase();
        if (!METHODS.contains(method)) {
            throw ApiException.bad("Paid by cash, M-Pesa or bank");
        }
        UUID id = db.insert("""
                INSERT INTO commission_payouts (tenant_id, user_id, period_from, period_to, amount, method, reference, note, paid_by)
                VALUES (:t, :user, :pf, :pt, :amount, :method, :ref, :note, :u) RETURNING id""",
                "user", user, "pf", f, "pt", t, "amount", amount, "method", method,
                "ref", b.str("reference", null), "note", b.str("note", null));
        return Map.of("ok", true, "id", id);
    }
}
