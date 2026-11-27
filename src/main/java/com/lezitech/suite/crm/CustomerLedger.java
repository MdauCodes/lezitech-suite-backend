package com.lezitech.suite.crm;

import com.lezitech.suite.common.Db;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What each customer owes, line by line. Called right after customers.balance changes (a sale put on their account,
 * a payment towards it, a refund off it), so every line carries the balance after it.
 */
@Component
public class CustomerLedger {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final Db db;

    public CustomerLedger(Db db) {
        this.db = db;
    }

    /** {@code amount}: + they owe more, - they owe less. The customers row is already updated (and locked). */
    public void record(UUID customerId, String kind, BigDecimal amount, UUID orderId, String method, String reference, String note) {
        BigDecimal after = db.value("SELECT balance FROM customers WHERE id = :id", BigDecimal.class, "id", customerId);
        db.exec("""
                INSERT INTO customer_ledger (tenant_id, customer_id, kind, amount, balance_after, order_id, method, reference, note, user_id)
                VALUES (:t, :c, :kind, :amount, :after, :order, :method, :ref, :note, cast(:u as uuid))""",
                "c", customerId, "kind", kind, "amount", amount, "after", after, "order", orderId, "method", method,
                "ref", reference, "note", note);
    }

    /**
     * A statement for the dates: what they owed at the start (brought forward), each line, what they owe at the end,
     * and how old the unpaid amount is. Balances from before the ledger existed come in as "brought forward".
     */
    public Map<String, Object> statement(UUID customerId, LocalDate from, LocalDate to) {
        LocalDate t = to == null ? LocalDate.now(NAIROBI) : to;
        LocalDate f = from == null ? t.minusDays(89) : from;
        Timestamp start = Timestamp.from(f.atStartOfDay(NAIROBI).toInstant());
        Timestamp end = Timestamp.from(t.plusDays(1).atStartOfDay(NAIROBI).toInstant());
        Map<String, Object> c = db.one("Customer", "SELECT id, name, phone, email, company, balance, credit_limit FROM customers WHERE id = :id", "id", customerId);
        BigDecimal now = (BigDecimal) c.get("balance");
        // What they owed when the dates start: today's balance minus everything that happened since.
        BigDecimal since = db.value("SELECT coalesce(sum(amount), 0) FROM customer_ledger WHERE customer_id = :c AND created_at >= :s",
                BigDecimal.class, "c", customerId, "s", start);
        BigDecimal opening = now.subtract(since);
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal running = opening;
        BigDecimal charged = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        for (Map<String, Object> l : db.list("""
                SELECT g.kind, g.amount, g.method, g.reference, g.note, to_char(g.created_at AT TIME ZONE 'Africa/Nairobi', 'YYYY-MM-DD HH24:MI') AS at,
                       o.number AS order_number
                FROM customer_ledger g LEFT JOIN orders o ON o.id = g.order_id
                WHERE g.customer_id = :c AND g.created_at >= :s AND g.created_at < :e ORDER BY g.created_at, g.id""",
                "c", customerId, "s", start, "e", end)) {
            BigDecimal a = (BigDecimal) l.get("amount");
            running = running.add(a);
            if (a.signum() > 0) charged = charged.add(a); else paid = paid.add(a.negate());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("at", l.get("at"));
            line.put("kind", l.get("kind"));
            line.put("what", describe(l));
            line.put("charged", a.signum() > 0 ? a : null);
            line.put("paid", a.signum() < 0 ? a.negate() : null);
            line.put("balance", running);
            lines.add(line);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customer", c);
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("opening", opening);
        out.put("charged", charged);
        out.put("paid", paid);
        out.put("closing", running);
        out.put("lines", lines);
        out.put("ageing", ageing(customerId, now));
        return out;
    }

    private static String describe(Map<String, Object> l) {
        Object n = l.get("orderNumber");
        return switch (String.valueOf(l.get("kind"))) {
            case "SALE" -> "Sale #" + n + " on account";
            case "REFUND" -> l.get("note") != null ? String.valueOf(l.get("note")) : "Sale #" + n + " cancelled or refunded";
            default -> "Payment received" + (l.get("method") == null ? "" : " (" + method(String.valueOf(l.get("method"))) + ")")
                    + (l.get("reference") == null ? "" : " " + l.get("reference"));
        };
    }

    private static String method(String m) {
        return switch (m) {
            case "MPESA" -> "M-Pesa";
            case "CASH" -> "cash";
            case "CARD" -> "card";
            case "BANK" -> "bank";
            default -> m.toLowerCase();
        };
    }

    /**
     * How old what they owe is: payments pay off the oldest sales first, so what's left belongs to the newest sales.
     * Buckets: 0-30, 31-60, 61-90, over 90 days; plus the date of the oldest unpaid sale.
     */
    public Map<String, Object> ageing(UUID customerId, BigDecimal balance) {
        BigDecimal left = balance == null ? BigDecimal.ZERO : balance;
        BigDecimal[] b = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        String oldest = null;
        for (Map<String, Object> s : db.list("""
                SELECT amount, to_char((created_at AT TIME ZONE 'Africa/Nairobi')::date, 'YYYY-MM-DD') AS day,
                       current_date - (created_at AT TIME ZONE 'Africa/Nairobi')::date AS days
                FROM customer_ledger WHERE customer_id = :c AND kind = 'SALE' ORDER BY created_at DESC""", "c", customerId)) {
            if (left.signum() <= 0) break;
            BigDecimal part = left.min((BigDecimal) s.get("amount"));
            left = left.subtract(part);
            long days = ((Number) s.get("days")).longValue();
            int k = days <= 30 ? 0 : days <= 60 ? 1 : days <= 90 ? 2 : 3;
            b[k] = b[k].add(part);
            oldest = String.valueOf(s.get("day"));
        }
        // Owed from before the ledger began: counted as the oldest.
        if (left.signum() > 0) b[3] = b[3].add(left);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upTo30", b[0].setScale(2, RoundingMode.HALF_UP));
        out.put("days31to60", b[1].setScale(2, RoundingMode.HALF_UP));
        out.put("days61to90", b[2].setScale(2, RoundingMode.HALF_UP));
        out.put("over90", b[3].setScale(2, RoundingMode.HALF_UP));
        out.put("oldestUnpaid", oldest);
        return out;
    }
}
