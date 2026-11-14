package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Floor plan and kitchen display (cf. Odoo pos_restaurant floors/tables/courses). */
@RestController
@RequestMapping("/api")
@Transactional
public class RestaurantController {

    private static final Set<String> KITCHEN_FLOW = Set.of("NEW", "PREPARING", "READY", "SERVED");

    private final Db db;
    private final com.lezitech.suite.sales.OrderService orders;
    private final KitchenService kitchen;

    public RestaurantController(Db db, com.lezitech.suite.sales.OrderService orders, KitchenService kitchen) {
        this.db = db;
        this.orders = orders;
        this.kitchen = kitchen;
    }

    /** Floors with every table and the running bill on it, for the waiter's floor view. */
    @GetMapping("/floors")
    public List<Map<String, Object>> floors() {
        List<Map<String, Object>> floors = db.list("SELECT * FROM floors ORDER BY sort, name");
        List<Map<String, Object>> tables = db.list("""
                SELECT t.*, o.id AS order_id, o.number AS order_number, o.total, o.guests, o.created_at AS opened_at, o.self_order,
                       u.name AS waiter_name, (o.status <> 'OPEN') AS awaiting_pay_check,
                       (SELECT count(*) FROM order_lines l WHERE l.order_id = o.id AND l.kitchen_status = 'READY') AS ready_count,
                       (SELECT count(*) FROM order_lines l WHERE l.order_id = o.id AND l.kitchen_status = 'PENDING') AS unsent_count
                FROM dining_tables t
                LEFT JOIN LATERAL (SELECT * FROM orders o WHERE o.table_id = t.id
                                     AND (o.status = 'OPEN' OR (o.needs_pay_check AND o.pay_checked_at IS NULL
                                          AND o.closed_at > now() - interval '16 hours'))
                                   ORDER BY (o.status = 'OPEN') DESC, o.created_at LIMIT 1) o ON true
                LEFT JOIN users u ON u.id = coalesce(o.assigned_to, o.user_id)
                ORDER BY t.y, t.x, t.label""");
        for (Map<String, Object> f : floors) {
            f.put("tables", tables.stream().filter(t -> f.get("id").equals(t.get("floorId"))).toList());
        }
        return floors;
    }

    @PostMapping("/floors")
    public Map<String, Object> createFloor(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        UUID id = db.insert("INSERT INTO floors (tenant_id, name, sort) VALUES (:t, :name, coalesce((SELECT max(sort) + 1 FROM floors), 0)) RETURNING id",
                "name", new Body(raw).str("name"));
        return db.one("Floor", "SELECT * FROM floors WHERE id = :id", "id", id);
    }

    @PostMapping("/tables")
    public Map<String, Object> createTable(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        UUID id = db.insert("""
                INSERT INTO dining_tables (tenant_id, floor_id, label, seats, shape, x, y)
                VALUES (:t, :floorId, :label, :seats, :shape, :x, :y) RETURNING id""",
                "floorId", UUID.fromString(b.str("floorId")), "label", b.str("label"), "seats", b.integer("seats", 4),
                "shape", b.str("shape", "square"), "x", b.integer("x", 0), "y", b.integer("y", 99));
        return db.one("Table", "SELECT * FROM dining_tables WHERE id = :id", "id", id);
    }

    @PutMapping("/tables/{id}")
    public Map<String, Object> updateTable(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        db.exec("UPDATE dining_tables SET label = :label, seats = :seats, shape = :shape WHERE id = :id",
                "id", id, "label", b.str("label"), "seats", b.integer("seats", 4), "shape", b.str("shape", "square"));
        return db.one("Table", "SELECT * FROM dining_tables WHERE id = :id", "id", id);
    }

    @DeleteMapping("/tables/{id}")
    public void deleteTable(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        db.exec("DELETE FROM dining_tables WHERE id = :id", "id", id);
    }

