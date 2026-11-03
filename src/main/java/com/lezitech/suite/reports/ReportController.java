package com.lezitech.suite.reports;

import com.lezitech.suite.common.Db;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@Transactional(readOnly = true)
public class ReportController {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final String SOLD = " o.status IN ('PAID','CREDIT') AND o.kind <> 'QUOTE'";

    private final Db db;

    public ReportController(Db db) {
        this.db = db;
    }

    /** Every KPI any sector pack can ask for; the app shows the ones its pack lists. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Timestamp today = Timestamp.from(LocalDate.now(NAIROBI).atStartOfDay(NAIROBI).toInstant());
        Timestamp month = Timestamp.from(LocalDate.now(NAIROBI).withDayOfMonth(1).atStartOfDay(NAIROBI).toInstant());
        Map<String, Object> k = new LinkedHashMap<>();
        Map<String, Object> todayRow = db.one("Sales", "SELECT coalesce(sum(total), 0) AS sales, count(*) AS n, coalesce(avg(total), 0) AS avg FROM orders o WHERE "
                + SOLD + " AND o.closed_at >= :today", "today", today);
        k.put("sales_today", todayRow.get("sales"));
        k.put("transactions_today", todayRow.get("n"));
        k.put("avg_ticket", todayRow.get("avg"));
        k.put("sales_month", db.value("SELECT coalesce(sum(total), 0) FROM orders o WHERE " + SOLD + " AND o.closed_at >= :month",
                java.math.BigDecimal.class, "month", month));
        k.put("open_bills", db.value("SELECT count(*) FROM orders WHERE status = 'OPEN' AND kind = 'SALE'", Long.class));
        k.put("kitchen_queue", db.value("SELECT count(*) FROM order_lines WHERE kitchen_status IN ('NEW','PREPARING')", Long.class));
        k.put("low_stock", db.value("""
                SELECT count(*) FROM (SELECT i.id FROM items i LEFT JOIN stock_levels s ON s.item_id = i.id
                WHERE i.active AND i.track_stock GROUP BY i.id HAVING coalesce(sum(s.qty), 0) <= max(i.reorder_level)) x""", Long.class));
        k.put("expiring_soon", db.value("SELECT count(*) FROM stock_batches WHERE qty > 0 AND expiry_date <= current_date + 60", Long.class));
        k.put("receivables", db.value("SELECT coalesce(sum(balance), 0) FROM customers WHERE balance > 0", java.math.BigDecimal.class));
        k.put("open_quotes", db.value("SELECT count(*) FROM orders WHERE kind = 'QUOTE' AND status = 'OPEN'", Long.class));
        k.put("bookings_today", db.value("""
                SELECT count(*) FROM appointments WHERE starts_at >= :today AND starts_at < cast(:today as timestamptz) + interval '1 day'
                AND status <> 'CANCELLED'""", Long.class, "today", today));
        k.put("pipeline_value", db.value("""
                SELECT coalesce(sum(d.value), 0) FROM deals d JOIN pipeline_stages s ON s.id = d.stage_id
                WHERE NOT s.is_won AND NOT s.is_lost""", java.math.BigDecimal.class));
        k.put("won_this_month", db.value("""
                SELECT coalesce(sum(d.value), 0) FROM deals d JOIN pipeline_stages s ON s.id = d.stage_id
                WHERE s.is_won AND d.closed_at >= :month""", java.math.BigDecimal.class, "month", month));
        k.put("tasks_due", db.value("SELECT count(*) FROM activities WHERE NOT done AND due_at <= now() + interval '1 day'", Long.class));
        k.put("top_staff", db.find("""
                SELECT u.name, sum(l.line_total) AS amount FROM order_lines l JOIN orders o ON o.id = l.order_id
                JOIN users u ON u.id = coalesce(l.staff_id, o.user_id)
                WHERE """ + SOLD + " AND o.closed_at >= :today GROUP BY u.name ORDER BY 2 DESC LIMIT 1", "today", today).orElse(null));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", k);
        out.put("daily", db.list("""
                SELECT to_char(d, 'YYYY-MM-DD') AS day, coalesce(sum(o.total), 0) AS sales, count(o.id) AS orders
                FROM generate_series(current_date - 13, current_date, interval '1 day') d
                LEFT JOIN orders o ON """ + SOLD + """
                 AND (o.closed_at AT TIME ZONE 'Africa/Nairobi')::date = d::date
                GROUP BY d ORDER BY d"""));
        out.put("topItems", db.list("""
                SELECT l.name, sum(l.qty) AS qty, sum(l.line_total) AS amount FROM order_lines l JOIN orders o ON o.id = l.order_id
                WHERE """ + SOLD + " AND o.closed_at >= :month GROUP BY l.name ORDER BY amount DESC LIMIT 8", "month", month));
        out.put("recent", db.list("""
                SELECT o.id, o.number, o.kind, o.status, o.total, o.closed_at, o.created_at, c.name AS customer_name, t.label AS table_label
                FROM orders o LEFT JOIN customers c ON c.id = o.customer_id LEFT JOIN dining_tables t ON t.id = o.table_id
                ORDER BY o.created_at DESC LIMIT 8"""));
        return out;
    }

    @GetMapping("/sales")
    public Map<String, Object> sales(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate f = from == null ? LocalDate.now(NAIROBI).minusDays(29) : from;
        LocalDate t = to == null ? LocalDate.now(NAIROBI) : to;
        Instant start = f.atStartOfDay(NAIROBI).toInstant();
        Instant end = t.plusDays(1).atStartOfDay(NAIROBI).toInstant();
        Object[] range = {"from", Timestamp.from(start), "to", Timestamp.from(end)};
        String window = SOLD + " AND o.closed_at >= :from AND o.closed_at < :to";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("totals", db.one("Totals", """
                SELECT coalesce(sum(o.total), 0) AS sales, coalesce(sum(o.tax), 0) AS tax, coalesce(sum(o.discount), 0) AS discounts,
                       count(*) AS orders, coalesce(avg(o.total), 0) AS avg_ticket,
                       coalesce((SELECT sum(l.qty * i.cost) FROM order_lines l JOIN orders o2 ON o2.id = l.order_id JOIN items i ON i.id = l.item_id
                                 WHERE o2.status IN ('PAID','CREDIT') AND o2.kind <> 'QUOTE' AND o2.closed_at >= :from AND o2.closed_at < :to), 0) AS cost
                FROM orders o WHERE """ + window, range));
        out.put("byDay", db.list("""
                SELECT to_char((o.closed_at AT TIME ZONE 'Africa/Nairobi')::date, 'YYYY-MM-DD') AS day, sum(o.total) AS sales, count(*) AS orders
                FROM orders o WHERE """ + window + " GROUP BY 1 ORDER BY 1", range));
        out.put("byItem", db.list("""
                SELECT l.name, sum(l.qty) AS qty, sum(l.line_total) AS amount FROM order_lines l JOIN orders o ON o.id = l.order_id
                WHERE """ + window + " GROUP BY l.name ORDER BY amount DESC LIMIT 50", range));
        out.put("byMethod", db.list("""
                SELECT p.method, sum(p.amount) AS amount, count(*) AS count FROM payments p JOIN orders o ON o.id = p.order_id
                WHERE """ + window + " GROUP BY p.method ORDER BY amount DESC", range));
        out.put("byStaff", db.list("""
                SELECT u.name, count(DISTINCT o.id) AS orders, sum(l.line_total) AS amount
                FROM order_lines l JOIN orders o ON o.id = l.order_id JOIN users u ON u.id = coalesce(l.staff_id, o.user_id)
                WHERE """ + window + " GROUP BY u.name ORDER BY amount DESC", range));
        out.put("byCategory", db.list("""
                SELECT coalesce(c.name, 'Other') AS name, sum(l.line_total) AS amount FROM order_lines l JOIN orders o ON o.id = l.order_id
                LEFT JOIN items i ON i.id = l.item_id LEFT JOIN categories c ON c.id = i.category_id
                WHERE """ + window + " GROUP BY 1 ORDER BY amount DESC", range));
        out.put("byChannel", db.list("""
                SELECT o.channel, sum(o.total) AS amount, count(*) AS orders FROM orders o WHERE """ + window
                + " GROUP BY o.channel ORDER BY amount DESC", range));
        return out;
    }
}
