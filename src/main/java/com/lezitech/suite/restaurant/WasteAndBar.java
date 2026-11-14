package com.lezitech.suite.restaurant;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.stock.StockService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restaurants, package R3: the waste log, selling a bottle by the tot, glass or bottle, and what a shortfall at the
 * count means in tots and in lost sales.
 */
@Service
public class WasteAndBar {

    public static final Set<String> REASONS = Set.of("BURNT", "EXPIRED", "DROPPED", "SPILLED", "RETURNED", "STAFF_MEAL", "OTHER");
    /** Waste worth more than this goes to the Activity log for the owner. */
    static final BigDecimal BIG_WASTE = new BigDecimal("1000");
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final Db db;
    private final JdbcTemplate jdbc;
    private final Recipes recipes;
    private final StockService stock;
    private final ActivityLog activity;

    public WasteAndBar(Db db, JdbcTemplate jdbc, Recipes recipes, StockService stock, ActivityLog activity) {
        this.db = db;
        this.jdbc = jdbc;
        this.recipes = recipes;
        this.stock = stock;
        this.activity = activity;
    }

    // ---------------- who may record waste ----------------

    /** The owner's choice: anyone on the team (default) or managers only. */
    public boolean managersOnly() {
        Boolean v = jdbc.queryForObject("SELECT coalesce((settings->'waste'->>'managersOnly')::boolean, false) FROM tenants WHERE id = ?",
                Boolean.class, TenantContext.tenantId());
        return Boolean.TRUE.equals(v);
    }

    public void setManagersOnly(boolean on) {
        jdbc.update("UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{waste}', cast(? as jsonb)) WHERE id = ?",
                "{\"managersOnly\":" + on + "}", TenantContext.tenantId());
    }

    // ---------------- waste ----------------