    /** Kitchen display: tickets (one per bill) with lines still to cook or collect. */
    @GetMapping("/kitchen")
    public List<Map<String, Object>> kitchen(@RequestParam(required = false) String station) {
        List<Map<String, Object>> lines = db.list("""
                SELECT l.id, l.order_id, l.item_id, l.name, l.qty, l.options, l.note, l.course, l.station, l.kitchen_status, l.sent_at,
                       l.rejected_reason, s.name AS substitute_name,
                       o.number, o.channel, o.estimated_ready_at, o.customer_label, o.self_order, o.pickup_code,
                       t.label AS table_label, u.name AS waiter_name,
                       (SELECT string_agg(gt.label, ', ' ORDER BY g.number) FROM orders g JOIN dining_tables gt ON gt.id = g.table_id
                        WHERE g.batch_id = o.batch_id AND g.id <> o.id) AS group_with
                FROM order_lines l JOIN orders o ON o.id = l.order_id
                LEFT JOIN dining_tables t ON t.id = o.table_id LEFT JOIN users u ON u.id = coalesce(o.assigned_to, o.user_id)
                LEFT JOIN items s ON s.id = l.substitute_item_id
                WHERE l.kitchen_status IN ('NEW','PREPARING','READY','ON_HOLD') AND o.status <> 'VOID'
                  AND (cast(:station as text) IS NULL OR l.station = cast(:station as text))
                ORDER BY l.sent_at, l.course, l.created_at""", "station", station == null || station.isBlank() ? null : station);
        Map<Object, Map<String, Object>> tickets = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            Map<String, Object> ticket = tickets.computeIfAbsent(l.get("orderId"), k -> {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("orderId", l.get("orderId"));
                t.put("number", l.get("number"));
                t.put("channel", l.get("channel"));
                t.put("tableLabel", l.get("tableLabel"));
                t.put("waiterName", l.get("waiterName"));
                t.put("sentAt", l.get("sentAt"));
                t.put("estimatedReadyAt", l.get("estimatedReadyAt"));
                t.put("customerLabel", l.get("customerLabel"));
                t.put("selfOrder", l.get("selfOrder"));
                t.put("pickupCode", l.get("pickupCode"));
                t.put("groupWith", l.get("groupWith"));
                t.put("lines", new java.util.ArrayList<Map<String, Object>>());
                return t;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tl = (List<Map<String, Object>>) ticket.get("lines");
            tl.add(l);
        }
        for (Map<String, Object> t : tickets.values()) {
            t.put("suggestedMinutes", kitchen.suggestMinutes(UUID.fromString(t.get("orderId").toString())));
        }
        return List.copyOf(tickets.values());
    }

    /** The kitchen sets how long an order will take; the diner sees it counting down. */
    @PostMapping("/kitchen/orders/{orderId}/estimate")
    public Map<String, Object> estimate(@PathVariable UUID orderId, @RequestBody Map<String, Object> raw) {
        Integer minutes = new Body(raw).integer("minutes", null);
        if (minutes == null) {
            throw ApiException.bad("Choose how many minutes");
        }
        return Map.of("estimatedReadyAt", kitchen.setEstimate(orderId, minutes).toString());
    }

    /** Can't make a dish: offer another (the diner answers on their phone) or take it off the bill. */
    @PostMapping("/kitchen/lines/{id}/reject")
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        kitchen.reject(id, b.str("reason", null), b.has("substituteItemId") ? UUID.fromString(b.str("substituteItemId")) : null,
                b.bool("markFinished", false));
        return Map.of("ok", true);
    }

    /** Mark a dish finished (until restocked, or until a time) or back on. */
    @PostMapping("/kitchen/items/{id}/availability")
    public Map<String, Object> availability(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        kitchen.setAvailability(id, b.str("until", null), b.has("at") ? b.instant("at") : null);
        return Map.of("ok", true);
    }

    @GetMapping("/kitchen/finished")
    public List<Map<String, Object>> finished() {
        return kitchen.finishedItems();
    }

    // ---------- Waiters ----------

