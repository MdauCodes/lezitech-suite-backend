package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.stock.StockService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * R7, the bakery's day: what came out of the oven, what sold (from the till by itself), and the closing count of what's
 * left. Anything missing is shown in shillings. Leftovers are sold tomorrow at a lower price (moved to the item's
 * "(yesterday's)" twin), given away or thrown (both recorded as waste), or kept (things that last).
 */
@Service
public class Bakery {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final List<String> ACTIONS = List.of("SELL_TOMORROW", "GIVE_AWAY", "WASTE", "KEEP");

    private final Db db;
    private final StockService stock;

    public Bakery(Db db, StockService stock) {
        this.db = db;
        this.stock = stock;
    }

    private BigDecimal onHand(UUID item) {
        return db.value("SELECT coalesce(sum(qty), 0) FROM stock_levels WHERE item_id = :id", BigDecimal.class, "id", item);
    }

    /** The day's sheet. */
    public Map<String, Object> day(LocalDate day) {
        LocalDate d = day == null ? LocalDate.now(NAIROBI) : day;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> i : db.list("""
                SELECT i.id, i.name, i.price, i.cost, i.day_old_price, i.unit,
                       (SELECT t.id FROM items t WHERE t.day_old_of = i.id LIMIT 1) AS day_old_item,
                       b.baked, b.expected, b.left_counted, b.missing, b.missing_value, b.leftover_action, b.counted_at,
                       coalesce((SELECT sum(l.qty) FROM order_lines l JOIN orders o ON o.id = l.order_id
                                 WHERE l.item_id = i.id AND o.status IN ('PAID', 'CREDIT')
                                   AND (o.closed_at AT TIME ZONE 'Africa/Nairobi')::date = :day), 0) AS sold
                FROM items i LEFT JOIN bake_logs b ON b.item_id = i.id AND b.day = :day
                WHERE i.baked_daily AND i.active ORDER BY i.name""", "day", d)) {
            UUID id = UUID.fromString(i.get("id").toString());
            i.put("onHand", onHand(id));
            if (i.get("baked") == null) {
                i.put("baked", BigDecimal.ZERO);
            }
            items.add(i);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", d.toString());
        out.put("today", d.equals(LocalDate.now(NAIROBI)));
        out.put("items", items);
        out.put("dayOld", db.list("""
                SELECT t.id, t.name, t.price, coalesce((SELECT sum(qty) FROM stock_levels s WHERE s.item_id = t.id), 0) AS on_hand
                FROM items t WHERE t.day_old_of IS NOT NULL AND t.active ORDER BY t.name"""));
        out.put("candidates", db.list("""
                SELECT id, name, price FROM items
                WHERE active AND NOT baked_daily AND day_old_of IS NULL AND kind IN ('MENU', 'PRODUCT') ORDER BY name"""));
        out.put("missingValue", items.stream().map(x -> x.get("missingValue") == null ? BigDecimal.ZERO : (BigDecimal) x.get("missingValue"))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return out;
    }

    /** {bakedDaily, dayOldPrice}: put an item on the daily baking sheet (or take it off). */
    public Map<String, Object> setItem(UUID id, Body b) {
        TenantContext.requireRole("MANAGER");
        Map<String, Object> item = db.one("Item", "SELECT id, name, price FROM items WHERE id = :id", "id", id);
        BigDecimal dayOld = b.num("dayOldPrice", null);
        if (dayOld != null && (dayOld.signum() < 0 || dayOld.compareTo((BigDecimal) item.get("price")) >= 0)) {
            throw ApiException.bad("Yesterday's price should be lower than today's (KES " + ((BigDecimal) item.get("price")).toPlainString() + ")");
        }
        db.exec("UPDATE items SET baked_daily = :on, day_old_price = :p, track_stock = CASE WHEN :on THEN true ELSE track_stock END WHERE id = :id",
                "id", id, "on", b.bool("bakedDaily", true), "p", dayOld);
        db.exec("UPDATE items SET price = :p WHERE day_old_of = :id AND cast(:p as numeric) IS NOT NULL", "id", id, "p", dayOld);
        return db.one("Item", "SELECT id, name, baked_daily, day_old_price FROM items WHERE id = :id", "id", id);
    }

    /** A batch out of the oven: {itemId, qty}. Adds to today's stock. */
    public Map<String, Object> baked(Body b) {
        TenantContext.requireRole("MANAGER", "KITCHEN", "CASHIER");
        UUID item = UUID.fromString(b.str("itemId"));
        BigDecimal qty = b.num("qty", null);
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.bad("Say how many came out");
        }
        Map<String, Object> it = db.one("Item", "SELECT name, baked_daily FROM items WHERE id = :id", "id", item);
        if (!Boolean.TRUE.equals(it.get("bakedDaily"))) {
            throw ApiException.bad(it.get("name") + " isn't on the daily baking list. Add it first.");
        }
        LocalDate today = LocalDate.now(NAIROBI);
        db.exec("""
                INSERT INTO bake_logs (tenant_id, day, item_id, baked) VALUES (:t, :day, :item, :qty)
                ON CONFLICT (tenant_id, day, item_id) DO UPDATE SET baked = bake_logs.baked + excluded.baked""",
                "day", today, "item", item, "qty", qty);
        stock.move(item, stock.defaultLocation(), qty, "BAKED", "Baked " + today, null);
        return day(today);
    }

