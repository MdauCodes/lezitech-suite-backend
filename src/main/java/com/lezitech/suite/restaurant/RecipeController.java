package com.lezitech.suite.restaurant;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.stock.StockService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Restaurants, package R2: ingredients, batch recipes, what each dish uses, deliveries, batches made, counting. */
@RestController
@RequestMapping("/api/recipes")
@Transactional
public class RecipeController {

    private static final Set<String> UNITS = Set.of("g", "kg", "ml", "L", "pc");

    private final Db db;
    private final Recipes recipes;
    private final StockService stock;
    private final ActivityLog activity;
    private final WasteAndBar wasteAndBar;

    public RecipeController(Db db, Recipes recipes, StockService stock, ActivityLog activity, WasteAndBar wasteAndBar) {
        this.db = db;
        this.recipes = recipes;
        this.stock = stock;
        this.activity = activity;
        this.wasteAndBar = wasteAndBar;
    }

    @GetMapping
    public Map<String, Object> overview() {
        return recipes.overview();
    }

    // ---------------- ingredients ----------------

    /**
     * {name, unit (cooking unit: g, kg, ml, L, pc), buyUnit ("bag"), buySize (cooking units in one: 2000), buyPrice
     * (for one bag), reorderLevel (cooking units), startCount (cooking units on hand now, optional)}.
     */
    @PostMapping("/ingredients")
    public Map<String, Object> addIngredient(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        UUID id = db.insert("INSERT INTO items (tenant_id, name, kind, track_stock, price, tax_rate) VALUES (:t, :name, 'INGREDIENT', true, 0, 0) RETURNING id",
                "name", name(b));
        saveIngredient(id, b);
        BigDecimal start = b.num("startCount", null);
        if (start != null && start.signum() > 0) {
            stock.move(id, stock.defaultLocation(), start, "OPENING", "Starting count", null);
        }
        return Map.of("id", id);
    }

    @PostMapping("/ingredients/{id}")
    public Map<String, Object> updateIngredient(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        kind(id, "INGREDIENT");
        Body b = new Body(raw);
        db.exec("UPDATE items SET name = :name, active = :active WHERE id = :id", "id", id, "name", name(b), "active", b.bool("active", true));
        saveIngredient(id, b);
        return Map.of("id", id);
    }

    private void saveIngredient(UUID id, Body b) {
        String unit = b.str("unit", "g");
        if (!UNITS.contains(unit)) {
            throw ApiException.bad("Choose how the kitchen measures it: g, kg, ml, L or pieces");
        }
        BigDecimal size = b.num("buySize", null);
        String buyUnit = b.str("buyUnit", null);
        if (buyUnit != null && buyUnit.isBlank()) {
            buyUnit = null;
        }
        if (buyUnit != null && (size == null || size.signum() <= 0)) {
            throw ApiException.bad("Say how many " + unit + " are in one " + buyUnit);
        }
        BigDecimal price = b.num("buyPrice", null);
        if (price != null && price.signum() < 0) {
            throw ApiException.bad("A price can't be below zero");
        }
        BigDecimal per = price == null ? BigDecimal.ZERO : size != null ? price.divide(size, 2, RoundingMode.HALF_UP) : price;
        String area = "BAR".equalsIgnoreCase(b.str("area", "KITCHEN")) ? "BAR" : "KITCHEN";
        db.exec("""
                UPDATE items SET unit = :unit, buy_unit = :bu, buy_size = :size, buy_price = :price, cost = :per,
                       reorder_level = :reorder, area = :area WHERE id = :id""",
                "id", id, "unit", unit, "bu", buyUnit, "size", buyUnit == null ? null : size, "price", price, "per", per,
                "reorder", b.num("reorderLevel", BigDecimal.ZERO), "area", area);
    }

