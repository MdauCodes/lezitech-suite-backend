package com.lezitech.suite.booking;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.OrderService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Appointment diary by staff member, for salons, spas, clinics and service firms. */
@RestController
@RequestMapping("/api/appointments")
@Transactional
public class BookingController {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final Set<String> STATUSES = Set.of("BOOKED", "ARRIVED", "DONE", "NO_SHOW", "CANCELLED");

    private final Db db;
    private final OrderService orders;

    public BookingController(Db db, OrderService orders) {
        this.db = db;
        this.orders = orders;
    }

    @GetMapping
    public Map<String, Object> day(@RequestParam(required = false) LocalDate date) {
        LocalDate d = date == null ? LocalDate.now(NAIROBI) : date;
        Instant from = d.atStartOfDay(NAIROBI).toInstant();
        Instant to = from.plus(1, ChronoUnit.DAYS);
        return Map.of(
                "date", d.toString(),
                "staff", db.list("""
                        SELECT id, name, role FROM users WHERE tenant_id = :t AND active
                          AND role IN ('STYLIST','OWNER','MANAGER','SALES') ORDER BY role = 'STYLIST' DESC, name"""),
                "appointments", db.list("""
                        SELECT a.*, i.name AS service_name, i.price, u.name AS staff_name, o.status AS order_status
                        FROM appointments a LEFT JOIN items i ON i.id = a.item_id LEFT JOIN users u ON u.id = a.staff_id
                        LEFT JOIN orders o ON o.id = a.order_id
                        WHERE a.starts_at >= :from AND a.starts_at < :to ORDER BY a.starts_at""",
                        "from", Timestamp.from(from), "to", Timestamp.from(to)));
    }

    @PostMapping
    public Map<String, Object> book(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        Instant start = b.instant("startsAt");
        UUID itemId = b.has("itemId") ? UUID.fromString(b.str("itemId")) : null;
        Integer minutes = b.integer("durationMin", null);
        if (minutes == null && itemId != null) {
            minutes = db.value("SELECT duration_min FROM items WHERE id = :itemId", Integer.class, "itemId", itemId);
        }
        Instant end = start.plus(minutes == null ? 60 : minutes, ChronoUnit.MINUTES);
        UUID staffId = b.has("staffId") ? UUID.fromString(b.str("staffId")) : null;
        if (staffId != null && Boolean.TRUE.equals(db.value("""
                SELECT exists(SELECT 1 FROM appointments WHERE staff_id = :staffId AND status IN ('BOOKED','ARRIVED')
                  AND starts_at < :end AND ends_at > :start)""", Boolean.class,
                "staffId", staffId, "start", Timestamp.from(start), "end", Timestamp.from(end)))) {
            throw ApiException.conflict("That staff member is already booked at this time");
        }
        UUID customerId = b.has("customerId") ? UUID.fromString(b.str("customerId")) : null;
        String name = b.str("customerName", null);
        if (customerId == null && b.has("phone")) {
            customerId = db.value("SELECT id FROM customers WHERE phone = :phone LIMIT 1", UUID.class, "phone", b.str("phone"));
        }
        if (customerId == null && name != null) {
            customerId = db.insert("INSERT INTO customers (tenant_id, name, phone) VALUES (:t, :name, :phone) RETURNING id",
                    "name", name, "phone", b.str("phone", null));
        }
        if (name == null && customerId != null) {
            name = db.value("SELECT name FROM customers WHERE id = :customerId", String.class, "customerId", customerId);
        }
        if (name == null) {
            throw ApiException.bad("Add the client's name");
        }
        UUID id = db.insert("""
                INSERT INTO appointments (tenant_id, customer_id, customer_name, phone, item_id, staff_id, starts_at, ends_at, notes)
                VALUES (:t, :customerId, :name, :phone, :itemId, :staffId, :start, :end, :notes) RETURNING id""",
                "customerId", customerId, "name", name, "phone", b.str("phone", null), "itemId", itemId, "staffId", staffId,
                "start", Timestamp.from(start), "end", Timestamp.from(end), "notes", b.str("notes", null));
        return db.one("Appointment", "SELECT * FROM appointments WHERE id = :id", "id", id);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> setStatus(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        String status = new Body(raw).str("status");
        if (!STATUSES.contains(status)) {
            throw ApiException.bad("Unknown status");
        }
        db.exec("UPDATE appointments SET status = :s WHERE id = :id", "id", id, "s", status);
        return db.one("Appointment", "SELECT * FROM appointments WHERE id = :id", "id", id);
    }

    /** Open a ticket for the booked service, credited to the stylist, ready to add products and pay. */
    @PostMapping("/{id}/checkout")
    public Map<String, Object> checkout(@PathVariable UUID id) {
        Map<String, Object> a = db.one("Appointment", "SELECT * FROM appointments WHERE id = :id", "id", id);
        if (a.get("orderId") != null) {
            return orders.detail(UUID.fromString(a.get("orderId").toString()));
        }
        java.util.Map<String, Object> line = new java.util.HashMap<>();
        line.put("qty", 1);
        if (a.get("itemId") != null) {
            line.put("itemId", a.get("itemId"));
        }
        if (a.get("staffId") != null) {
            line.put("staffId", a.get("staffId"));
        }
        java.util.Map<String, Object> order = new java.util.HashMap<>();
        order.put("channel", "BOOKING");
        if (a.get("customerId") != null) {
            order.put("customerId", a.get("customerId"));
        }
        order.put("lines", a.get("itemId") == null ? List.of() : List.of(line));
        UUID orderId = orders.create(new Body(order));
        db.exec("UPDATE appointments SET order_id = :orderId, status = 'ARRIVED' WHERE id = :id", "id", id, "orderId", orderId);
        return orders.detail(orderId);
    }
}