    /**
     * {itemId (a dish, an ingredient or something cooked ahead), qty, reason, note}. A dish with a recipe takes its
     * ingredients out of stock; an ingredient comes out itself. The cost is recorded at what the restaurant paid.
     */
    public Map<String, Object> waste(Body b) {
        if (managersOnly()) {
            TenantContext.requireRole("MANAGER");
        }
        UUID itemId = UUID.fromString(b.str("itemId"));
        Map<String, Object> item = db.one("Item", "SELECT name, kind, unit, cost, track_stock FROM items WHERE id = :id", "id", itemId);
        BigDecimal qty = b.num("qty", null);
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.bad("Say how much was wasted");
        }
        String reason = b.str("reason", "").toUpperCase();
        if (!REASONS.contains(reason)) {
            throw ApiException.bad("Choose why it was wasted");
        }
        if ("SERVICE".equals(item.get("kind"))) {
            throw ApiException.bad(item.get("name") + " is a service, not stock");
        }
        Map<UUID, BigDecimal> costs = recipes.unitCosts();
        UUID loc = stock.defaultLocation();
        String ref = "Waste: " + reason.toLowerCase().replace('_', ' ');
        BigDecimal value = BigDecimal.ZERO;
        if (Recipes.KITCHEN_KINDS.contains(item.get("kind"))) {
            value = qty.multiply(costs.getOrDefault(itemId, BigDecimal.ZERO));
            stock.move(itemId, loc, qty.negate(), "WASTE", ref, b.str("note", null), true);
        } else {
            Map<UUID, BigDecimal> uses = recipes.usesFor(itemId, null);
            if (uses.isEmpty()) {
                value = qty.multiply((BigDecimal) item.get("cost"));
                stock.move(itemId, loc, qty.negate(), "WASTE", ref, b.str("note", null), true);
            }
            for (Map.Entry<UUID, BigDecimal> u : uses.entrySet()) {
                BigDecimal used = u.getValue().multiply(qty);
                value = value.add(used.multiply(costs.getOrDefault(u.getKey(), BigDecimal.ZERO)));
                stock.move(u.getKey(), loc, used.negate(), "WASTE", ref + " (" + item.get("name") + ")", b.str("note", null), true);
            }
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
        String note = b.str("note", null);
        if (note != null && note.length() > 200) {
            note = note.substring(0, 200);
        }
        UUID id = db.insert("""
                INSERT INTO waste_entries (tenant_id, item_id, qty, reason, note, value, user_id)
                VALUES (:t, :item, :qty, :reason, :note, :value, :u) RETURNING id""",
                "item", itemId, "qty", qty, "reason", reason, "note", note, "value", value);
        if (value.compareTo(BIG_WASTE) > 0) {
            activity.record("WASTE", ActivityLog.CONCERN, "Waste: " + qty.stripTrailingZeros().toPlainString() + " " + item.get("unit") + " "
                    + item.get("name") + " (" + reason.toLowerCase().replace('_', ' ') + "), worth " + value.toPlainString() + " at cost",
                    null, itemId, value, null, Map.of("note", note == null ? "" : note));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("value", value);
        return out;
    }

    /** Waste between two days: each entry, totals by reason, and the total. Staff who aren't managers see their own. */
    public Map<String, Object> report(LocalDate from, LocalDate to) {
        String role = TenantContext.caller().role();
        boolean all = Set.of("OWNER", "MANAGER", "ACCOUNTANT").contains(role);
        LocalDate f = from == null ? LocalDate.now(NAIROBI).minusDays(6) : from;
        LocalDate t = to == null ? LocalDate.now(NAIROBI) : to;
        Object[] p = {"from", java.sql.Timestamp.from(f.atStartOfDay(NAIROBI).toInstant()),
                "to", java.sql.Timestamp.from(t.plusDays(1).atStartOfDay(NAIROBI).toInstant()), "all", all};
        List<Map<String, Object>> entries = db.list("""
                SELECT w.id, w.qty, w.reason, w.note, w.value, w.created_at, i.name, i.unit, i.kind, u.name AS by_name
                FROM waste_entries w JOIN items i ON i.id = w.item_id LEFT JOIN users u ON u.id = w.user_id
                WHERE w.created_at >= :from AND w.created_at < :to AND (:all OR w.user_id = :u)
                ORDER BY w.created_at DESC LIMIT 500""", p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("entries", entries);
        out.put("byReason", db.list("""
                SELECT reason, count(*) AS entries, sum(value) AS value FROM waste_entries
                WHERE created_at >= :from AND created_at < :to AND (:all OR user_id = :u) GROUP BY reason ORDER BY sum(value) DESC""", p));
        out.put("total", entries.stream().map(e -> (BigDecimal) e.get("value")).reduce(BigDecimal.ZERO, BigDecimal::add));
        out.put("managersOnly", managersOnly());
        return out;
    }

    // ---------------- selling by the tot ----------------

    /**
     * Sell a bottle by the tot, glass or bottle: {servings: [{name: "tot", qty: 25, price: 300}, ...]}. Each serving
     * becomes a drink on the menu ("Jameson (tot)") that uses that much of the bottle.
     */
    public List<Map<String, Object>> sellBy(UUID ingredientId, Body b) {
        Map<String, Object> ing = db.one("Ingredient", "SELECT name, kind, unit FROM items WHERE id = :id", "id", ingredientId);
        if (!"INGREDIENT".equals(ing.get("kind"))) {
            throw ApiException.notFound("Ingredient");
        }
        List<Map<String, Object>> servings = b.list("servings");
        if (servings == null || servings.isEmpty()) {
            throw ApiException.bad("Add at least one way to sell it, e.g. a tot");
        }
        UUID category = b.has("categoryId") && !b.str("categoryId", "").isBlank() ? UUID.fromString(b.str("categoryId")) : null;
        List<Map<String, Object>> made = new ArrayList<>();
        for (Map<String, Object> raw : servings) {
            Body s = new Body(raw);
            String label = s.str("name", "").trim();
            BigDecimal qty = s.num("qty", null);
            BigDecimal price = s.num("price", null);
            if (label.isEmpty() || qty == null || qty.signum() <= 0 || price == null || price.signum() <= 0) {
                throw ApiException.bad("Each way to sell it needs a name, how much it uses and a price");
            }
            String name = ing.get("name") + " (" + label + ")";
            UUID id = db.insert("""
                    INSERT INTO items (tenant_id, name, kind, category_id, unit, price, cost, tax_rate, track_stock, station)
                    VALUES (:t, :name, 'MENU', :cat, 'pc', :price, 0, 16, false, 'Bar') RETURNING id""",
                    "name", name, "cat", category, "price", price);
            db.exec("INSERT INTO recipe_lines (tenant_id, item_id, ingredient_id, qty) VALUES (:t, :id, :ing, :qty)",
                    "id", id, "ing", ingredientId, "qty", qty);
            made.add(Map.of("id", id, "name", name));
        }
        stock.syncFinishedDishes(ingredientId);
        return made;
    }

    /**
     * For each ingredient sold on its own (a tot, a glass), its smallest serving: name, how much it uses, its price.
     * Used to show a shortfall in servings and in lost sales.
     */
    public Map<UUID, Map<String, Object>> servings() {
        Map<UUID, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : db.list("""
                SELECT DISTINCT ON (r.ingredient_id) r.ingredient_id, i.name, r.qty, i.price
                FROM recipe_lines r JOIN items i ON i.id = r.item_id
                WHERE i.active AND i.kind NOT IN ('INGREDIENT', 'BATCH') AND r.option_group IS NULL AND i.price > 0
                  AND (SELECT count(*) FROM recipe_lines x WHERE x.item_id = i.id) = 1
                ORDER BY r.ingredient_id, r.qty""")) {
            out.put(UUID.fromString(r.get("ingredientId").toString()), r);
        }
        return out;
    }
}
