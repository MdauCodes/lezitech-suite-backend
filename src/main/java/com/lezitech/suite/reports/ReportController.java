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
    /** Completed sales. A landlord's deposit bills are money held for the tenant, not income, so they're left out. */
    private static final String SOLD = " o.status IN ('PAID','CREDIT') AND o.kind <> 'QUOTE' AND o.channel <> 'DEPOSIT'";

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
        // Hotels
        Long rooms = db.value("SELECT count(*) FROM spaces WHERE active AND kind IN ('ROOM','COTTAGE','TENT','APARTMENT')", Long.class);
        Long occupied = db.value("SELECT count(*) FROM stays WHERE status = 'IN_HOUSE'", Long.class);
        k.put("occupancy_today", rooms == null || rooms == 0 ? "—" : Math.round(100.0 * occupied / rooms) + "% (" + occupied + "/" + rooms + ")");
        k.put("arrivals_today", db.value("SELECT count(*) FROM stays WHERE status = 'RESERVED' AND check_in <= current_date", Long.class));
        k.put("departures_today", db.value("SELECT count(*) FROM stays WHERE status = 'IN_HOUSE' AND check_out <= current_date", Long.class));
        // Tours
        k.put("departures_next_30", db.value("SELECT count(*) FROM trips WHERE status IN ('OPEN','FULL') AND starts_on BETWEEN current_date AND current_date + 30", Long.class));
        k.put("seats_booked", db.value("""
                SELECT coalesce(sum(b.pax), 0) FROM trip_bookings b JOIN trips t ON t.id = b.trip_id
                WHERE b.status <> 'CANCELLED' AND t.starts_on >= current_date""", Long.class));
        k.put("balances_due", db.value("SELECT coalesce(sum(total - paid), 0) FROM orders WHERE status = 'OPEN' AND channel IN ('TOUR','ROOM')", java.math.BigDecimal.class));
        // Property
        Long units = db.value("SELECT count(*) FROM spaces WHERE active AND kind IN ('SHOP','STALL','OFFICE','APARTMENT')", Long.class);
        Long let = db.value("SELECT count(DISTINCT space_id) FROM leases WHERE status = 'ACTIVE'", Long.class);
        k.put("occupancy_rate", units == null || units == 0 ? "—" : Math.round(100.0 * let / units) + "% (" + let + "/" + units + ")");
        String period = java.time.YearMonth.now(NAIROBI).toString();
        k.put("rent_billed_month", db.value("SELECT coalesce(sum(total), 0) FROM orders WHERE period = :p AND status <> 'VOID'", java.math.BigDecimal.class, "p", period));
        k.put("rent_collected_month", db.value("SELECT coalesce(sum(paid), 0) FROM orders WHERE period = :p AND status <> 'VOID'", java.math.BigDecimal.class, "p", period));
        k.put("arrears", db.value("SELECT coalesce(sum(total - paid), 0) FROM orders WHERE lease_id IS NOT NULL AND status = 'OPEN' AND due_on < current_date", java.math.BigDecimal.class));
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
                SELECT p.method, sum(p.amount) AS amount, count(*) FILTER (WHERE p.kind = 'PAYMENT') AS count
                FROM payments p WHERE p.created_at >= :from AND p.created_at < :to
                GROUP BY p.method ORDER BY amount DESC""", range));
        // Money given back in the period (already netted out of the methods above).
        out.put("refunds", db.one("Refunds", """
                SELECT coalesce(-sum(amount), 0) AS amount, count(DISTINCT order_id) AS orders FROM payments
                WHERE kind = 'REFUND' AND created_at >= :from AND created_at < :to""", range));
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

    private Object[] window(LocalDate from, LocalDate to) {
        LocalDate f = from == null ? LocalDate.now(NAIROBI).minusDays(29) : from;
        LocalDate t = to == null ? LocalDate.now(NAIROBI) : to;
        return new Object[]{"from", Timestamp.from(f.atStartOfDay(NAIROBI).toInstant()), "to", Timestamp.from(t.plusDays(1).atStartOfDay(NAIROBI).toInstant())};
    }

    /**
     * Back office (ERP): what the stock is worth, what is running low, what was bought and from whom,
     * stock written off, and money owed both ways. Separate from the till report on purpose.
     */
    @GetMapping("/back-office")
    public Map<String, Object> backOffice(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        Object[] range = window(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stock", db.one("Stock", """
                SELECT coalesce(sum(s.qty * i.cost), 0) AS cost_value, coalesce(sum(s.qty * i.price), 0) AS sale_value,
                       count(DISTINCT i.id) FILTER (WHERE s.qty > 0) AS items_in_stock
                FROM stock_levels s JOIN items i ON i.id = s.item_id WHERE i.active AND i.track_stock AND s.qty > 0"""));
        out.put("lowStock", db.list("""
                SELECT i.name, coalesce(sum(s.qty), 0) AS qty, i.reorder_level, i.unit
                FROM items i LEFT JOIN stock_levels s ON s.item_id = i.id
                WHERE i.active AND i.track_stock
                GROUP BY i.id, i.name, i.reorder_level, i.unit
                HAVING coalesce(sum(s.qty), 0) <= i.reorder_level
                ORDER BY coalesce(sum(s.qty), 0) - i.reorder_level, i.name LIMIT 50"""));
        out.put("purchases", db.one("Purchases", """
                SELECT coalesce(sum(total), 0) AS amount, count(*) AS orders FROM purchase_orders
                WHERE status = 'RECEIVED' AND received_at >= :from AND received_at < :to""", range));
        out.put("bySupplier", db.list("""
                SELECT coalesce(sp.name, 'No supplier') AS name, sum(po.total) AS amount, count(*) AS orders
                FROM purchase_orders po LEFT JOIN suppliers sp ON sp.id = po.supplier_id
                WHERE po.status = 'RECEIVED' AND po.received_at >= :from AND po.received_at < :to
                GROUP BY 1 ORDER BY amount DESC LIMIT 20""", range));
        out.put("writtenOff", db.one("Written off", """
                SELECT coalesce(-sum(m.qty * i.cost), 0) AS amount, count(*) AS moves
                FROM stock_moves m JOIN items i ON i.id = m.item_id
                WHERE m.reason = 'ADJUST' AND m.qty < 0 AND m.created_at >= :from AND m.created_at < :to""", range));
        out.put("owedToYou", db.one("Owed to you", """
                SELECT coalesce(sum(balance), 0) AS amount, count(*) AS customers FROM customers WHERE balance > 0"""));
        out.put("topDebtors", db.list("""
                SELECT name, phone, balance, credit_limit FROM customers WHERE balance > 0 ORDER BY balance DESC LIMIT 15"""));
        out.put("youOwe", db.one("You owe", """
                SELECT coalesce(sum(balance), 0) AS amount, count(*) AS suppliers FROM suppliers WHERE balance > 0"""));
        out.put("topCreditors", db.list("""
                SELECT name, phone, balance FROM suppliers WHERE balance > 0 ORDER BY balance DESC LIMIT 15"""));
        out.put("marginByCategory", db.list("""
                SELECT coalesce(c.name, 'Other') AS name, sum(l.line_total) AS sales,
                       sum(l.line_total) - sum(l.qty * coalesce(i.cost, 0)) - sum(round(l.line_total * l.tax_rate / (100 + l.tax_rate), 2)) AS profit
                FROM order_lines l JOIN orders o ON o.id = l.order_id
                LEFT JOIN items i ON i.id = l.item_id LEFT JOIN categories c ON c.id = i.category_id
                WHERE """ + SOLD + " AND o.closed_at >= :from AND o.closed_at < :to GROUP BY 1 ORDER BY sales DESC LIMIT 20", range));
        return out;
    }

    /** Customers and sales leads (CRM): who buys, who came back, and how the leads are moving. */
    @GetMapping("/customers")
    public Map<String, Object> customers(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        Object[] range = window(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", db.one("Customers", """
                SELECT (SELECT count(*) FROM customers WHERE created_at >= :from AND created_at < :to) AS new_customers,
                       (SELECT count(DISTINCT o.customer_id) FROM orders o WHERE """ + SOLD + """
                         AND o.customer_id IS NOT NULL AND o.closed_at >= :from AND o.closed_at < :to) AS buying_customers,
                       (SELECT count(*) FROM (SELECT o.customer_id FROM orders o WHERE """ + SOLD + """
                         AND o.customer_id IS NOT NULL AND o.closed_at >= :from AND o.closed_at < :to
                         GROUP BY o.customer_id HAVING count(*) > 1) x) AS came_back,
                       (SELECT coalesce(sum(o.total), 0) FROM orders o WHERE """ + SOLD + """
                         AND o.customer_id IS NULL AND o.closed_at >= :from AND o.closed_at < :to) AS walk_in_sales""", range));
        out.put("topCustomers", db.list("""
                SELECT c.name, c.phone, count(*) AS orders, sum(o.total) AS amount, max(o.closed_at) AS last_visit
                FROM orders o JOIN customers c ON c.id = o.customer_id
                WHERE """ + SOLD + " AND o.closed_at >= :from AND o.closed_at < :to GROUP BY c.id, c.name, c.phone ORDER BY amount DESC LIMIT 20", range));
        out.put("leads", db.one("Leads", """
                SELECT count(*) FILTER (WHERE d.created_at >= :from AND d.created_at < :to) AS new_leads,
                       coalesce(sum(d.value) FILTER (WHERE s.is_won AND coalesce(d.closed_at, d.last_activity_at) >= :from AND coalesce(d.closed_at, d.last_activity_at) < :to), 0) AS won_value,
                       count(*) FILTER (WHERE s.is_won AND coalesce(d.closed_at, d.last_activity_at) >= :from AND coalesce(d.closed_at, d.last_activity_at) < :to) AS won,
                       count(*) FILTER (WHERE s.is_lost AND coalesce(d.closed_at, d.last_activity_at) >= :from AND coalesce(d.closed_at, d.last_activity_at) < :to) AS lost,
                       coalesce(sum(d.value) FILTER (WHERE NOT s.is_won AND NOT s.is_lost), 0) AS open_value
                FROM deals d JOIN pipeline_stages s ON s.id = d.stage_id""", range));
        out.put("byStage", db.list("""
                SELECT s.name, count(d.id) AS deals, coalesce(sum(d.value), 0) AS value
                FROM pipeline_stages s LEFT JOIN deals d ON d.stage_id = s.id
                WHERE NOT s.is_won AND NOT s.is_lost GROUP BY s.id, s.name, s.sort ORDER BY s.sort"""));
        out.put("lostReasons", db.list("""
                SELECT coalesce(d.lost_reason, 'No reason given') AS reason, count(*) AS deals
                FROM deals d JOIN pipeline_stages s ON s.id = d.stage_id
                WHERE s.is_lost AND coalesce(d.closed_at, d.last_activity_at) >= :from AND coalesce(d.closed_at, d.last_activity_at) < :to GROUP BY 1 ORDER BY deals DESC LIMIT 10""", range));
        return out;
    }
}