    /**
     * The closing count: {lines: [{itemId, left, action}]}. Stock is set to what's left; what's missing is recorded in
     * shillings; then the leftovers go where the action says.
     */
    public Map<String, Object> count(Body b) {
        TenantContext.requireRole("MANAGER", "CASHIER", "KITCHEN");
        LocalDate today = LocalDate.now(NAIROBI);
        UUID loc = stock.defaultLocation();
        for (Map<String, Object> raw : b.list("lines") == null ? List.<Map<String, Object>>of() : b.list("lines")) {
            Body l = new Body(raw);
            UUID item = UUID.fromString(l.str("itemId"));
            Map<String, Object> it = db.one("Item", "SELECT * FROM items WHERE id = :id AND baked_daily", "id", item);
            BigDecimal left = l.num("left", null);
            if (left == null || left.signum() < 0) {
                throw ApiException.bad("Count what's left of " + it.get("name"));
            }
            String action = l.str("action", "KEEP").toUpperCase();
            if (!ACTIONS.contains(action)) {
                throw ApiException.bad("Say what happens to the leftover " + it.get("name"));
            }
            if ("SELL_TOMORROW".equals(action) && it.get("dayOldPrice") == null && left.signum() > 0) {
                throw ApiException.bad("Set yesterday's price for " + it.get("name") + " first, or choose another option");
            }
            BigDecimal expected = onHand(item);
            BigDecimal missing = expected.subtract(left).max(BigDecimal.ZERO);
            BigDecimal value = missing.multiply((BigDecimal) it.get("price")).setScale(2, RoundingMode.HALF_UP);
            // Stock now matches the shelf.
            BigDecimal diff = left.subtract(expected);
            if (diff.signum() != 0) {
                stock.move(item, loc, diff, "COUNT", "Closing count " + today, diff.signum() < 0 ? "Missing at closing" : "More than expected", true);
            }
            if (left.signum() > 0) {
                switch (action) {
                    case "SELL_TOMORROW" -> {
                        UUID twin = twin(it);
                        stock.move(item, loc, left.negate(), "DAY_OLD", "To yesterday's shelf", null, true);
                        stock.move(twin, loc, left, "DAY_OLD", "From today's baking", null, true);
                    }
                    case "GIVE_AWAY", "WASTE" -> {
                        stock.move(item, loc, left.negate(), "WASTE", "GIVE_AWAY".equals(action) ? "Given away at closing" : "Thrown at closing", null, true);
                        db.exec("""
                                INSERT INTO waste_entries (tenant_id, item_id, qty, reason, note, value, user_id)
                                VALUES (:t, :item, :qty, :reason, :note, :value, :u)""",
                                "item", item, "qty", left, "reason", "GIVE_AWAY".equals(action) ? "OTHER" : "EXPIRED",
                                "note", "GIVE_AWAY".equals(action) ? "Given away at closing" : "Left at closing, thrown",
                                "value", left.multiply((BigDecimal) it.get("cost")).setScale(2, RoundingMode.HALF_UP));
                    }
                    default -> {
                        // KEEP: stays on today's shelf.
                    }
                }
            }
            db.exec("""
                    INSERT INTO bake_logs (tenant_id, day, item_id, expected, left_counted, missing, missing_value, leftover_action, counted_at, counted_by)
                    VALUES (:t, :day, :item, :e, :left, :m, :v, :a, now(), :u)
                    ON CONFLICT (tenant_id, day, item_id) DO UPDATE SET expected = excluded.expected, left_counted = excluded.left_counted,
                        missing = excluded.missing, missing_value = excluded.missing_value, leftover_action = excluded.leftover_action,
                        counted_at = now(), counted_by = excluded.counted_by""",
                    "day", today, "item", item, "e", expected, "left", left, "m", missing, "v", value, "a", action);
        }
        return day(today);
    }

    /** The item's "(yesterday's)" twin, made the first time it's needed. */
    private UUID twin(Map<String, Object> it) {
        UUID id = UUID.fromString(it.get("id").toString());
        UUID twin = db.value("SELECT id FROM items WHERE day_old_of = :id LIMIT 1", UUID.class, "id", id);
        if (twin != null) {
            db.exec("UPDATE items SET price = :p, active = true WHERE id = :tw", "tw", twin, "p", it.get("dayOldPrice"));
            return twin;
        }
        return db.insert("""
                INSERT INTO items (tenant_id, category_id, name, kind, unit, price, cost, tax_rate, track_stock, station, day_old_of, color)
                VALUES (:t, :cat, :name, :kind, :unit, :price, :cost, :tax, true, :station, :of, :color) RETURNING id""",
                "cat", it.get("categoryId") == null ? null : UUID.fromString(it.get("categoryId").toString()), "name", clip(it.get("name") + " (yesterday's)", 200), "kind", it.get("kind"), "unit", it.get("unit"),
                "price", it.get("dayOldPrice"), "cost", it.get("cost"), "tax", it.get("taxRate"), "station", it.get("station"), "of", id,
                "color", it.get("color"));
    }

    static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
