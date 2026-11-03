package com.lezitech.suite.rooms;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Rooms, halls and cottages: the room chart, front desk and housekeeping (cf. Odoo/ERPNext hotel modules). */
@RestController
@RequestMapping("/api")
@Transactional
public class RoomController {

    static final String STAY_KINDS = "('ROOM','COTTAGE','TENT','HALL','APARTMENT')";
    private static final Set<String> HOUSEKEEPING = Set.of("CLEAN", "DIRTY", "INSPECT", "OUT_OF_ORDER");

    private final Db db;
    private final StayService stays;

    public RoomController(Db db, StayService stays) {
        this.db = db;
        this.stays = stays;
    }

    // ---------- spaces (shared with property) ----------

    @GetMapping("/spaces")
    public List<Map<String, Object>> spaces(@RequestParam(defaultValue = "stay") String group) {
        String kinds = "property".equals(group) ? "('SHOP','STALL','OFFICE','APARTMENT')" : STAY_KINDS;
        return db.list("SELECT * FROM spaces WHERE kind IN " + kinds + " ORDER BY sort, floor NULLS FIRST, name");
    }

    @PostMapping("/spaces")
    public Map<String, Object> createSpace(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        UUID id = db.insert("INSERT INTO spaces (tenant_id, name, sort) VALUES (:t, :name, coalesce((SELECT max(sort) + 1 FROM spaces), 0)) RETURNING id",
                "name", new Body(raw).str("name"));
        return updateSpace(id, raw);
    }

    @PutMapping("/spaces/{id}")
    public Map<String, Object> updateSpace(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        db.exec("""
                UPDATE spaces SET name = :name, kind = :kind, type_name = :type, capacity = :cap, rate = :rate,
                       service_charge = :svc, area_sqft = :area, floor = :floor, active = :active WHERE id = :id""",
                "id", id, "name", b.str("name"), "kind", b.str("kind", "ROOM"), "type", b.str("typeName", null),
                "cap", b.integer("capacity", 2), "rate", b.num("rate", BigDecimal.ZERO), "svc", b.num("serviceCharge", BigDecimal.ZERO),
                "area", b.integer("areaSqft", null), "floor", b.str("floor", null), "active", b.bool("active", true));
        return db.one("Space", "SELECT * FROM spaces WHERE id = :id", "id", id);
    }

    @PatchMapping("/spaces/{id}/housekeeping")
    public Map<String, Object> housekeeping(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        String status = new Body(raw).str("status");
        if (!HOUSEKEEPING.contains(status)) {
            throw ApiException.bad("Unknown housekeeping status");
        }
        db.exec("UPDATE spaces SET housekeeping = :s WHERE id = :id", "id", id, "s", status);
        return db.one("Space", "SELECT * FROM spaces WHERE id = :id", "id", id);
    }

    // ---------- front desk ----------

    /** Room chart: every room with the stays that overlap the window. */
    @GetMapping("/rooms/chart")
    public Map<String, Object> chart(@RequestParam(required = false) LocalDate from, @RequestParam(defaultValue = "14") int days) {
        LocalDate start = from == null ? today().minusDays(1) : from;
        LocalDate end = start.plusDays(Math.min(Math.max(days, 1), 62));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("spaces", db.list("SELECT * FROM spaces WHERE active AND kind IN " + STAY_KINDS + " ORDER BY sort, floor NULLS FIRST, name"));
        out.put("stays", db.list("""
                SELECT s.*, o.total AS folio_total, o.paid AS folio_paid FROM stays s LEFT JOIN orders o ON o.id = s.folio_id
                WHERE s.status IN ('RESERVED','IN_HOUSE','CHECKED_OUT') AND s.check_in < :end AND s.check_out > :start
                ORDER BY s.check_in""", "start", start, "end", end));
        return out;
    }

