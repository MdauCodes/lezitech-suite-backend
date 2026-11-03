package com.lezitech.suite.rooms;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reservations for rooms, cottages and halls. Each stay owns a folio (an open order) from the
 * moment it's booked, so deposits, restaurant bills and room nights all land on one bill.
 */
@Service
@Transactional
public class StayService {

    private final Db db;
    private final OrderService orders;

    public StayService(Db db, OrderService orders) {
        this.db = db;
        this.orders = orders;
    }

    public UUID reserve(Body b) {
        UUID spaceId = UUID.fromString(b.str("spaceId"));
        LocalDate in = b.date("checkIn");
        LocalDate out = b.date("checkOut");
        if (in == null || out == null || !out.isAfter(in)) {
            throw ApiException.bad("Check-out must be after check-in");
        }
        assertFree(spaceId, in, out, null);
        Map<String, Object> space = db.one("Room", "SELECT * FROM spaces WHERE id = :spaceId", "spaceId", spaceId);
        String guest = b.str("guestName");
        UUID customerId = b.has("customerId") ? UUID.fromString(b.str("customerId")) : null;
        if (customerId == null && b.has("phone")) {
            customerId = db.value("SELECT id FROM customers WHERE phone = :phone LIMIT 1", UUID.class, "phone", b.str("phone"));
        }
        if (customerId == null) {
            customerId = db.insert("INSERT INTO customers (tenant_id, name, phone) VALUES (:t, :name, :phone) RETURNING id",
                    "name", guest, "phone", b.str("phone", null));
        }
        Map<String, Object> folioBody = new HashMap<>();
        folioBody.put("channel", "ROOM");
        folioBody.put("customerId", customerId.toString());
        folioBody.put("note", "Folio: " + space.get("name") + " · " + guest);
        folioBody.put("lines", List.of());
        UUID folio = orders.create(new Body(folioBody));
        UUID id = db.insert("""
                INSERT INTO stays (tenant_id, space_id, customer_id, guest_name, phone, adults, children, check_in, check_out,
                                   rate, board, source, status, notes, folio_id)
                VALUES (:t, :spaceId, :customerId, :guest, :phone, :adults, :children, :in, :out, :rate, :board, :source,
                        :status, :notes, :folio) RETURNING id""",
                "spaceId", spaceId, "customerId", customerId, "guest", guest, "phone", b.str("phone", null),
                "adults", b.integer("adults", 1), "children", b.integer("children", 0), "in", in, "out", out,
                "rate", b.num("rate", (BigDecimal) space.get("rate")), "board", b.str("board", "BB"),
                "source", b.str("source", "Walk-in"), "status", b.str("status", "RESERVED"), "notes", b.str("notes", null),
                "folio", folio);
        db.exec("UPDATE orders SET stay_id = :id WHERE id = :folio", "id", id, "folio", folio);
        return id;
    }

    public void assertFree(UUID spaceId, LocalDate in, LocalDate out, UUID ignoreStay) {
        Map<String, Object> clash = db.find("""
                SELECT guest_name, check_in, check_out FROM stays
                WHERE space_id = :spaceId AND status IN ('RESERVED','IN_HOUSE')
                  AND check_in < :out AND check_out > :in
                  AND (cast(:ignore as uuid) IS NULL OR id <> cast(:ignore as uuid)) LIMIT 1""",
                "spaceId", spaceId, "in", in, "out", out, "ignore", ignoreStay).orElse(null);
        if (clash != null) {
            throw ApiException.conflict("Already booked by " + clash.get("guestName") + " from "
                    + clash.get("checkIn") + " to " + clash.get("checkOut"));
        }
    }

    public void checkIn(UUID id) {
        Map<String, Object> s = stay(id);
        if (!"RESERVED".equals(s.get("status"))) {
            throw ApiException.conflict("Only reserved stays can check in");
        }
        Object hk = db.value("SELECT housekeeping FROM spaces WHERE id = :spaceId", String.class, "spaceId", s.get("spaceId"));
        if ("OUT_OF_ORDER".equals(hk)) {
            throw ApiException.conflict("This room is out of order; move the guest first");
        }
        db.exec("UPDATE stays SET status = 'IN_HOUSE', check_in = least(check_in, current_date) WHERE id = :id", "id", id);
    }

    /** Posts the room nights to the folio and frees the room for housekeeping. Payment happens on the folio. */
    public UUID checkOut(UUID id) {
        Map<String, Object> s = stay(id);
        if (!"IN_HOUSE".equals(s.get("status"))) {
            throw ApiException.conflict("Only in-house guests can check out");
        }
        LocalDate in = LocalDate.parse(s.get("checkIn").toString());
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"));
        LocalDate out = today.isAfter(in) ? today : in.plusDays(1);
        long nights = Math.max(1, ChronoUnit.DAYS.between(in, out));
        UUID folio = UUID.fromString(s.get("folioId").toString());
        String room = db.value("SELECT name FROM spaces WHERE id = :spaceId", String.class, "spaceId", s.get("spaceId"));
        Map<String, Object> line = new HashMap<>();
        line.put("name", "Room " + room + " · " + nights + " night" + (nights == 1 ? "" : "s") + " (" + s.get("board") + ")");
        line.put("unitPrice", s.get("rate"));
        line.put("qty", nights);
        line.put("taxRate", 16);
        orders.addLines(folio, List.of(line));
        // Early or late departures shorten or extend the stay to the real date.
        db.exec("UPDATE stays SET status = 'CHECKED_OUT', check_out = :out WHERE id = :id", "id", id, "out", out);
        db.exec("UPDATE spaces SET housekeeping = 'DIRTY' WHERE id = :spaceId", "spaceId", s.get("spaceId"));
        return folio;
    }

    public void cancel(UUID id, String status) {
        Map<String, Object> s = stay(id);
        if (!"RESERVED".equals(s.get("status"))) {
            throw ApiException.conflict("Only reservations not yet checked in can be cancelled");
        }
        db.exec("UPDATE stays SET status = :status WHERE id = :id", "id", id, "status", status);
        if (s.get("folioId") != null) {
            UUID folio = UUID.fromString(s.get("folioId").toString());
            BigDecimal paid = db.value("SELECT paid FROM orders WHERE id = :folio", BigDecimal.class, "folio", folio);
            if (paid == null || paid.signum() == 0) {
                orders.voidOrder(folio, "Reservation " + status.toLowerCase().replace('_', ' '));
            }
        }
    }

    public void move(UUID id, UUID spaceId, LocalDate in, LocalDate out) {
        Map<String, Object> s = stay(id);
        LocalDate newIn = in == null ? LocalDate.parse(s.get("checkIn").toString()) : in;
        LocalDate newOut = out == null ? LocalDate.parse(s.get("checkOut").toString()) : out;
        if (!newOut.isAfter(newIn)) {
            throw ApiException.bad("Check-out must be after check-in");
        }
        UUID target = spaceId == null ? UUID.fromString(s.get("spaceId").toString()) : spaceId;
        assertFree(target, newIn, newOut, id);
        db.exec("UPDATE stays SET space_id = :target, check_in = :in, check_out = :out WHERE id = :id",
                "id", id, "target", target, "in", newIn, "out", newOut);
    }

    private Map<String, Object> stay(UUID id) {
        return db.one("Stay", "SELECT * FROM stays WHERE id = :id", "id", id);
    }
}
