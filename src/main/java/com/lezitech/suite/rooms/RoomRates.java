package com.lezitech.suite.rooms;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Prices that change: high season, weekends, holidays, long stays. Each night of a stay takes the most specific rule
 * that fits (one room, then a room type, then all rooms; a shorter date range wins a longer one); a night no rule
 * fits takes the room's own price.
 */
@Service
@Transactional
public class RoomRates {

    static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    private final Db db;

    public RoomRates(Db db) {
        this.db = db;
    }

    public List<Map<String, Object>> list() {
        return db.list("""
                SELECT r.*, sp.name AS space_name FROM room_rates r LEFT JOIN spaces sp ON sp.id = r.space_id
                ORDER BY r.starts_on NULLS FIRST, r.name""");
    }

    /** {name, spaceId | typeName | neither (all rooms), startsOn, endsOn, weekdays: ["fri","sat"], rate, minNights}. */
    public Map<String, Object> save(UUID id, Body b) {
        TenantContext.requireRole("MANAGER");
        String name = b.str("name", "").trim();
        if (name.isEmpty()) {
            throw ApiException.bad("Give the price a name, like High season or Weekends");
        }
        BigDecimal rate = b.num("rate", null);
        if (rate == null || rate.signum() < 0) {
            throw ApiException.bad("Enter the price per night");
        }
        LocalDate from = b.has("startsOn") ? b.date("startsOn") : null;
        LocalDate to = b.has("endsOn") ? b.date("endsOn") : null;
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.bad("The last day must be on or after the first");
        }
        List<String> days = new ArrayList<>();
        if (b.raw("weekdays") instanceof List<?> l) {
            for (Object o : l) {
                String d = String.valueOf(o).toLowerCase();
                if (!DAYS.contains(d)) {
                    throw ApiException.bad("Unknown day " + o);
                }
                days.add(d);
            }
        }
        int minNights = Math.max(1, Math.min(60, b.integer("minNights", 1)));
        UUID space = b.has("spaceId") ? UUID.fromString(b.str("spaceId")) : null;
        String type = space == null && b.has("typeName") ? b.str("typeName").trim() : null;
        Object[] p = {"name", name, "space", space, "type", type == null || type.isEmpty() ? null : type, "from", from, "to", to,
                "days", days.isEmpty() || days.size() == 7 ? null : String.join(",", days), "rate", rate, "min", minNights, "id", id};
        if (id == null) {
            id = db.insert("""
                    INSERT INTO room_rates (tenant_id, name, space_id, type_name, starts_on, ends_on, weekdays, rate, min_nights)
                    VALUES (:t, :name, :space, :type, :from, :to, :days, :rate, :min) RETURNING id""", p);
        } else if (db.exec("""
                UPDATE room_rates SET name = :name, space_id = :space, type_name = :type, starts_on = :from, ends_on = :to,
                       weekdays = :days, rate = :rate, min_nights = :min WHERE id = :id""", p) == 0) {
            throw ApiException.notFound("Price");
        }
        return db.one("Price", "SELECT * FROM room_rates WHERE id = :id", "id", id);
    }

    public void delete(UUID id) {
        TenantContext.requireRole("MANAGER");
        db.exec("DELETE FROM room_rates WHERE id = :id", "id", id);
    }

    public record Quote(BigDecimal total, int nights, List<Map<String, Object>> byNight, int minNights) {
    }

    /** The price of a stay in one room: each night's price, the total, and the most nights any rule asks for. */
    public Quote quote(UUID spaceId, LocalDate in, LocalDate out) {
        Map<String, Object> room = db.one("Room", "SELECT rate, type_name FROM spaces WHERE id = :id", "id", spaceId);
        List<Map<String, Object>> rules = db.list("""
                SELECT * FROM room_rates
                WHERE (space_id = :space OR (space_id IS NULL AND (type_name IS NULL OR type_name = :type)))
                  AND (starts_on IS NULL OR starts_on < :out) AND (ends_on IS NULL OR ends_on >= :in)
                ORDER BY (space_id IS NOT NULL) DESC, (type_name IS NOT NULL) DESC,
                         (coalesce(ends_on, date '9999-12-31') - coalesce(starts_on, date '0001-01-01')), (weekdays IS NOT NULL) DESC, created_at DESC""",
                "space", spaceId, "type", room.get("typeName"), "in", in, "out", out);
        BigDecimal total = BigDecimal.ZERO;
        int minNights = 1;
        List<Map<String, Object>> nights = new ArrayList<>();
        for (LocalDate d = in; d.isBefore(out); d = d.plusDays(1)) {
            Map<String, Object> rule = null;
            for (Map<String, Object> r : rules) {
                if (fits(r, d)) {
                    rule = r;
                    break;
                }
            }
            BigDecimal price = rule == null ? (BigDecimal) room.get("rate") : (BigDecimal) rule.get("rate");
            if (rule != null) {
                minNights = Math.max(minNights, ((Number) rule.get("minNights")).intValue());
            }
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("date", d.toString());
            n.put("rate", price);
            n.put("rule", rule == null ? null : rule.get("name"));
            nights.add(n);
            total = total.add(price);
        }
        return new Quote(total, nights.size(), nights, minNights);
    }

    private static boolean fits(Map<String, Object> r, LocalDate d) {
        if (r.get("startsOn") != null && d.isBefore(LocalDate.parse(r.get("startsOn").toString()))) {
            return false;
        }
        if (r.get("endsOn") != null && d.isAfter(LocalDate.parse(r.get("endsOn").toString()))) {
            return false;
        }
        Object w = r.get("weekdays");
        return w == null || Set.of(w.toString().split(",")).contains(DAYS.get(d.getDayOfWeek().getValue() - 1));
    }
}