    /** A delivery: {buyQty (bags, or cooking units when there's no buying unit), buyPrice (optional, for one)}. */
    @PostMapping("/ingredients/{id}/received")
    public Map<String, Object> received(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "CHEF", "KITCHEN");
        Map<String, Object> i = kind(id, "INGREDIENT");
        Body b = new Body(raw);
        BigDecimal qty = b.num("buyQty", null);
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.bad("Say how much came in");
        }
        BigDecimal size = (BigDecimal) i.get("buySize");
        stock.move(id, stock.defaultLocation(), size == null ? qty : qty.multiply(size), "RECEIVE", "Delivery", b.str("note", null));
        BigDecimal price = b.num("buyPrice", null);
        if (price != null && price.signum() >= 0) {
            db.exec("UPDATE items SET buy_price = :p, cost = CASE WHEN buy_size IS NULL THEN :p ELSE round(:p / buy_size, 2) END WHERE id = :id",
                    "id", id, "p", price);
        }
        return Map.of("onHand", recipes.onHand(id));
    }

    // ---------------- batch recipes ----------------

    /** {name, unit (g, kg, ml, L, pc), batchYield (how much one batch makes)}; then set its recipe. */
    @PostMapping("/batches")
    public Map<String, Object> addBatch(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        UUID id = db.insert("INSERT INTO items (tenant_id, name, kind, track_stock, price, tax_rate) VALUES (:t, :name, 'BATCH', true, 0, 0) RETURNING id",
                "name", name(b));
        saveBatch(id, b);
        return Map.of("id", id);
    }

    @PostMapping("/batches/{id}")
    public Map<String, Object> updateBatch(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        kind(id, "BATCH");
        Body b = new Body(raw);
        db.exec("UPDATE items SET name = :name, active = :active WHERE id = :id", "id", id, "name", name(b), "active", b.bool("active", true));
        saveBatch(id, b);
        return Map.of("id", id);
    }

    private void saveBatch(UUID id, Body b) {
        String unit = b.str("unit", "ml");
        if (!UNITS.contains(unit)) {
            throw ApiException.bad("Choose how the batch is measured: g, kg, ml, L or pieces");
        }
        BigDecimal yield = b.num("batchYield", null);
        if (yield == null || yield.signum() <= 0) {
            throw ApiException.bad("Say how much one batch makes");
        }
        db.exec("UPDATE items SET unit = :unit, batch_yield = :y WHERE id = :id", "id", id, "unit", unit, "y", yield);
    }

    /** The cook made a batch: {qty} in the batch's unit (e.g. 10000 ml). Anyone on the team can record it. */
    @PostMapping("/batches/{id}/made")
    public Map<String, Object> made(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        recipes.made(id, new Body(raw).num("qty", null));
        return Map.of("onHand", recipes.onHand(id));
    }

    // ---------------- recipes ----------------

    /** What a dish or a batch uses: {lines: [{ingredientId, qty}], choices: [{group, choice, ingredientId, qty, replacesId}]}. */
    @PostMapping("/items/{id}/recipe")
    public Map<String, Object> saveRecipe(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        recipes.saveRecipe(id, new Body(raw));
        return Map.of("ok", true);
    }

    // ---------------- counting ----------------

    /**
     * The kitchen counted: {counts: [{itemId, counted}], note}. For each, what the records expected, what was counted,
     * and the difference in money; stock is set to the count.
     */
    @PostMapping("/count")
    public Map<String, Object> count(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "CHEF", "KITCHEN");
        Body b = new Body(raw);
        List<Map<String, Object>> counts = b.list("counts");
        if (counts == null || counts.isEmpty()) {
            throw ApiException.bad("Count at least one thing");
        }
        Map<UUID, BigDecimal> costs = recipes.unitCosts();
        Map<UUID, Map<String, Object>> servings = wasteAndBar.servings();
        UUID loc = stock.defaultLocation();
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal missing = BigDecimal.ZERO;
        BigDecimal lostSales = BigDecimal.ZERO;
        BigDecimal extra = BigDecimal.ZERO;
        StringBuilder worst = new StringBuilder();
        for (Map<String, Object> c : counts) {
            Body cb = new Body(c);
            UUID id = UUID.fromString(cb.str("itemId"));
            Map<String, Object> item = db.one("Ingredient", "SELECT name, unit, kind FROM items WHERE id = :id", "id", id);
            if (!Recipes.KITCHEN_KINDS.contains(item.get("kind"))) {
                throw ApiException.bad(item.get("name") + " isn't a kitchen ingredient");
            }
            BigDecimal counted = cb.num("counted", null);
            if (counted == null || counted.signum() < 0) {
                throw ApiException.bad("Enter what you counted for " + item.get("name"));
            }
            BigDecimal expected = db.value("SELECT coalesce(sum(qty), 0) FROM stock_levels WHERE item_id = :id AND location_id = :loc",
                    BigDecimal.class, "id", id, "loc", loc);
            BigDecimal diff = counted.subtract(expected);
            BigDecimal value = diff.multiply(costs.getOrDefault(id, BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
            // R3: sold by the tot or glass? Then a shortfall is also so many tots, and that much in lost sales.
            Map<String, Object> serving = servings.get(id);
            BigDecimal servingsShort = null;
            BigDecimal lost = null;
            if (serving != null && diff.signum() < 0) {
                BigDecimal size = (BigDecimal) serving.get("qty");
                servingsShort = diff.negate().divide(size, 1, RoundingMode.HALF_UP);
                lost = diff.negate().multiply((BigDecimal) serving.get("price")).divide(size, 2, RoundingMode.HALF_UP);
                lostSales = lostSales.add(lost);
            }
            stock.move(id, loc, diff, "ADJUST", "Kitchen count", b.str("note", null), true);
            if (value.signum() < 0) {
                missing = missing.add(value.negate());
                if (worst.length() < 200) {
                    worst.append(worst.length() == 0 ? "" : ", ").append(item.get("name")).append(" ")
                            .append(servingsShort != null ? servingsShort.stripTrailingZeros().toPlainString() + " servings"
                                    : diff.negate().stripTrailingZeros().toPlainString() + " " + item.get("unit"));
                }
            } else {
                extra = extra.add(value);
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("itemId", id);
            line.put("name", item.get("name"));
            line.put("unit", item.get("unit"));
            line.put("expected", expected);
            line.put("counted", counted);
            line.put("difference", diff);
            line.put("value", value);
            line.put("serving", serving == null ? null : serving.get("name"));
            line.put("servingsShort", servingsShort);
            line.put("lostSales", lost);
            lines.add(line);
        }
        if (missing.signum() > 0) {
            activity.record("STOCK_COUNT", missing.compareTo(new BigDecimal("500")) >= 0 ? ActivityLog.CONCERN : ActivityLog.NOTICE,
                    "Count: missing " + worst + " (worth " + missing.toPlainString() + " at cost"
                            + (lostSales.signum() > 0 ? ", " + lostSales.toPlainString() + " in lost sales" : "") + ")", null, null, missing, null,
                    Map.of("note", b.str("note", "")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", lines);
        out.put("missingValue", missing);
        out.put("extraValue", extra);
        out.put("lostSalesValue", lostSales);
        return out;
    }

    // ---------------- R3: waste, selling by the tot ----------------

    /** {itemId, qty, reason, note}: anyone on the team, unless the owner chose managers only. */
    @PostMapping("/waste")
    public Map<String, Object> waste(@RequestBody Map<String, Object> raw) {
        return wasteAndBar.waste(new Body(raw));
    }

    @GetMapping("/waste")
    public Map<String, Object> wasteReport(@RequestParam(required = false) java.time.LocalDate from,
                                           @RequestParam(required = false) java.time.LocalDate to) {
        return wasteAndBar.report(from, to);
    }

    /** The owner decides who may record waste: {managersOnly}. */
    @PostMapping("/waste/settings")
    public Map<String, Object> wasteSettings(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        wasteAndBar.setManagersOnly(new Body(raw).bool("managersOnly", false));
        return Map.of("managersOnly", wasteAndBar.managersOnly());
    }

    /** Sell a bottle by the tot, glass or bottle: {servings: [{name, qty, price}], categoryId}. */
    @PostMapping("/ingredients/{id}/sell-by")
    public List<Map<String, Object>> sellBy(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return wasteAndBar.sellBy(id, new Body(raw));
    }

    // ---------------- helpers ----------------

    private static String name(Body b) {
        String n = b.str("name", "").trim();
        if (n.isEmpty()) {
            throw ApiException.bad("Give it a name");
        }
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    private Map<String, Object> kind(UUID id, String kind) {
        Map<String, Object> i = db.one("Ingredient", "SELECT id, kind, buy_size FROM items WHERE id = :id", "id", id);
        if (!kind.equals(i.get("kind"))) {
            throw ApiException.notFound("BATCH".equals(kind) ? "Batch recipe" : "Ingredient");
        }
        return i;
    }
}