    /**
     * A waiter's own tables: orders assigned to them (or that they started), what the kitchen is doing with each,
     * whether it's paid, and notices that need them. Managers see everyone's, with each waiter's load.
     */
    @GetMapping("/waiter/tables")
    public Map<String, Object> waiterTables(@RequestParam(defaultValue = "false") boolean everyone) {
        TenantContext.Caller me = TenantContext.caller();
        boolean all = everyone && Set.of("OWNER", "MANAGER").contains(me.role());
        List<Map<String, Object>> list = db.list("""
                SELECT o.id, o.number, o.channel, o.total, o.paid, o.customer_label, o.self_order, o.held_for_payment,
                       o.estimated_ready_at, o.created_at, o.pickup_code, t.label AS table_label,
                       coalesce(o.assigned_to, o.user_id) AS waiter_id, u.name AS waiter_name,
                       count(l.id) FILTER (WHERE l.kitchen_status IN ('NEW','PREPARING')) AS cooking,
                       count(l.id) FILTER (WHERE l.kitchen_status = 'READY') AS ready,
                       count(l.id) FILTER (WHERE l.kitchen_status = 'ON_HOLD') AS waiting_on_diner,
                       count(l.id) FILTER (WHERE l.kitchen_status = 'PENDING') AS not_sent,
                       coalesce(sum(l.qty) FILTER (WHERE l.kitchen_status <> 'SERVED'), 0) AS plates,
                       (o.needs_pay_check AND o.pay_checked_at IS NULL) AS awaiting_pay_check,
                       (SELECT string_agg(DISTINCT m.phone, ', ') FROM mpesa_requests m
                        WHERE m.status = 'PAID' AND (m.order_id = o.id OR m.batch_id = o.batch_id)) AS paid_by,
                       (cardinality(o.ordered_phones) > 0 AND EXISTS (SELECT 1 FROM mpesa_requests m
                               WHERE m.status = 'PAID' AND m.user_id IS NULL AND (m.order_id = o.id OR m.batch_id = o.batch_id)
                               AND NOT (m.phone = ANY(o.ordered_phones)))) AS paid_from_other_number
                FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id
                LEFT JOIN users u ON u.id = coalesce(o.assigned_to, o.user_id)
                LEFT JOIN order_lines l ON l.order_id = o.id
                WHERE o.kind = 'SALE' AND o.channel IN ('DINE_IN', 'TAKEAWAY')
                  AND (o.status = 'OPEN' OR (o.status = 'PAID' AND o.self_order AND o.closed_at > now() - interval '6 hours'
                       AND EXISTS (SELECT 1 FROM order_lines x WHERE x.order_id = o.id AND x.kitchen_status <> 'SERVED'))
                       OR (o.needs_pay_check AND o.pay_checked_at IS NULL AND o.closed_at > now() - interval '16 hours'))
                  AND (:all OR coalesce(o.assigned_to, o.user_id) = :u)
                GROUP BY o.id, t.label, u.name ORDER BY count(l.id) FILTER (WHERE l.kitchen_status = 'READY') DESC, o.created_at""",
                "all", all);
        List<Map<String, Object>> notices = db.list("""
                SELECT n.id, n.order_id, n.kind, n.message, n.created_at, o.number, t.label AS table_label
                FROM order_notices n JOIN orders o ON o.id = n.order_id LEFT JOIN dining_tables t ON t.id = o.table_id
                WHERE n.done_at IS NULL AND n.for_waiter AND n.created_at > now() - interval '12 hours'
                  AND (:all OR coalesce(o.assigned_to, o.user_id) = :u)
                ORDER BY n.created_at""", "all", all);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", list);
        out.put("notices", notices);
        if (all) {
            out.put("waiters", db.list("""
                    SELECT u.id, u.name,
                           (SELECT count(DISTINCT o.table_id) FROM orders o WHERE o.assigned_to = u.id AND o.status = 'OPEN') AS tables,
                           (SELECT coalesce(sum(l.qty), 0) FROM orders o JOIN order_lines l ON l.order_id = o.id
                            WHERE o.assigned_to = u.id AND o.status = 'OPEN' AND l.kitchen_status <> 'SERVED') AS plates
                    FROM users u WHERE u.tenant_id = :t AND u.role = 'WAITER' AND u.active ORDER BY u.name"""));
        }
        return out;
    }

