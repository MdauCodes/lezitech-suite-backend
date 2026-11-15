package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Time;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** R6, menu tools: combos (a dish made of parts), deals and happy hour, and meal times for categories. */
@RestController
@RequestMapping("/api/menu-tools")
@Transactional
public class MenuToolsController {

    private final Db db;
    private final Deals deals;

    public MenuToolsController(Db db, Deals deals) {
        this.db = db;
        this.deals = deals;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deals", deals.list());
        out.put("combos", db.list("""
                SELECT i.id, i.name, i.price, i.options, i.category_id, i.active, c.name AS category_name
                FROM items i LEFT JOIN categories c ON c.id = i.category_id WHERE i.kind = 'COMBO' ORDER BY i.name"""));
        out.put("categories", db.list("SELECT id, name, serve_from, serve_to FROM categories ORDER BY sort, name"));
        out.put("dishes", db.list("""
                SELECT id, name, price, category_id FROM items
                WHERE active AND kind NOT IN ('INGREDIENT', 'BATCH', 'COMBO', 'SERVICE') ORDER BY name"""));
        return out;
    }

    // ---------------- deals ----------------

    @PostMapping("/deals")
    public Map<String, Object> addDeal(@RequestBody Map<String, Object> raw) {
        return deals.save(null, new Body(raw));
    }

    @PutMapping("/deals/{id}")
    public Map<String, Object> saveDeal(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return deals.save(id, new Body(raw));
    }

    @DeleteMapping("/deals/{id}")
    public Map<String, Object> deleteDeal(@PathVariable UUID id) {
        deals.delete(id);
        return Map.of("deleted", true);
    }

    // ---------------- combos ----------------

    /**
     * {name, price, categoryId, active, parts: [{name, choices: [{itemId, extra}]}]}. A part with one choice is fixed
     * ("Beef burger"); with several the customer picks one ("Drink": Coke, Fanta, Sprite +KES 20).
     */
    @PostMapping("/combos")
    public Map<String, Object> addCombo(@RequestBody Map<String, Object> raw) {
        return saveCombo(null, new Body(raw));
    }

    @PutMapping("/combos/{id}")
    public Map<String, Object> updateCombo(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return saveCombo(id, new Body(raw));
    }

    private Map<String, Object> saveCombo(UUID id, Body b) {
        TenantContext.requireRole("MANAGER");
        String name = b.str("name", "").trim();
        if (name.isEmpty() || name.length() > 120) {
            throw ApiException.bad("Give the combo a name, e.g. Burger meal");
        }
        BigDecimal price = b.num("price", null);
        if (price == null || price.signum() <= 0) {
            throw ApiException.bad("Give the combo its price");
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        Set<String> groupNames = new HashSet<>();
        int portions = 0;
        for (Map<String, Object> raw : b.list("parts") == null ? List.<Map<String, Object>>of() : b.list("parts")) {
            Body pb = new Body(raw);
            List<Map<String, Object>> choices = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Map<String, Object> c : pb.list("choices") == null ? List.<Map<String, Object>>of() : pb.list("choices")) {
                Body cb = new Body(c);
                Map<String, Object> dish = db.one("Dish", "SELECT id, name, kind FROM items WHERE id = :id", "id", UUID.fromString(cb.str("itemId")));
                if (List.of("COMBO", "INGREDIENT", "BATCH").contains(dish.get("kind"))) {
                    throw ApiException.bad(dish.get("name") + " can't be part of a combo");
                }
                BigDecimal extra = cb.num("extra", BigDecimal.ZERO);
                if (extra.signum() < 0) {
                    throw ApiException.bad("An extra charge can't be below zero");
                }
                if (names.add((String) dish.get("name"))) {
                    choices.add(Map.of("name", dish.get("name"), "price", extra, "itemId", dish.get("id").toString()));
                }
            }
            if (choices.isEmpty()) {
                continue;
            }
            String label = pb.str("name", "").trim();
            if (label.isEmpty()) {
                label = choices.size() == 1 ? (String) choices.get(0).get("name") : "Choice " + (groups.size() + 1);
            }
            if (!groupNames.add(label)) {
                throw ApiException.bad("Two parts are both called " + label + ". Give each part its own name.");
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", label);
            g.put("required", true);
            g.put("multi", false);
            g.put("choices", choices);
            groups.add(g);
            portions++;
        }
        if (portions < 2) {
            throw ApiException.bad("A combo needs at least two parts, e.g. a burger and a drink");
        }
        UUID category = b.has("categoryId") && !b.str("categoryId").isBlank() ? UUID.fromString(b.str("categoryId")) : null;
        if (category == null) {
            // The menu lists dishes by category: combos without one go under "Combos & meal deals" (first on the menu).
            category = db.value("SELECT id FROM categories WHERE lower(name) = 'combos & meal deals'", UUID.class);
            if (category == null) {
                category = db.insert("INSERT INTO categories (tenant_id, name, sort) VALUES (:t, 'Combos & meal deals', -1) RETURNING id");
            }
        }
        Object[] p = {"name", name, "price", price, "opts", db.toJson(groups), "cat", category, "active", b.bool("active", true), "id", id};
        if (id == null) {
            id = db.insert("""
                    INSERT INTO items (tenant_id, name, kind, price, options, category_id, active, track_stock, tax_rate, unit)
                    VALUES (:t, :name, 'COMBO', :price, cast(:opts as jsonb), :cat, :active, false, 16, 'meal') RETURNING id""", p);
        } else {
            db.one("Combo", "SELECT id FROM items WHERE id = :id AND kind = 'COMBO'", "id", id);
            db.exec("UPDATE items SET name = :name, price = :price, options = cast(:opts as jsonb), category_id = :cat, active = :active WHERE id = :id", p);
        }
        return db.one("Combo", "SELECT id, name, price, options, category_id, active FROM items WHERE id = :id", "id", id);
    }

    // ---------------- meal times ----------------

    /** {from, to} or both empty: when a category is usually served. Nothing is hidden; it only changes the order. */
    @PutMapping("/categories/{id}/meal-time")
    public Map<String, Object> mealTime(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        LocalTime from = b.has("from") && !b.str("from").isBlank() ? Deals.parse(b.str("from")) : null;
        LocalTime to = b.has("to") && !b.str("to").isBlank() ? Deals.parse(b.str("to")) : null;
        if ((from == null) != (to == null) || (from != null && from.equals(to))) {
            throw ApiException.bad("Give both a start and an end time, or neither");
        }
        db.one("Category", "SELECT id FROM categories WHERE id = :id", "id", id);
        db.exec("UPDATE categories SET serve_from = :f, serve_to = :to WHERE id = :id", "id", id,
                "f", from == null ? null : Time.valueOf(from), "to", to == null ? null : Time.valueOf(to));
        return db.one("Category", "SELECT id, name, serve_from, serve_to FROM categories WHERE id = :id", "id", id);
    }
}