    @GetMapping("/rooms/today")
    public Map<String, Object> today(@RequestParam(required = false) LocalDate date) {
        LocalDate d = date == null ? today() : date;
        String cols = "s.*, sp.name AS space_name, sp.type_name, o.total AS folio_total, o.paid AS folio_paid";
        String from = " FROM stays s JOIN spaces sp ON sp.id = s.space_id LEFT JOIN orders o ON o.id = s.folio_id ";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arrivals", db.list("SELECT " + cols + from + "WHERE s.status = 'RESERVED' AND s.check_in <= :d ORDER BY s.check_in, sp.name", "d", d));
        out.put("departures", db.list("SELECT " + cols + from + "WHERE s.status = 'IN_HOUSE' AND s.check_out <= :d ORDER BY sp.name", "d", d));
        out.put("inHouse", db.list("SELECT " + cols + from + "WHERE s.status = 'IN_HOUSE' ORDER BY sp.name"));
        out.put("rooms", db.list("""
                SELECT sp.*, (SELECT s.guest_name FROM stays s WHERE s.space_id = sp.id AND s.status = 'IN_HOUSE' LIMIT 1) AS guest_name
                FROM spaces sp WHERE sp.active AND sp.kind IN """ + " " + STAY_KINDS + " ORDER BY sp.sort, sp.floor NULLS FIRST, sp.name"));
        return out;
    }

    /** Free rooms for a date range, for the booking form. */
    @GetMapping("/rooms/available")
    public List<Map<String, Object>> available(@RequestParam LocalDate checkIn, @RequestParam LocalDate checkOut) {
        return db.list("""
                SELECT sp.* FROM spaces sp WHERE sp.active AND sp.kind IN """ + " " + STAY_KINDS + """
                  AND sp.housekeeping <> 'OUT_OF_ORDER'
                  AND NOT EXISTS (SELECT 1 FROM stays s WHERE s.space_id = sp.id AND s.status IN ('RESERVED','IN_HOUSE')
                                  AND s.check_in < :out AND s.check_out > :in)
                ORDER BY sp.sort, sp.name""", "in", checkIn, "out", checkOut);
    }

    @GetMapping("/stays/{id}")
    public Map<String, Object> stay(@PathVariable UUID id) {
        return db.one("Stay", """
                SELECT s.*, sp.name AS space_name, sp.type_name, o.total AS folio_total, o.paid AS folio_paid, o.status AS folio_status
                FROM stays s JOIN spaces sp ON sp.id = s.space_id LEFT JOIN orders o ON o.id = s.folio_id WHERE s.id = :id""", "id", id);
    }

    @GetMapping("/stays/in-house")
    public List<Map<String, Object>> inHouse() {
        return db.list("""
                SELECT s.id, s.guest_name, sp.name AS space_name, s.folio_id FROM stays s JOIN spaces sp ON sp.id = s.space_id
                WHERE s.status = 'IN_HOUSE' ORDER BY sp.name""");
    }

    @PostMapping("/stays")
    public Map<String, Object> reserve(@RequestBody Map<String, Object> raw) {
        return stay(stays.reserve(new Body(raw)));
    }

    @PatchMapping("/stays/{id}")
    public Map<String, Object> change(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        stays.move(id, b.has("spaceId") ? UUID.fromString(b.str("spaceId")) : null, b.date("checkIn"), b.date("checkOut"));
        if (b.has("notes")) {
            db.exec("UPDATE stays SET notes = :n WHERE id = :id", "id", id, "n", b.str("notes"));
        }
        return stay(id);
    }

    @PostMapping("/stays/{id}/check-in")
    public Map<String, Object> checkIn(@PathVariable UUID id) {
        stays.checkIn(id);
        return stay(id);
    }

    @PostMapping("/stays/{id}/check-out")
    public Map<String, Object> checkOut(@PathVariable UUID id) {
        stays.checkOut(id);
        return stay(id);
    }

    @PostMapping("/stays/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        stays.cancel(id, new Body(raw).bool("noShow", false) ? "NO_SHOW" : "CANCELLED");
        return stay(id);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Africa/Nairobi"));
    }
}