    /** Tips by server, and how they were shared, for a date range (default: the last 7 days). */
    @GetMapping("/reports/tips")
    public Map<String, Object> tips(@RequestParam(required = false) java.time.LocalDate from, @RequestParam(required = false) java.time.LocalDate to) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        java.time.ZoneId nairobi = java.time.ZoneId.of("Africa/Nairobi");
        java.time.LocalDate f = from == null ? java.time.LocalDate.now(nairobi).minusDays(6) : from;
        java.time.LocalDate t = to == null ? java.time.LocalDate.now(nairobi) : to;
        Object[] p = {"from", java.sql.Timestamp.from(f.atStartOfDay(nairobi).toInstant()), "to", java.sql.Timestamp.from(t.plusDays(1).atStartOfDay(nairobi).toInstant())};
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("byServer", db.list("""
                SELECT coalesce(u.name, 'No waiter') AS name, count(*) AS tips, sum(x.amount) AS amount, sum(x.server_share) AS server_share
                FROM tips x LEFT JOIN users u ON u.id = x.server_id
                WHERE x.created_at >= :from AND x.created_at < :to GROUP BY u.name ORDER BY sum(x.amount) DESC""", p));
        out.put("totals", db.one("Tips", """
                SELECT count(*) AS tips, coalesce(sum(amount), 0) AS amount, coalesce(sum(kitchen_share), 0) AS kitchen,
                       coalesce(sum(business_share), 0) AS business, coalesce(sum(server_share), 0) AS servers
                FROM tips WHERE created_at >= :from AND created_at < :to""", p));
        return out;
    }

    @PostMapping("/waiter/notices/{id}/done")
    public Map<String, Object> noticeDone(@PathVariable UUID id) {
        kitchen.noticeDone(id);
        return Map.of("ok", true);
    }

    /** R1: the waiter looked at a table paid from a diner's phone: "paid, checked". The table shows as free again. */
    @PostMapping("/waiter/orders/{orderId}/pay-checked")
    public Map<String, Object> payChecked(@PathVariable UUID orderId) {
        if (db.exec("""
                UPDATE orders SET pay_checked_at = now(), pay_checked_by = :u
                WHERE id = :id AND needs_pay_check AND pay_checked_at IS NULL""", "id", orderId) == 0) {
            throw ApiException.notFound("A paid table waiting for a check");
        }
        return Map.of("ok", true);
    }

    /** Take an order yourself, or (managers) give it to another waiter. */
    @PostMapping("/waiter/orders/{orderId}/assign")
    public Map<String, Object> assign(@PathVariable UUID orderId, @RequestBody(required = false) Map<String, Object> raw) {
        TenantContext.Caller me = TenantContext.caller();
        Body b = new Body(raw);
        UUID to = me.userId();
        if (b.has("userId")) {
            TenantContext.requireRole("MANAGER");
            to = UUID.fromString(b.str("userId"));
            if (db.value("SELECT count(*) FROM users WHERE id = :id AND tenant_id = :t AND active", Long.class, "id", to) == 0) {
                throw ApiException.notFound("That waiter");
            }
        }
        orders.requireOpen(orderId);
        db.exec("UPDATE orders SET assigned_to = :to WHERE id = :id", "to", to, "id", orderId);
        return Map.of("ok", true);
    }

    @GetMapping("/kitchen/stations")
    public List<Map<String, Object>> stations() {
        return db.list("SELECT DISTINCT station FROM items WHERE station IS NOT NULL ORDER BY station");
    }

    @PostMapping("/kitchen/lines/{id}")
    public Map<String, Object> setLine(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        String status = new Body(raw).str("status");
        if (!KITCHEN_FLOW.contains(status)) {
            throw ApiException.bad("Unknown kitchen status");
        }
        db.exec("UPDATE order_lines SET kitchen_status = :s WHERE id = :id AND kitchen_status <> 'ON_HOLD'", "id", id, "s", status);
        kitchen.moved(id, status);
        Map<String, Object> line = db.one("Line", "SELECT id, order_id, kitchen_status FROM order_lines WHERE id = :id", "id", id);
        orders.markReadyIfDone(UUID.fromString(line.get("orderId").toString()));
        return line;
    }

    /** Bump a whole ticket one step: new → preparing → ready → served. */
    @PostMapping("/kitchen/orders/{orderId}/bump")
    public Map<String, Object> bump(@PathVariable UUID orderId) {
        String lowest = db.value("""
                SELECT kitchen_status FROM order_lines WHERE order_id = :orderId AND kitchen_status IN ('NEW','PREPARING','READY')
                ORDER BY array_position(ARRAY['NEW','PREPARING','READY'], kitchen_status) LIMIT 1""", String.class, "orderId", orderId);
        if (lowest == null) {
            return Map.of("status", "SERVED");
        }
        String next = switch (lowest) {
            case "NEW" -> "PREPARING";
            case "PREPARING" -> "READY";
            default -> "SERVED";
        };
        db.exec("""
                UPDATE order_lines SET kitchen_status = :next,
                       ready_at = CASE WHEN :next IN ('READY', 'SERVED') THEN coalesce(ready_at, now()) ELSE ready_at END
                WHERE order_id = :orderId AND kitchen_status = :cur""",
                "orderId", orderId, "next", next, "cur", lowest);
        orders.markReadyIfDone(orderId);
        return Map.of("status", next);
    }
}
