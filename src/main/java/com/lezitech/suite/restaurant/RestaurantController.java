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

    public RestaurantController(Db db) {
        this.db = db;
    }

    /** Floors with every table and the running bill on it, for the waiter's floor view. */
    @GetMapping("/floors")
    public List<Map<String, Object>> floors() {
        List<Map<String, Object>> floors = db.list("SELECT * FROM floors ORDER BY sort, name");
        List<Map<String, Object>> tables = db.list("""
                SELECT t.*, o.id AS order_id, o.number AS order_number, o.total, o.guests, o.created_at AS opened_at,
                       u.name AS waiter_name,
                       (SELECT count(*) FROM order_lines l WHERE l.order_id = o.id AND l.kitchen_status = 'READY') AS ready_count,
                       (SELECT count(*) FROM order_lines l WHERE l.order_id = o.id AND l.kitchen_status = 'PENDING') AS unsent_count
                FROM dining_tables t
                LEFT JOIN LATERAL (SELECT * FROM orders o WHERE o.table_id = t.id AND o.status = 'OPEN'
                                   ORDER BY o.created_at LIMIT 1) o ON true
                LEFT JOIN users u ON u.id = o.user_id
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
                SELECT l.id, l.order_id, l.name, l.qty, l.options, l.note, l.course, l.station, l.kitchen_status, l.sent_at,
                       o.number, o.channel, t.label AS table_label, u.name AS waiter_name
                FROM order_lines l JOIN orders o ON o.id = l.order_id
                LEFT JOIN dining_tables t ON t.id = o.table_id LEFT JOIN users u ON u.id = o.user_id
                WHERE l.kitchen_status IN ('NEW','PREPARING','READY') AND o.status <> 'VOID'
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
                t.put("lines", new java.util.ArrayList<Map<String, Object>>());
                return t;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tl = (List<Map<String, Object>>) ticket.get("lines");
            tl.add(l);
        }
        return List.copyOf(tickets.values());
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
        db.exec("UPDATE order_lines SET kitchen_status = :s WHERE id = :id", "id", id, "s", status);
        return db.one("Line", "SELECT id, kitchen_status FROM order_lines WHERE id = :id", "id", id);
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
        db.exec("UPDATE order_lines SET kitchen_status = :next WHERE order_id = :orderId AND kitchen_status = :cur",
                "orderId", orderId, "next", next, "cur", lowest);
        return Map.of("status", next);
    }
}
