package com.lezitech.suite.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.stock.StockService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restaurants, package R2: recipes. A dish lists what it uses; selling it uses those ingredients up (and a refund puts
 * them back). Batch recipes (a pot of stew) turn ingredients into a batch that dishes use. Food cost per dish.
 */
@Service
public class Recipes {

    public static final Set<String> KITCHEN_KINDS = Set.of("INGREDIENT", "BATCH");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Db db;
    private final StockService stock;

    public Recipes(Db db, StockService stock) {
        this.db = db;
        this.stock = stock;
    }

    // ---------------- costs ----------------

    /** The cost of one cooking unit (one gram, one ml, one piece) of every ingredient and batch. */
    public Map<UUID, BigDecimal> unitCosts() {
        Map<UUID, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> i : db.list("SELECT id, cost, buy_size, buy_price FROM items WHERE kind = 'INGREDIENT'")) {
            BigDecimal size = (BigDecimal) i.get("buySize");
            BigDecimal price = (BigDecimal) i.get("buyPrice");
            out.put(id(i.get("id")), size != null && price != null && size.signum() > 0
                    ? price.divide(size, 6, RoundingMode.HALF_UP) : (BigDecimal) i.get("cost"));
        }
        List<Map<String, Object>> batches = db.list("SELECT id, batch_yield FROM items WHERE kind = 'BATCH'");
        for (Map<String, Object> b : batches) {
            BigDecimal yield = (BigDecimal) b.get("batchYield");
            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> l : db.list("SELECT ingredient_id, qty FROM recipe_lines WHERE item_id = :id AND option_group IS NULL", "id", b.get("id"))) {
                total = total.add(((BigDecimal) l.get("qty")).multiply(out.getOrDefault(id(l.get("ingredientId")), BigDecimal.ZERO)));
            }
            out.put(id(b.get("id")), yield == null || yield.signum() <= 0 ? BigDecimal.ZERO : total.divide(yield, 6, RoundingMode.HALF_UP));
        }
        return out;
    }

    // ---------------- using stock ----------------

    /** What one of this dish uses, with the diner's choices applied. Empty when the dish has no recipe. */
    public Map<UUID, BigDecimal> usesFor(UUID itemId, Object options) {
        List<Map<String, Object>> lines = db.list("""
                SELECT ingredient_id, qty, option_group, option_name, replaces_id FROM recipe_lines WHERE item_id = :id ORDER BY sort""",
                "id", itemId);
        Map<UUID, BigDecimal> uses = new LinkedHashMap<>();
        if (lines.isEmpty()) {
            return uses;
        }
        Set<String> picked = picked(options);
        for (Map<String, Object> l : lines) {
            if (l.get("optionGroup") == null) {
                uses.merge(id(l.get("ingredientId")), (BigDecimal) l.get("qty"), BigDecimal::add);
            }
        }
        for (Map<String, Object> l : lines) {
            if (l.get("optionGroup") != null && picked.contains(key(l.get("optionGroup"), l.get("optionName")))) {
                if (l.get("replacesId") != null) {
                    uses.remove(id(l.get("replacesId")));
                }
                uses.merge(id(l.get("ingredientId")), (BigDecimal) l.get("qty"), BigDecimal::add);
            }
        }
        return uses;
    }

    /**
     * A sale closed (sign -1) or was refunded (sign +1): its dishes' ingredients come out of, or go back into, stock.
     * Running below zero is allowed (the kitchen may have more than the records say); the dish then shows as finished.
     */
    public void useForOrder(UUID orderId, UUID location, int sign, String reason, String ref) {
        for (Map<String, Object> l : db.list("SELECT item_id, qty, options FROM order_lines WHERE order_id = :id AND item_id IS NOT NULL", "id", orderId)) {
            BigDecimal dishes = (BigDecimal) l.get("qty");
            for (Map.Entry<UUID, BigDecimal> u : usesFor(id(l.get("itemId")), l.get("options")).entrySet()) {
                stock.move(u.getKey(), location, u.getValue().multiply(dishes).multiply(BigDecimal.valueOf(sign)), reason, ref, null, true);
            }
        }
    }

    /** The cook made a batch: its ingredients come out of stock, and the batch goes in. */
    public Map<String, Object> made(UUID batchId, BigDecimal qty) {
        Map<String, Object> b = db.one("Batch recipe", "SELECT name, kind, batch_yield, unit FROM items WHERE id = :id", "id", batchId);
        if (!"BATCH".equals(b.get("kind"))) {
            throw ApiException.notFound("Batch recipe");
        }
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.bad("Say how much was made");
        }
        BigDecimal yield = (BigDecimal) b.get("batchYield");
        List<Map<String, Object>> lines = db.list("SELECT ingredient_id, qty FROM recipe_lines WHERE item_id = :id AND option_group IS NULL", "id", batchId);
        if (lines.isEmpty() || yield == null) {
            throw ApiException.bad("Add what goes into " + b.get("name") + " first");
        }
        UUID loc = stock.defaultLocation();
        String ref = "Made " + qty.stripTrailingZeros().toPlainString() + " " + b.get("unit") + " " + b.get("name");
        for (Map<String, Object> l : lines) {
            BigDecimal use = ((BigDecimal) l.get("qty")).multiply(qty).divide(yield, 3, RoundingMode.HALF_UP);
            stock.move(id(l.get("ingredientId")), loc, use.negate(), "BATCH", ref, null, true);
        }
        stock.move(batchId, loc, qty, "BATCH", ref, null, true);
        return Map.of("ok", true);
    }

    // ---------------- setting recipes ----------------

    /**
     * Replace what a dish (or a batch) uses. {lines: [{ingredientId, qty}], choices: [{group, choice, ingredientId, qty,
     * replacesId}]}. A batch can only use ingredients; a dish can use ingredients and batches.
     */
    public void saveRecipe(UUID itemId, Body b) {
        Map<String, Object> item = db.one("Item", "SELECT id, name, kind, options FROM items WHERE id = :id", "id", itemId);
        String kind = (String) item.get("kind");
        if ("INGREDIENT".equals(kind) || "SERVICE".equals(kind)) {
            throw ApiException.bad(item.get("name") + " can't have a recipe");
        }
        boolean batch = "BATCH".equals(kind);
        Set<String> choices = new HashSet<>();
        if (item.get("options") instanceof JsonNode groups) {
            for (JsonNode g : groups) {
                for (JsonNode c : g.path("choices")) {
                    choices.add(key(g.path("name").asText(), c.path("name").asText()));
                }
            }
        }
        db.exec("DELETE FROM recipe_lines WHERE item_id = :id", "id", itemId);
        int sort = 0;
        List<Map<String, Object>> lines = b.list("lines") == null ? List.of() : b.list("lines");
        Set<UUID> base = new HashSet<>();
        for (Map<String, Object> raw : lines) {
            Body l = new Body(raw);
            UUID ing = ingredient(l.str("ingredientId"), itemId, batch);
            base.add(ing);
            db.exec("INSERT INTO recipe_lines (tenant_id, item_id, ingredient_id, qty, sort) VALUES (:t, :item, :ing, :qty, :sort)",
                    "item", itemId, "ing", ing, "qty", positive(l.num("qty", null)), "sort", sort++);
        }
        List<Map<String, Object>> extra = b.list("choices") == null ? List.of() : b.list("choices");
        if (batch && !extra.isEmpty()) {
            throw ApiException.bad("A batch recipe has no choices");
        }
        for (Map<String, Object> raw : extra) {
            Body l = new Body(raw);
            String group = l.str("group", "");
            String choice = l.str("choice", "");
            if (!choices.contains(key(group, choice))) {
                throw ApiException.bad("\"" + choice + "\" isn't one of the choices on " + item.get("name"));
            }
            UUID ing = ingredient(l.str("ingredientId"), itemId, false);
            UUID replaces = null;
            if (l.has("replacesId") && !l.str("replacesId", "").isBlank()) {
                replaces = UUID.fromString(l.str("replacesId"));
                if (!base.contains(replaces)) {
                    throw ApiException.bad("A choice can only swap out something the dish already uses");
                }
            }
            db.exec("""
                    INSERT INTO recipe_lines (tenant_id, item_id, ingredient_id, qty, option_group, option_name, replaces_id, sort)
                    VALUES (:t, :item, :ing, :qty, :g, :c, :r, :sort)""",
                    "item", itemId, "ing", ing, "qty", positive(l.num("qty", null)), "g", group, "c", choice, "r", replaces, "sort", sort++);
        }
        // The dish's finished state follows its new recipe.
        stock.syncFinishedDishes(null);
    }

    private UUID ingredient(String raw, UUID self, boolean batch) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.bad("Choose an ingredient for every line");
        }
        UUID id = UUID.fromString(raw);
        if (id.equals(self)) {
            throw ApiException.bad("A recipe can't use itself");
        }
        String kind = db.find("SELECT kind FROM items WHERE id = :id", "id", id).map(r -> (String) r.get("kind"))
                .orElseThrow(() -> ApiException.notFound("That ingredient"));
        if (batch ? !"INGREDIENT".equals(kind) : !KITCHEN_KINDS.contains(kind)) {
            throw ApiException.bad(batch ? "A batch recipe uses ingredients only" : "Choose an ingredient or a batch recipe");
        }
        return id;
    }

    private static BigDecimal positive(BigDecimal q) {
        if (q == null || q.signum() <= 0) {
            throw ApiException.bad("Every amount must be more than zero");
        }
        return q;
    }

    // ---------------- the recipes screen ----------------

    public Map<String, Object> overview() {
        Map<UUID, BigDecimal> costs = unitCosts();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> ingredients = db.list("""
                SELECT i.id, i.name, i.unit, i.buy_unit, i.buy_size, i.buy_price, i.reorder_level, i.active,
                       coalesce((SELECT sum(qty) FROM stock_levels s WHERE s.item_id = i.id), 0) AS on_hand,
                       (SELECT count(DISTINCT r.item_id) FROM recipe_lines r WHERE r.ingredient_id = i.id) AS used_in
                FROM items i WHERE i.kind = 'INGREDIENT' ORDER BY i.active DESC, i.name""");
        for (Map<String, Object> i : ingredients) {
            i.put("unitCost", money6(costs.get(id(i.get("id")))));
        }
        out.put("ingredients", ingredients);
        List<Map<String, Object>> batches = db.list("""
                SELECT i.id, i.name, i.unit, i.batch_yield, i.active,
                       coalesce((SELECT sum(qty) FROM stock_levels s WHERE s.item_id = i.id), 0) AS on_hand
                FROM items i WHERE i.kind = 'BATCH' ORDER BY i.active DESC, i.name""");
        for (Map<String, Object> b : batches) {
            b.put("unitCost", money6(costs.get(id(b.get("id")))));
            b.put("lines", lines(id(b.get("id"))));
        }
        out.put("batches", batches);
        List<Map<String, Object>> dishes = db.list("""
                SELECT i.id, i.name, i.price, i.options, c.name AS category_name, i.finished_by_stock,
                       (i.unavailable_until IS NOT NULL AND i.unavailable_until > now()) AS finished
                FROM items i LEFT JOIN categories c ON c.id = i.category_id
                WHERE i.active AND i.kind NOT IN ('INGREDIENT', 'BATCH', 'SERVICE')
                ORDER BY c.sort NULLS LAST, i.name""");
        for (Map<String, Object> d : dishes) {
            List<Map<String, Object>> lines = lines(id(d.get("id")));
            d.put("lines", lines);
            BigDecimal cost = BigDecimal.ZERO;
            boolean any = false;
            for (Map<String, Object> l : lines) {
                if (l.get("group") == null) {
                    any = true;
                    cost = cost.add(((BigDecimal) l.get("qty")).multiply(costs.getOrDefault(id(l.get("ingredientId")), BigDecimal.ZERO)));
                }
            }
            BigDecimal price = (BigDecimal) d.get("price");
            d.put("cost", any ? cost.setScale(2, RoundingMode.HALF_UP) : null);
            d.put("foodCostPct", any && price.signum() > 0 ? cost.multiply(HUNDRED).divide(price, 1, RoundingMode.HALF_UP) : null);
        }
        out.put("dishes", dishes);
        return out;
    }

    private List<Map<String, Object>> lines(UUID itemId) {
        return db.list("""
                SELECT r.id, r.ingredient_id, g.name, g.unit, r.qty, r.option_group AS "group", r.option_name AS choice, r.replaces_id
                FROM recipe_lines r JOIN items g ON g.id = r.ingredient_id WHERE r.item_id = :id ORDER BY r.sort""", "id", itemId);
    }

    // ---------------- helpers ----------------

    private static BigDecimal money6(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(4, RoundingMode.HALF_UP);
    }

    private static UUID id(Object o) {
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    private static String key(Object group, Object name) {
        return (group == null ? "" : group.toString().trim().toLowerCase()) + "|" + (name == null ? "" : name.toString().trim().toLowerCase());
    }

    private static Set<String> picked(Object options) {
        Set<String> out = new HashSet<>();
        if (options instanceof JsonNode n) {
            for (JsonNode o : n) {
                out.add(key(o.path("group").asText(""), o.path("name").asText("")));
            }
        } else if (options instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add(key(m.get("group"), m.get("name")));
                }
            }
        }
        return out;
    }

    /** For tests and the count screen: the stock of one item at the default place. */
    public BigDecimal onHand(UUID itemId) {
        return db.value("SELECT coalesce(sum(qty), 0) FROM stock_levels WHERE item_id = :id", BigDecimal.class, "id", itemId);
    }

    public static List<String> kitchenKinds() {
        return new ArrayList<>(KITCHEN_KINDS);
    }
}
