package com.lezitech.suite.property;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Markets (owner's decisions, 2026-11-14). Stalls pay monthly (the usual), weekly or daily. A daily stall is charged
 * every day the market opens, whether the trader comes or not; the collector can excuse a day. Collectors walk the rows
 * with a phone: cash (recorded against them), an M-Pesa prompt, or not paid. At day's end the office counts each
 * collector's cash; a shortfall shows and is logged. One-off tickets for casual traders only if the landlord allows them.
 * Callers run inside the workspace's transaction.
 */
@Service
public class Market {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final Db db;
    private final OrderService orders;
    private final LeaseLedger ledger;
    private final ActivityLog activity;

    public Market(Db db, OrderService orders, LeaseLedger ledger, ActivityLog activity) {
        this.db = db;
        this.orders = orders;
        this.ledger = ledger;
        this.activity = activity;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> market(Map<String, Object> rentSettings) {
        return (Map<String, Object>) rentSettings.get("market");
    }

    Map<String, Object> settings() {
        return RentSettings.withDefaults(db.value("SELECT coalesce(settings->'rent', '{}'::jsonb)::text FROM tenants WHERE id = :t", String.class));
    }

    static boolean open(Map<String, Object> rentSettings, LocalDate day) {
        return ((List<?>) market(rentSettings).get("openDays")).stream().anyMatch(d -> ((Number) d).intValue() == day.getDayOfWeek().getValue());
    }

    /** The day's charges for daily stalls (if the market opens that day) and the week's for weekly stalls. Safe to run twice. */
    public int raiseStallCharges(LocalDate day, Map<String, Object> rentSettings) {
        if (!open(rentSettings, day)) {
            return 0;
        }
        int created = 0;
        LocalDate monday = day.with(DayOfWeek.MONDAY);
        for (Map<String, Object> l : db.list("""
                SELECT l.id, l.rent, l.frequency, l.customer_id, sp.name AS unit_name FROM leases l JOIN spaces sp ON sp.id = l.space_id
                WHERE l.status = 'ACTIVE' AND l.frequency IN ('DAILY','WEEKLY') AND l.starts_on <= cast(:d as date)
                  AND (l.moved_out_on IS NULL OR l.moved_out_on > cast(:d as date))""", "d", day)) {
            boolean daily = "DAILY".equals(l.get("frequency"));
            LocalDate chargeDay = daily ? day : monday;
            UUID leaseId = UUID.fromString(l.get("id").toString());
            // Already charged, or excused (a cancelled charge): never charged again for that day or week.
            if (db.find("SELECT id FROM orders WHERE lease_id = :l AND charge_day = cast(:d as date)", "l", leaseId, "d", chargeDay).isPresent()) {
                continue;
            }
            Map<String, Object> inv = new LinkedHashMap<>();
            inv.put("kind", "INVOICE");
            inv.put("channel", "STALL");
            inv.put("customerId", l.get("customerId"));
            inv.put("dueOn", day.toString());
            inv.put("lines", List.of(Map.of("name", "Stall " + l.get("unitName") + " · " + (daily ? "day " + DAY.format(day) : "week of " + DAY.format(monday)),
                    "unitPrice", l.get("rent"), "qty", 1, "taxRate", 0)));
            UUID order = orders.createTrusted(new Body(inv));
            db.exec("UPDATE orders SET lease_id = :l, charge_day = cast(:d as date) WHERE id = :o", "l", leaseId, "d", chargeDay, "o", order);
            ledger.useCredit(leaseId);
            created++;
        }
        return created;
    }

    /** The collector's round for a day: every market stall, today's charge, what they owe in all, and what this collector took. */
    public Map<String, Object> round(LocalDate day) {
        Map<String, Object> rs = settings();
        if (day.equals(LeaseLedger.today())) {
            raiseStallCharges(day, rs);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.toString());
        out.put("open", open(rs, day));
        out.put("casualTickets", market(rs).get("casualTickets"));
        out.put("ticketFee", market(rs).get("ticketFee"));
        out.put("stalls", db.list("""
                SELECT l.id AS lease_id, sp.name AS unit_name, sp.floor, c.name AS trader, c.phone, l.frequency, l.rent, l.credit,
                       (SELECT o.id FROM orders o WHERE o.lease_id = l.id AND o.charge_day = cast(:d as date) ORDER BY o.status = 'VOID' LIMIT 1) AS charge_id,
                       (SELECT o.status FROM orders o WHERE o.lease_id = l.id AND o.charge_day = cast(:d as date) ORDER BY o.status = 'VOID' LIMIT 1) AS charge_status,
                       (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN') AS owed,
                       (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN' AND o.due_on < cast(:d as date)) AS owed_before,
                       (SELECT coalesce(sum(m.amount), 0) FROM market_collections m WHERE m.lease_id = l.id AND m.day = cast(:d as date)) AS collected_today
                FROM leases l JOIN spaces sp ON sp.id = l.space_id JOIN customers c ON c.id = l.customer_id
                WHERE l.status = 'ACTIVE' AND (l.frequency <> 'MONTHLY' OR sp.kind = 'STALL')
                ORDER BY sp.sort, sp.name""", "d", day));
        out.put("mine", myDay(TenantContext.userId(), day));
        return out;
    }

    /** Cash from a stall, taken by the collector: put on the stall's bills (oldest first) and on the collector's day. */
    public Map<String, Object> collect(UUID leaseId, BigDecimal amount, LocalDate day) {
        if (day.isAfter(LeaseLedger.today())) {
            throw ApiException.bad("Can't record money for a day that hasn't come");
        }
        TenantContext.Caller c = TenantContext.caller();
        Map<String, Object> r = ledger.receive(leaseId, amount, "CASH", null, "COLLECTOR", "Collected by " + c.name(), day);
        db.exec("""
                INSERT INTO market_collections (tenant_id, collector_id, collector_name, day, kind, lease_id, amount, method)
                VALUES (:t, :u, :n, :d, 'STALL', :l, :a, 'CASH')""", "u", c.userId(), "n", c.name(), "d", day, "l", leaseId, "a", amount);
        return r;
    }

    /** Waive a daily stall's charge for a day (e.g. the trader was sick). Only if nothing was paid on it. */
    public Map<String, Object> excuse(UUID leaseId, LocalDate day, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.bad("Say why the day is excused");
        }
        Map<String, Object> o = db.one("Charge", "SELECT id, paid, status FROM orders WHERE lease_id = :l AND charge_day = cast(:d as date) AND status <> 'VOID' FOR UPDATE",
                "l", leaseId, "d", day);
        if (((BigDecimal) o.get("paid")).signum() > 0 || !"OPEN".equals(o.get("status"))) {
            throw ApiException.conflict("Something was already paid for that day; it can't be excused");
        }
        orders.voidOrder(UUID.fromString(o.get("id").toString()), "Excused: " + reason.trim());
        return Map.of("excused", true);
    }

    /** A one-off ticket for a casual trader (only if the landlord allows them). Returns the ticket number. */
    public Map<String, Object> ticket(String name, String phone, BigDecimal amountOrNull, LocalDate day) {
        Map<String, Object> m = market(settings());
        if (!Boolean.TRUE.equals(m.get("casualTickets"))) {
            throw ApiException.forbidden("Casual tickets are switched off. The landlord can switch them on in Rent settings.");
        }
        String who = name == null || name.isBlank() ? "Casual trader" : name.trim();
        BigDecimal amount = amountOrNull != null ? amountOrNull : new BigDecimal(String.valueOf(m.get("ticketFee")));
        if (amount.signum() <= 0) {
            throw ApiException.bad("Enter the fee");
        }
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("kind", "SALE");
        sale.put("channel", "MARKET");
        sale.put("lines", List.of(Map.of("name", "Daily ticket · " + who + (phone == null || phone.isBlank() ? "" : " · " + phone.trim()),
                "unitPrice", amount.setScale(2, RoundingMode.HALF_UP), "qty", 1, "taxRate", 0)));
        UUID order = orders.createTrusted(new Body(sale));
        orders.pay(order, new Body(Map.of("payments", List.of(Map.of("method", "CASH", "amount", amount)))));
        TenantContext.Caller c = TenantContext.caller();
        db.exec("""
                INSERT INTO market_collections (tenant_id, collector_id, collector_name, day, kind, order_id, payer_name, amount, method)
                VALUES (:t, :u, :n, :d, 'TICKET', :o, :p, :a, 'CASH')""", "u", c.userId(), "n", c.name(), "d", day, "o", order, "p", who, "a", amount);
        return Map.of("ticket", db.value("SELECT number FROM orders WHERE id = :o", Integer.class, "o", order), "amount", amount, "name", who);
    }

    /** What a collector took on a day, and the office's count if done. */
    public Map<String, Object> myDay(UUID collector, LocalDate day) {
        Map<String, Object> out = new LinkedHashMap<>(db.one("Day", """
                SELECT coalesce(sum(amount), 0) AS cash, count(*) FILTER (WHERE kind = 'STALL') AS stalls, count(*) FILTER (WHERE kind = 'TICKET') AS tickets
                FROM market_collections WHERE collector_id = :u AND day = cast(:d as date) AND method = 'CASH'""", "u", collector, "d", day));
        out.put("count", db.find("SELECT counted, difference, note, counted_by_name, created_at FROM market_cash_counts WHERE collector_id = :u AND day = cast(:d as date)",
                "u", collector, "d", day).orElse(null));
        return out;
    }

    /** The office's view of a day: every collector's cash, counted or waiting. */
    public List<Map<String, Object>> days(LocalDate day) {
        return db.list("""
                SELECT m.collector_id, max(m.collector_name) AS collector_name, sum(m.amount) AS recorded, count(*) AS entries,
                       k.counted, k.difference, k.note, k.counted_by_name
                FROM market_collections m LEFT JOIN market_cash_counts k ON k.collector_id = m.collector_id AND k.day = m.day
                WHERE m.day = cast(:d as date) AND m.method = 'CASH'
                GROUP BY m.collector_id, k.counted, k.difference, k.note, k.counted_by_name ORDER BY 2""", "d", day);
    }

    /** The office counts a collector's cash for the day. A shortfall is logged as a concern. Once per collector and day. */
    public Map<String, Object> count(UUID collector, LocalDate day, BigDecimal counted, String note) {
        if (counted == null || counted.signum() < 0) {
            throw ApiException.bad("Enter the cash counted");
        }
        if (db.find("SELECT id FROM market_cash_counts WHERE collector_id = :u AND day = cast(:d as date)", "u", collector, "d", day).isPresent()) {
            throw ApiException.conflict("That day's cash was already counted");
        }
        Map<String, Object> mine = myDay(collector, day);
        BigDecimal recorded = (BigDecimal) mine.get("cash");
        BigDecimal diff = counted.subtract(recorded);
        TenantContext.Caller c = TenantContext.caller();
        db.exec("""
                INSERT INTO market_cash_counts (tenant_id, collector_id, day, recorded, counted, difference, note, counted_by, counted_by_name)
                VALUES (:t, :u, :d, :r, :c, :x, :n, :by, :byn)""", "u", collector, "d", day, "r", recorded, "c", counted, "x", diff,
                "n", note == null || note.isBlank() ? null : note.trim(), "by", c.userId(), "byn", c.name());
        String name = db.value("SELECT name FROM users WHERE id = :u", String.class, "u", collector);
        if (diff.signum() != 0) {
            activity.record("MARKET_CASH", diff.signum() < 0 ? ActivityLog.CONCERN : ActivityLog.NOTICE,
                    name + "'s market cash for " + day + ": recorded " + recorded.toPlainString() + ", counted " + counted.toPlainString()
                            + (diff.signum() < 0 ? " (" + diff.negate().toPlainString() + " short)" : " (" + diff.toPlainString() + " over)"),
                    null, null, diff.abs(), null, Map.of("collector", collector.toString(), "day", day.toString()));
        }
        return myDay(collector, day);
    }
}
