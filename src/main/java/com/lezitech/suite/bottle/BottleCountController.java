package com.lezitech.suite.bottle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.restaurant.NightSummary;
import com.lezitech.suite.sales.TillRules;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.stock.StockService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The nightly bottle count for bars: a short list of the drinks to count (drink categories, the bar's own stock, or
 * anything the owner marked "Count every night"), each with what the system expects on the shelf. On save, what is
 * found goes through the same stock adjustment a stock-take uses (so the stock history shows it), the count is kept,
 * and a shortage worth more than {@link #SMALL_SHORTAGE} goes to the Activity log as a concern and into the owner's
 * night summary. Closing the till without a count today shows a reminder, or stops the closing if the owner chose that.
 */
@RestController
@RequestMapping("/api/bottle-counts")
@Transactional
public class BottleCountController {

    /** A shortage worth more than this (at cost) is a concern in the Activity log; less is a notice. */
    static final BigDecimal SMALL_SHORTAGE = new BigDecimal("100");

    /** Category names that count as drinks. */
    static final String DRINKS = "beer|lager|cider|wine|spirit|whisky|whiskey|vodka|gin|rum|brandy|liqueur|tequila|champagne|soda|juice|drink|beverage|liquor";

    private final Db db;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SectorCatalog sectors;
    private final StockService stock;
    private final ActivityLog activity;

    public BottleCountController(Db db, JdbcTemplate jdbc, ObjectMapper json, SectorCatalog sectors, StockService stock, ActivityLog activity) {
        this.db = db;
        this.jdbc = jdbc;
        this.json = json;
        this.sectors = sectors;
        this.stock = stock;
        this.activity = activity;
    }

    // ---------------- the switch and the rule ----------------

    /** The owner's saved choice, else what the sub-sector has. */
    public boolean enabled() {
        Map<String, Object> t = jdbc.queryForMap("SELECT sector, sub_sector, settings::text AS s FROM tenants WHERE id = ?", TenantContext.tenantId());
        try {
            JsonNode v = json.readTree((String) t.get("s")).path("features").path("bottleCount");
            if (v.isBoolean()) {
                return v.asBoolean();
            }
        } catch (Exception ignored) {
            // fall back to the sub-sector default
        }
        return sectors.get((String) t.get("sector"), (String) t.get("sub_sector")).path("features").path("bottleCount").asBoolean(false);
    }

    private void requireOn() {
        if (!enabled()) {
            throw ApiException.forbidden("The bottle count is switched off for this business. The owner can switch it on in Settings.");
        }
    }

    /** Owner's choice: closing the till without a count today is stopped (true) or only reminded (false, the default). */
    public boolean hardStop() {
        Boolean v = jdbc.queryForObject("SELECT coalesce((settings->'bottleCount'->>'hardStop')::boolean, false) FROM tenants WHERE id = ?",
                Boolean.class, TenantContext.tenantId());
        return Boolean.TRUE.equals(v);
    }

    static LocalDate today() {
        return NightSummary.businessDay(Instant.now());
    }

    private boolean countedOn(LocalDate day) {
        return Boolean.TRUE.equals(db.value("SELECT exists(SELECT 1 FROM bottle_counts WHERE day = :d)", Boolean.class, "d", day));
    }

    /** What to count: id, name, category, stock-level quantity, cost, and how many stock units make one bottle. */
    private List<Map<String, Object>> countable(UUID location) {
        List<Map<String, Object>> rows = db.list("""
                SELECT i.id, i.name, i.unit, i.cost, i.buy_unit, i.buy_size, c.name AS category_name,
                       coalesce((SELECT s.qty FROM stock_levels s WHERE s.item_id = i.id AND s.location_id = :loc), 0) AS qty
                FROM items i LEFT JOIN categories c ON c.id = i.category_id
                WHERE i.active AND i.track_stock AND i.variant_axes IS NULL AND i.kind <> 'SERVICE'
                  AND (i.count_nightly OR i.area = 'BAR' OR c.name ~* :drinks)
                ORDER BY c.name NULLS LAST, i.name""", "loc", location, "drinks", DRINKS);
        for (Map<String, Object> r : rows) {
            BigDecimal size = (BigDecimal) r.get("buySize");
            boolean open = size != null && size.compareTo(BigDecimal.ONE) > 0 && r.get("buyUnit") != null;
            r.put("factor", open ? size : BigDecimal.ONE);
            r.put("hasOpen", open);
        }
        return rows;
    }

    // ---------------- the sheet and the status ----------------

    /** Is a count due? Used by the closing screen and the till. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean on = enabled();
        out.put("enabled", on);
        out.put("hardStop", on && hardStop());
        boolean has = on && !countable(stock.defaultLocation()).isEmpty();
        boolean counted = on && countedOn(today());
        out.put("hasItems", has);
        out.put("countedToday", counted);
        out.put("due", on && has && !counted);
        return out;
    }

    /** The list to count, with what the system expects on the shelf. */
    @GetMapping("/sheet")
    public Map<String, Object> sheet() {
        requireOn();
        boolean values = TillRules.seesTotals();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : countable(stock.defaultLocation())) {
            BigDecimal factor = (BigDecimal) r.get("factor");
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("itemId", r.get("id"));
            o.put("name", r.get("name"));
            o.put("category", r.get("categoryName"));
            o.put("expected", ((BigDecimal) r.get("qty")).divide(factor, 2, RoundingMode.HALF_UP));
            o.put("hasOpen", r.get("hasOpen"));
            if (values && r.get("cost") != null) {
                o.put("costPerBottle", ((BigDecimal) r.get("cost")).multiply(factor).setScale(2, RoundingMode.HALF_UP));
            }
            items.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", today());
        out.put("items", items);
        out.put("countedToday", countedOn(today()));
        if (items.isEmpty()) {
            out.put("pickable", db.list("""
                    SELECT i.id AS item_id, i.name, c.name AS category FROM items i LEFT JOIN categories c ON c.id = i.category_id
                    WHERE i.active AND i.variant_axes IS NULL AND i.kind NOT IN ('SERVICE', 'INGREDIENT', 'BATCH') AND NOT i.count_nightly
                      AND (i.area = 'BAR' OR i.station = 'Bar' OR c.name ~* :drinks) ORDER BY c.name NULLS LAST, i.name""", "drinks", DRINKS));
        }
        return out;
    }

    /** {itemIds}: the owner or a manager picks which drinks to count every night (they start counting stock too). */
    @PostMapping("/pick")
    public Map<String, Object> pick(@RequestBody Map<String, Object> raw) {
        requireOn();
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        List<?> ids = raw.get("itemIds") instanceof List<?> l ? l : List.of();
        if (ids.isEmpty()) {
            throw ApiException.bad("Tick at least one drink");
        }
        int n = 0;
        for (Object id : ids) {
            n += db.exec("UPDATE items SET count_nightly = true, track_stock = true WHERE id = cast(:id as uuid) AND active AND kind <> 'SERVICE'", "id", String.valueOf(id));
        }
        return Map.of("picked", n);
    }

    // ---------------- saving a count ----------------

    /** {lines: [{itemId, whole, open?}], note?}: whole bottles, and the part of an open bottle left (0 to under 1). Blank lines are left out. */
    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> raw) {
        requireOn();
        Body body = new Body(raw);
        UUID loc = stock.defaultLocation();
        Map<UUID, Map<String, Object>> allowed = new LinkedHashMap<>();
        for (Map<String, Object> r : countable(loc)) {
            allowed.put(UUID.fromString(r.get("id").toString()), r);
        }
        List<Map<String, Object>> lines = body.list("lines");
        if (lines == null || lines.isEmpty()) {
            throw ApiException.bad("Count at least one bottle first");
        }
        LocalDate day = today();
        Map<UUID, BigDecimal> counted = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            Body lb = new Body(l);
            if (!lb.has("whole") || String.valueOf(l.get("whole")).isBlank()) {
                continue;
            }
            UUID id = UUID.fromString(lb.str("itemId"));
            Map<String, Object> item = allowed.get(id);
            if (item == null) {
                throw ApiException.bad("That item isn't on tonight's count");
            }
            BigDecimal whole = lb.num("whole");
            BigDecimal open = lb.has("open") && !String.valueOf(l.get("open")).isBlank() ? lb.num("open") : BigDecimal.ZERO;
            if (whole.signum() < 0 || whole.stripTrailingZeros().scale() > 0) {
                throw ApiException.bad("Whole bottles must be a number like 0, 3 or 12");
            }
            if (open.signum() < 0 || open.compareTo(BigDecimal.ONE) >= 0) {
                throw ApiException.bad("The open bottle is the part left, such as half: more than 0 and less than a full bottle");
            }
            if (open.signum() > 0 && !Boolean.TRUE.equals(item.get("hasOpen"))) {
                throw ApiException.bad(item.get("name") + " isn't tracked by the tot, so count whole bottles only");
            }
            counted.put(id, whole.add(open));
        }
        if (counted.isEmpty()) {
            throw ApiException.bad("Count at least one bottle first");
        }

        boolean values = TillRules.seesTotals();
        TenantContext.Caller c = TenantContext.caller();
        UUID countId = db.insert("""
                INSERT INTO bottle_counts (tenant_id, day, location_id, counted_by, counted_by_name, note)
                VALUES (:t, :day, :loc, cast(:u as uuid), :name, :note) RETURNING id""",
                "day", day, "loc", loc, "name", c.name(), "note", body.str("note", null));
        BigDecimal missingBottles = BigDecimal.ZERO, missingValue = BigDecimal.ZERO, extraValue = BigDecimal.ZERO;
        List<Map<String, Object>> out = new ArrayList<>();
        StringBuilder worst = new StringBuilder();
        for (Map.Entry<UUID, BigDecimal> e : counted.entrySet()) {
            Map<String, Object> item = allowed.get(e.getKey());
            BigDecimal factor = (BigDecimal) item.get("factor");
            BigDecimal expectedQty = db.value("SELECT coalesce((SELECT qty FROM stock_levels WHERE item_id = :item AND location_id = :loc), 0)",
                    BigDecimal.class, "item", e.getKey(), "loc", loc);
            BigDecimal countedQty = e.getValue().multiply(factor);
            BigDecimal diffQty = countedQty.subtract(expectedQty);
            BigDecimal cost = item.get("cost") == null ? BigDecimal.ZERO : (BigDecimal) item.get("cost");
            BigDecimal value = diffQty.multiply(cost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal expected = expectedQty.divide(factor, 3, RoundingMode.HALF_UP);
            BigDecimal diff = diffQty.divide(factor, 3, RoundingMode.HALF_UP);
            db.exec("""
                    INSERT INTO bottle_count_lines (tenant_id, count_id, item_id, item_name, expected, counted, difference, value)
                    VALUES (:t, :c, :item, :name, :exp, :cnt, :diff, :val)""",
                    "c", countId, "item", e.getKey(), "name", item.get("name"), "exp", expected, "cnt", e.getValue(), "diff", diff, "val", value);
            stock.move(e.getKey(), loc, diffQty, "ADJUST", "Bottle count " + day, null, true);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("itemId", e.getKey());
            o.put("name", item.get("name"));
            o.put("expected", expected.stripTrailingZeros());
            o.put("counted", e.getValue().stripTrailingZeros());
            o.put("difference", diff.stripTrailingZeros());
            o.put("text", words(diff, (String) item.get("name"), value, values));
            if (values) {
                o.put("value", value);
            }
            out.add(o);
            if (diff.signum() < 0) {
                missingBottles = missingBottles.add(diff.negate());
                missingValue = missingValue.add(value.negate());
                if (worst.length() < 160) {
                    worst.append(worst.length() == 0 ? "" : ", ").append(bottles(diff.negate())).append(" of ").append(item.get("name"));
                }
            } else if (diff.signum() > 0) {
                extraValue = extraValue.add(value);
            }
        }
        db.exec("UPDATE bottle_counts SET missing_bottles = :mb, missing_value = :mv, extra_value = :ev WHERE id = :id",
                "id", countId, "mb", missingBottles, "mv", missingValue, "ev", extraValue);
        if (missingBottles.signum() > 0) {
            activity.record("BOTTLE_COUNT", missingValue.compareTo(SMALL_SHORTAGE) > 0 ? ActivityLog.CONCERN : ActivityLog.NOTICE,
                    "Bottle count: " + worst + " missing (worth " + kes(missingValue) + " at cost)", null, null, missingValue, null,
                    Map.of("count", countId.toString(), "day", day.toString()));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", countId);
        res.put("day", day);
        res.put("lines", out);
        res.put("missingBottles", missingBottles.stripTrailingZeros());
        res.put("allThere", missingBottles.signum() == 0);
        if (values) {
            res.put("missingValue", missingValue);
            res.put("extraValue", extraValue);
        }
        res.put("summary", missingBottles.signum() == 0 ? "All the bottles are there."
                : "Missing: " + bottles(missingBottles) + (values ? ", worth " + kes(missingValue) : "") + ". Stock has been corrected.");
        return res;
    }

    /** "2 bottles of Tusker missing, worth KES 500" (the worth only for those who may see costs). */
    static String words(BigDecimal diff, String name, BigDecimal value, boolean withValue) {
        if (diff.signum() == 0) {
            return name + ": all there";
        }
        String s = bottles(diff.abs()) + " of " + name + (diff.signum() < 0 ? " missing" : " extra");
        return withValue ? s + ", worth " + kes(value.abs()) : s;
    }

    static String bottles(BigDecimal n) {
        BigDecimal v = n.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return v.toPlainString() + (v.compareTo(BigDecimal.ONE) == 0 ? " bottle" : " bottles");
    }

    static String kes(BigDecimal v) {
        return "KES " + String.format("%,d", v.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    // ---------------- closing the till ----------------

    /** Called when a till is closed: with the hard stop on and no count today, closing is refused. */
    public void requireBeforeClose() {
        if (!enabled() || !hardStop() || countedOn(today()) || countable(stock.defaultLocation()).isEmpty()) {
            return;
        }
        throw new ApiException(HttpStatus.CONFLICT, "BOTTLE_COUNT_FIRST",
                "Count the bottles first. The owner has made the nightly bottle count a must before the till closes.");
    }

    // ---------------- history and the night summary ----------------

    /** The last 30 nights: who counted, what went missing, with totals. For the owner, managers and the accountant. */
    @GetMapping("/history")
    public Map<String, Object> history() {
        requireOn();
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        LocalDate from = today().minusDays(29);
        List<Map<String, Object>> nights = db.list("""
                SELECT day, count(*) AS counts, string_agg(DISTINCT counted_by_name, ', ') AS counted_by,
                       sum(missing_bottles) AS missing_bottles, sum(missing_value) AS missing_value, sum(extra_value) AS extra_value,
                       max(counted_at) AS counted_at
                FROM bottle_counts WHERE day >= :from GROUP BY day ORDER BY day DESC""", "from", from);
        for (Map<String, Object> n : nights) {
            n.put("missing", db.list("""
                    SELECT l.item_name AS name, -l.difference AS bottles, -l.value AS value FROM bottle_count_lines l
                    JOIN bottle_counts c ON c.id = l.count_id WHERE c.day = cast(:d as date) AND l.difference < 0 ORDER BY l.value""", "d", String.valueOf(n.get("day"))));
        }
        BigDecimal mb = BigDecimal.ZERO, mv = BigDecimal.ZERO;
        for (Map<String, Object> n : nights) {
            mb = mb.add((BigDecimal) n.get("missingBottles"));
            mv = mv.add((BigDecimal) n.get("missingValue"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nights", nights);
        out.put("nightsCounted", nights.size());
        out.put("missingBottles", mb.stripTrailingZeros());
        out.put("missingValue", mv);
        return out;
    }

    /** For the night summary: null when the feature is off, else what happened that day. */
    public Map<String, Object> nightLine(LocalDate day) {
        if (!enabled()) {
            return null;
        }
        Map<String, Object> r = db.one("Bottle count", """
                SELECT count(*) AS counts, coalesce(sum(missing_bottles), 0) AS missing_bottles, coalesce(sum(missing_value), 0) AS missing_value
                FROM bottle_counts WHERE day = :d""", "d", day);
        boolean counted = ((Number) r.get("counts")).longValue() > 0;
        BigDecimal mb = (BigDecimal) r.get("missingBottles");
        BigDecimal mv = (BigDecimal) r.get("missingValue");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counted", counted);
        out.put("missingBottles", mb.stripTrailingZeros());
        out.put("missingValue", mv);
        out.put("text", !counted ? "Bottle count: not done"
                : mb.signum() == 0 ? "Bottle count: all there"
                : "Bottle count: " + mb.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " missing, " + kes(mv));
        return out;
    }

    // ---------------- the owner's rule ----------------

    /** {hardStop}: owner only. */
    @PostMapping("/settings")
    public Map<String, Object> settings(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole();
        boolean hard = new Body(raw).bool("hardStop", false);
        jdbc.update("UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{bottleCount}', cast(? as jsonb)) WHERE id = ?",
                "{\"hardStop\":" + hard + "}", TenantContext.tenantId());
        return Map.of("hardStop", hardStop());
    }
}
