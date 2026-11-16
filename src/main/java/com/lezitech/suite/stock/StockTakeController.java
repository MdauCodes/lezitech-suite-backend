package com.lezitech.suite.stock;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Two stock jobs every kind of business has:
 * <ul>
 *   <li>The full stock-take: start one for a place (and maybe one category), count on one or more phones (blind by
 *   default: counters don't see what the records expect), then finish. Counted lines become the stock; what's missing
 *   or extra is kept with its value at cost, and a big shortage is flagged in the activity log.</li>
 *   <li>Stock used in-house: soap and toilet paper for the rooms, cleaning supplies, towels or linen damaged, products
 *   used on a client. It leaves stock with what it was for, and a report adds it up.</li>
 * </ul>
 */
@RestController
@Transactional
public class StockTakeController {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final Set<String> MANAGE = Set.of("OWNER", "MANAGER", "STOREKEEPER", "ACCOUNTANT");

    private final Db db;
    private final StockService stock;
    private final ActivityLog activity;

    public StockTakeController(Db db, StockService stock, ActivityLog activity) {
        this.db = db;
        this.stock = stock;
        this.activity = activity;
    }

    private static boolean canSeeExpected() {
        return MANAGE.contains(TenantContext.caller().role());
    }

    // ---------- the stock-take ----------

    @GetMapping("/api/stock/counts")
    public List<Map<String, Object>> counts() {
        return db.list("""
                SELECT c.id, c.number, c.status, c.blind, c.note, c.started_at, c.finished_at, c.missing_value, c.extra_value,
                       l.name AS location_name, cat.name AS category_name, su.name AS started_by_name, fu.name AS finished_by_name,
                       (SELECT count(*) FROM stock_count_lines x WHERE x.count_id = c.id) AS lines,
                       (SELECT count(*) FROM stock_count_lines x WHERE x.count_id = c.id AND x.counted IS NOT NULL) AS counted
                FROM stock_counts c JOIN locations l ON l.id = c.location_id LEFT JOIN categories cat ON cat.id = c.category_id
                LEFT JOIN users su ON su.id = c.started_by LEFT JOIN users fu ON fu.id = c.finished_by
                ORDER BY c.status = 'OPEN' DESC, c.number DESC LIMIT 30""");
    }

    /** {locationId?, categoryId?, blind (default true), note}: every stocked item there goes on the count sheet. */
    @PostMapping("/api/stock/counts")
    public Map<String, Object> start(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER", "ACCOUNTANT");
        Body b = new Body(raw);
        UUID loc = b.has("locationId") && !b.str("locationId").isBlank() ? UUID.fromString(b.str("locationId")) : stock.defaultLocation();
        UUID cat = b.has("categoryId") && !b.str("categoryId").isBlank() ? UUID.fromString(b.str("categoryId")) : null;
        if (Boolean.TRUE.equals(db.value("SELECT exists(SELECT 1 FROM stock_counts WHERE status = 'OPEN' AND location_id = :loc)", Boolean.class, "loc", loc))) {
            throw ApiException.conflict("A stock-take is already open for this place. Finish or cancel it first");
        }
        Integer number = db.value("SELECT coalesce(max(number), 0) + 1 FROM stock_counts", Integer.class);
        UUID id = db.insert("""
                INSERT INTO stock_counts (tenant_id, number, location_id, category_id, blind, note, started_by)
                VALUES (:t, :n, :loc, :cat, :blind, :note, :u) RETURNING id""",
                "n", number, "loc", loc, "cat", cat, "blind", b.bool("blind", true), "note", b.str("note", null));
        int lines = db.exec("""
                INSERT INTO stock_count_lines (tenant_id, count_id, item_id)
                SELECT :t, :id, i.id FROM items i WHERE i.active AND i.track_stock
                  AND (cast(:cat as uuid) IS NULL OR i.category_id = cast(:cat as uuid))""", "id", id, "cat", cat);
        if (lines == 0) {
            db.exec("DELETE FROM stock_counts WHERE id = :id", "id", id);
            throw ApiException.bad("Nothing here counts stock yet. Tick Count how many I have on your products first");
        }
        return sheet(id);
    }

    /** The count sheet: each item, what was counted and by whom; managers also see what the records expect now. */
    @GetMapping("/api/stock/counts/{id}")
    public Map<String, Object> sheet(@PathVariable UUID id) {
        Map<String, Object> c = db.one("Stock-take", """
                SELECT c.*, l.name AS location_name, cat.name AS category_name FROM stock_counts c
                JOIN locations l ON l.id = c.location_id LEFT JOIN categories cat ON cat.id = c.category_id WHERE c.id = :id""", "id", id);
        boolean open = "OPEN".equals(c.get("status"));
        boolean showExpected = !open || !Boolean.TRUE.equals(c.get("blind")) || canSeeExpected();
        List<Map<String, Object>> lines = db.list("""
                SELECT x.item_id, i.name, i.unit, i.barcode, cat.name AS category_name, x.counted, x.counted_at, u.name AS counted_by_name,
                       coalesce(x.expected, (SELECT qty FROM stock_levels s WHERE s.item_id = x.item_id AND s.location_id = :loc), 0) AS expected,
                       coalesce(x.unit_cost, i.cost) AS unit_cost
                FROM stock_count_lines x JOIN items i ON i.id = x.item_id LEFT JOIN categories cat ON cat.id = i.category_id
                LEFT JOIN users u ON u.id = x.counted_by
                WHERE x.count_id = :id ORDER BY cat.name NULLS LAST, i.name""", "id", id, "loc", c.get("locationId"));
        BigDecimal missing = BigDecimal.ZERO, extra = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            BigDecimal counted = (BigDecimal) l.get("counted");
            BigDecimal expected = (BigDecimal) l.get("expected");
            if (counted != null) {
                BigDecimal diff = counted.subtract(expected);
                BigDecimal value = diff.multiply((BigDecimal) l.get("unitCost")).setScale(2, RoundingMode.HALF_UP);
                l.put("difference", diff);
                l.put("value", value);
                if (value.signum() < 0) missing = missing.add(value.negate()); else extra = extra.add(value);
            }
            if (!showExpected) {
                l.remove("expected");
                l.remove("difference");
                l.remove("value");
                l.remove("unitCost");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(c);
        out.put("lines", lines);
        out.put("showExpected", showExpected);
        if (showExpected && open) {
            out.put("missingSoFar", missing);
            out.put("extraSoFar", extra);
        }
        return out;
    }

    /** {itemId, counted}: one line counted (or cleared with counted null). Anyone who works with stock. */
    @PostMapping("/api/stock/counts/{id}/line")
    public Map<String, Object> countLine(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        requireOpen(id);
        BigDecimal counted = raw.get("counted") == null || String.valueOf(raw.get("counted")).isBlank() ? null : b.num("counted");
        if (counted != null && counted.signum() < 0) {
            throw ApiException.bad("A count can't be below zero");
        }
        int n = db.exec("""
                UPDATE stock_count_lines SET counted = cast(:c as numeric), counted_by = CASE WHEN cast(:c as numeric) IS NULL THEN NULL ELSE cast(:u as uuid) END,
                       counted_at = CASE WHEN cast(:c as numeric) IS NULL THEN NULL ELSE now() END
                WHERE count_id = :id AND item_id = :item""", "id", id, "item", UUID.fromString(b.str("itemId")), "c", counted);
        if (n == 0) {
            throw ApiException.notFound("That item on this stock-take");
        }
        return Map.of("ok", true);
    }

    /** Finish: counted lines become the stock (uncounted ones stay as they are); differences are kept and reported. */
    @PostMapping("/api/stock/counts/{id}/finish")
    public Map<String, Object> finish(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER", "ACCOUNTANT");
        Map<String, Object> c = requireOpen(id);
        UUID loc = UUID.fromString(c.get("locationId").toString());
        String ref = "Stock-take #" + c.get("number");
        BigDecimal missing = BigDecimal.ZERO, extra = BigDecimal.ZERO;
        StringBuilder worst = new StringBuilder();
        int counted = 0;
        for (Map<String, Object> l : db.list("""
                SELECT x.item_id, x.counted, i.name, i.unit, i.cost FROM stock_count_lines x JOIN items i ON i.id = x.item_id
                WHERE x.count_id = :id AND x.counted IS NOT NULL ORDER BY i.name""", "id", id)) {
            UUID item = UUID.fromString(l.get("itemId").toString());
            BigDecimal expected = db.value("SELECT coalesce((SELECT qty FROM stock_levels WHERE item_id = :item AND location_id = :loc), 0)",
                    BigDecimal.class, "item", item, "loc", loc);
            BigDecimal diff = ((BigDecimal) l.get("counted")).subtract(expected);
            BigDecimal cost = l.get("cost") == null ? BigDecimal.ZERO : (BigDecimal) l.get("cost");
            BigDecimal value = diff.multiply(cost).setScale(2, RoundingMode.HALF_UP);
            db.exec("UPDATE stock_count_lines SET expected = :e, unit_cost = :uc WHERE count_id = :id AND item_id = :item",
                    "id", id, "item", item, "e", expected, "uc", cost);
            stock.move(item, loc, diff, "ADJUST", ref, null, true);
            counted++;
            if (value.signum() < 0) {
                missing = missing.add(value.negate());
                if (worst.length() < 200) {
                    worst.append(worst.length() == 0 ? "" : ", ").append(l.get("name")).append(" ")
                            .append(diff.negate().stripTrailingZeros().toPlainString()).append(" ").append(l.get("unit"));
                }
            } else {
                extra = extra.add(value);
            }
        }
        if (counted == 0) {
            throw ApiException.bad("Nothing has been counted yet. Count at least one item, or cancel this stock-take");
        }
        db.exec("""
                UPDATE stock_counts SET status = 'DONE', finished_by = cast(:u as uuid), finished_at = now(), missing_value = :m, extra_value = :x
                WHERE id = :id""", "id", id, "m", missing, "x", extra);
        if (missing.signum() > 0) {
            activity.record("STOCK_COUNT", missing.compareTo(new BigDecimal("500")) >= 0 ? ActivityLog.CONCERN : ActivityLog.NOTICE,
                    ref + ": missing " + worst + " (worth " + missing.toPlainString() + " at cost)", null, null, missing, null, Map.of());
        }
        return sheet(id);
    }

    @PostMapping("/api/stock/counts/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER", "ACCOUNTANT");
        requireOpen(id);
        db.exec("UPDATE stock_counts SET status = 'CANCELLED', finished_by = cast(:u as uuid), finished_at = now() WHERE id = :id", "id", id);
        return Map.of("ok", true);
    }

    private Map<String, Object> requireOpen(UUID id) {
        Map<String, Object> c = db.one("Stock-take", "SELECT id, number, status, location_id FROM stock_counts WHERE id = :id", "id", id);
        if (!"OPEN".equals(c.get("status"))) {
            throw ApiException.conflict("This stock-take is already " + String.valueOf(c.get("status")).toLowerCase());
        }
        return c;
    }

    // ---------- stock used in-house ----------

    /** {itemId, qty, purpose, note, locationId?}: e.g. 4 toilet rolls, Housekeeping · Room 12. */
    @PostMapping("/api/stock/use")
    public Map<String, Object> use(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        UUID item = UUID.fromString(b.str("itemId"));
        BigDecimal qty = b.num("qty", null);
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.bad("Say how many were used");
        }
        String purpose = b.str("purpose", "").trim();
        if (purpose.isEmpty()) {
            throw ApiException.bad("Say what it was used for, e.g. Housekeeping or Room 12");
        }
        if (purpose.length() > 80) {
            purpose = purpose.substring(0, 80);
        }
        UUID loc = b.has("locationId") && !b.str("locationId").isBlank() ? UUID.fromString(b.str("locationId")) : stock.defaultLocation();
        stock.move(item, loc, qty.negate(), "USE", purpose, b.str("note", null));
        return Map.of("ok", true);
    }

    /** What was used in-house in the dates, by purpose and item, with its value at cost. */
    @GetMapping("/api/stock/used")
    public Map<String, Object> used(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate f = from == null ? LocalDate.now(NAIROBI).minusDays(29) : from;
        LocalDate t = to == null ? LocalDate.now(NAIROBI) : to;
        Object[] range = {"from", java.sql.Timestamp.from(f.atStartOfDay(NAIROBI).toInstant()), "to", java.sql.Timestamp.from(t.plusDays(1).atStartOfDay(NAIROBI).toInstant())};
        List<Map<String, Object>> rows = db.list("""
                SELECT m.ref AS purpose, i.name AS item_name, i.unit, -sum(m.qty) AS qty, round(-sum(m.qty) * i.cost, 2) AS value
                FROM stock_moves m JOIN items i ON i.id = m.item_id
                WHERE m.reason = 'USE' AND m.created_at >= :from AND m.created_at < :to
                GROUP BY m.ref, i.name, i.unit, i.cost ORDER BY m.ref, value DESC""", range);
        BigDecimal total = rows.stream().map(r -> (BigDecimal) r.get("value")).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> purposes = db.list("""
                SELECT m.ref FROM stock_moves m WHERE m.reason = 'USE' AND m.ref IS NOT NULL GROUP BY m.ref ORDER BY max(m.created_at) DESC LIMIT 12""")
                .stream().map(r -> String.valueOf(r.get("ref"))).toList();
        return Map.of("from", f.toString(), "to", t.toString(), "rows", rows, "total", total, "recentPurposes", purposes);
    }
}
