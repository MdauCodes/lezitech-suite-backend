package com.lezitech.suite.tours;

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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tour packages run as departures with a seat count; each booking carries an invoice for deposits and balance. */
@RestController
@RequestMapping("/api/trips")
@Transactional
public class TripController {

    private static final Set<String> STATUSES = Set.of("OPEN", "FULL", "DEPARTED", "DONE", "CANCELLED");

    private final Db db;
    private final OrderService orders;

    public TripController(Db db, OrderService orders) {
        this.db = db;
        this.orders = orders;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return db.list("""
                SELECT tr.*, i.name AS package_name, i.price AS package_price,
                       coalesce(sum(b.pax) FILTER (WHERE b.status <> 'CANCELLED'), 0) AS booked,
                       coalesce(sum(o.total) FILTER (WHERE b.status <> 'CANCELLED'), 0) AS revenue,
                       coalesce(sum(o.total - o.paid) FILTER (WHERE b.status <> 'CANCELLED' AND o.status = 'OPEN'), 0) AS balance_due
                FROM trips tr JOIN items i ON i.id = tr.item_id
                LEFT JOIN trip_bookings b ON b.trip_id = tr.id LEFT JOIN orders o ON o.id = b.order_id
                WHERE tr.ends_on >= current_date - 30
                GROUP BY tr.id, i.name, i.price ORDER BY tr.starts_on""");
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        Map<String, Object> t = new LinkedHashMap<>(db.one("Departure", """
                SELECT tr.*, i.name AS package_name, i.price AS package_price FROM trips tr JOIN items i ON i.id = tr.item_id
                WHERE tr.id = :id""", "id", id));
        t.put("bookings", db.list("""
                SELECT b.*, o.total, o.paid, o.status AS invoice_status, o.number AS invoice_number
                FROM trip_bookings b LEFT JOIN orders o ON o.id = b.order_id WHERE b.trip_id = :id ORDER BY b.created_at""", "id", id));
        return t;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        UUID item = UUID.fromString(b.str("itemId"));
        LocalDate start = b.date("startsOn");
        Integer minutes = db.value("SELECT duration_min FROM items WHERE id = :item", Integer.class, "item", item);
        int days = minutes == null ? 1 : Math.max(1, minutes / 1440);
        LocalDate end = b.has("endsOn") ? b.date("endsOn") : start.plusDays(days - 1L);
        UUID id = db.insert("""
                INSERT INTO trips (tenant_id, item_id, starts_on, ends_on, seats, vehicle, guide, notes)
                VALUES (:t, :item, :start, :end, :seats, :vehicle, :guide, :notes) RETURNING id""",
                "item", item, "start", start, "end", end, "seats", b.integer("seats", 7),
                "vehicle", b.str("vehicle", null), "guide", b.str("guide", null), "notes", b.str("notes", null));
        return get(id);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        Map<String, Object> t = db.one("Departure", "SELECT * FROM trips WHERE id = :id", "id", id);
        String status = b.str("status", (String) t.get("status"));
        if (!STATUSES.contains(status)) {
            throw ApiException.bad("Unknown status");
        }
        db.exec("UPDATE trips SET status = :s, seats = :seats, vehicle = :v, guide = :g WHERE id = :id",
                "id", id, "s", status, "seats", b.integer("seats", (Integer) t.get("seats")),
                "v", b.str("vehicle", (String) t.get("vehicle")), "g", b.str("guide", (String) t.get("guide")));
        return get(id);
    }

    /** Book seats: checks capacity, creates the client and an invoice (package x pax + extras). */
    @PostMapping("/{id}/bookings")
    public Map<String, Object> book(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        Map<String, Object> t = db.one("Departure", "SELECT * FROM trips WHERE id = :id", "id", id);
        if (Set.of("DONE", "CANCELLED", "DEPARTED").contains(t.get("status"))) {
            throw ApiException.conflict("This departure is closed for booking");
        }
        int pax = b.integer("pax", 1);
        Integer booked = db.value("SELECT coalesce(sum(pax), 0)::int FROM trip_bookings WHERE trip_id = :id AND status <> 'CANCELLED'",
                Integer.class, "id", id);
        int seats = (Integer) t.get("seats");
        if (booked + pax > seats) {
            throw ApiException.conflict("Only " + (seats - booked) + " seat(s) left on this departure");
        }
        String lead = b.str("leadName");
        UUID customerId = b.has("customerId") ? UUID.fromString(b.str("customerId")) : null;
        if (customerId == null) {
            customerId = db.insert("INSERT INTO customers (tenant_id, name, phone, email) VALUES (:t, :name, :phone, :email) RETURNING id",
                    "name", lead, "phone", b.str("phone", null), "email", b.str("email", null));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(new HashMap<>(Map.of("itemId", t.get("itemId").toString(), "qty", pax)));
        for (Map<String, Object> extra : b.list("extras")) {
            lines.add(extra);
        }
        Map<String, Object> inv = new HashMap<>();
        inv.put("channel", "TOUR");
        inv.put("customerId", customerId.toString());
        inv.put("dueOn", t.get("startsOn"));
        inv.put("note", "Departure " + t.get("startsOn") + " · " + pax + " pax");
        inv.put("lines", lines);
        UUID order = orders.create(new Body(inv));
        UUID bookingId = db.insert("""
                INSERT INTO trip_bookings (tenant_id, trip_id, customer_id, lead_name, phone, email, pax, pickup, nationality, status, order_id, notes)
                VALUES (:t, :id, :customerId, :lead, :phone, :email, :pax, :pickup, :nat, :status, :order, :notes) RETURNING id""",
                "id", id, "customerId", customerId, "lead", lead, "phone", b.str("phone", null), "email", b.str("email", null),
                "pax", pax, "pickup", b.str("pickup", null), "nat", b.str("nationality", null),
                "status", b.str("status", "CONFIRMED"), "order", order, "notes", b.str("notes", null));
        if (b.has("deposit") && b.num("deposit").signum() > 0) {
            orders.pay(order, new Body(Map.of("deposit", true, "payments",
                    List.of(Map.of("method", b.str("depositMethod", "MPESA"), "amount", b.num("deposit"),
                            "reference", b.str("depositReference", ""))))));
        }
        if (booked + pax >= seats) {
            db.exec("UPDATE trips SET status = 'FULL' WHERE id = :id AND status = 'OPEN'", "id", id);
        }
        return db.one("Booking", "SELECT * FROM trip_bookings WHERE id = :bookingId", "bookingId", bookingId);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID bookingId) {
        Map<String, Object> bk = db.one("Booking", "SELECT * FROM trip_bookings WHERE id = :bookingId", "bookingId", bookingId);
        db.exec("UPDATE trip_bookings SET status = 'CANCELLED' WHERE id = :bookingId", "bookingId", bookingId);
        db.exec("UPDATE trips SET status = 'OPEN' WHERE id = :trip AND status = 'FULL'", "trip", bk.get("tripId"));
        if (bk.get("orderId") != null) {
            UUID order = UUID.fromString(bk.get("orderId").toString());
            BigDecimal paid = db.value("SELECT paid FROM orders WHERE id = :order", BigDecimal.class, "order", order);
            if (paid == null || paid.signum() == 0) {
                orders.voidOrder(order, "Booking cancelled");
            }
        }
        return db.one("Booking", "SELECT * FROM trip_bookings WHERE id = :bookingId", "bookingId", bookingId);
    }
}
