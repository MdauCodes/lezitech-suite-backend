package com.lezitech.suite.catalog;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Transactional
public class CatalogController {

    private static final String ITEM_COLUMNS = """
            i.id, i.category_id, c.name AS category_name, i.name, i.kind, i.sku, i.barcode, i.unit, i.price, i.cost,
            i.wholesale_price, i.wholesale_min_qty, i.tax_rate, i.track_stock, i.reorder_level, i.duration_min, i.station,
            i.needs_prescription, i.options, coalesce(i.color, c.color) AS color, i.active, i.commission_rate,
            coalesce((SELECT sum(qty) FROM stock_levels s WHERE s.item_id = i.id), 0) AS on_hand""";

    private final Db db;

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.settings.PlanLimits limits;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.activity.ActivityLog activity;
    public CatalogController(Db db) {
        this.db = db;
    }

    // ---------- categories ----------

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return db.list("""
                SELECT c.*, (SELECT count(*) FROM items i WHERE i.category_id = c.id AND i.active) AS item_count
                FROM categories c ORDER BY sort, name""");
    }

    @PostMapping("/categories")
    public Map<String, Object> createCategory(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        UUID id = db.insert("""
                INSERT INTO categories (tenant_id, name, color, sort)
                VALUES (:t, :name, :color, coalesce((SELECT max(sort) + 1 FROM categories), 0)) RETURNING id""",
                "name", b.str("name"), "color", b.str("color", null));
        return db.one("Category", "SELECT * FROM categories WHERE id = :id", "id", id);
    }

    @PutMapping("/categories/{id}")
    public Map<String, Object> updateCategory(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        db.exec("UPDATE categories SET name = :name, color = :color WHERE id = :id",
                "id", id, "name", b.str("name"), "color", b.str("color", null));
        return db.one("Category", "SELECT * FROM categories WHERE id = :id", "id", id);
    }

    @DeleteMapping("/categories/{id}")
    public void deleteCategory(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        db.exec("DELETE FROM categories WHERE id = :id", "id", id);
    }

    // ---------- items ----------

    @GetMapping("/items")
    public List<Map<String, Object>> items(@RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "false") boolean all) {
        return db.list("SELECT " + ITEM_COLUMNS + """
                 FROM items i LEFT JOIN categories c ON c.id = i.category_id
                WHERE (:all OR i.active)
                  AND (cast(:q as text) IS NULL OR i.name ILIKE '%' || cast(:q as text) || '%'
                       OR i.barcode = cast(:q as text) OR i.sku ILIKE cast(:q as text))
                ORDER BY c.sort NULLS LAST, i.name""", "q", q == null || q.isBlank() ? null : q.trim(), "all", all);
    }

    @GetMapping("/items/barcode/{code}")
    public Map<String, Object> byBarcode(@PathVariable String code) {
        return db.one("Item with that barcode", "SELECT " + ITEM_COLUMNS
                + " FROM items i LEFT JOIN categories c ON c.id = i.category_id WHERE i.active AND (i.barcode = :code OR i.sku = :code)",
                "code", code);
    }

    @PostMapping("/items")
    public Map<String, Object> createItem(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        limits.checkItems(1);
        UUID id = db.insert("""
                INSERT INTO items (tenant_id, name, kind) VALUES (:t, :name, :kind) RETURNING id""",
                "name", new Body(raw).str("name"), "kind", new Body(raw).str("kind", "PRODUCT"));
        return updateItem(id, raw);
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        String kind = b.str("kind", "PRODUCT");
        Map<String, Object> before = db.find("SELECT name, price, cost, wholesale_price FROM items WHERE id = :id", "id", id).orElse(null);
        if (before != null) {
            BigDecimal np = b.num("price", BigDecimal.ZERO);
            BigDecimal nc = b.num("cost", BigDecimal.ZERO);
            BigDecimal nw = b.num("wholesalePrice", null);
            BigDecimal op = (BigDecimal) before.get("price");
            BigDecimal oc = (BigDecimal) before.get("cost");
            BigDecimal ow = (BigDecimal) before.get("wholesalePrice");
            boolean changed = np.compareTo(op) != 0 || nc.compareTo(oc) != 0 || (nw == null ? ow != null : ow == null || nw.compareTo(ow) != 0);
            if (changed) {
                activity.record("ITEM_PRICE_EDIT", np.compareTo(op) < 0 || (nw != null && ow != null && nw.compareTo(ow) < 0)
                                ? com.lezitech.suite.activity.ActivityLog.NOTICE : com.lezitech.suite.activity.ActivityLog.INFO,
                        before.get("name") + ": " + priceEdits(op, np, ow, nw, oc, nc),
                        null, id, null, null, Map.of());
            }
        }
        db.exec("""
                UPDATE items SET name = :name, kind = :kind, category_id = :categoryId, sku = :sku, barcode = :barcode,
                       unit = :unit, price = :price, cost = :cost, wholesale_price = :wp, wholesale_min_qty = :wmin,
                       tax_rate = :tax, track_stock = :track, reorder_level = :reorder, duration_min = :dur, station = :station,
                       needs_prescription = :rx, options = cast(:options as jsonb), active = :active
                 WHERE id = :id""",
                "id", id, "name", b.str("name"), "kind", kind,
                "categoryId", b.has("categoryId") ? UUID.fromString(b.str("categoryId")) : null,
                "sku", b.str("sku", null), "barcode", b.str("barcode", null), "unit", b.str("unit", "pc"),
                "price", b.num("price", BigDecimal.ZERO), "cost", b.num("cost", BigDecimal.ZERO),
                "wp", b.num("wholesalePrice", null), "wmin", b.num("wholesaleMinQty", null),
                "tax", b.num("taxRate", new BigDecimal("16")),
                "track", b.bool("trackStock", "PRODUCT".equals(kind)), "reorder", b.num("reorderLevel", BigDecimal.ZERO),
                "dur", b.integer("durationMin", null), "station", b.str("station", null),
                "rx", b.bool("needsPrescription", false), "options", db.toJson(b.raw("options")),
                "active", b.bool("active", true));
        // A service's own commission rate (overrides the staff member's); only changed when sent.
        if (b.has("commissionRate")) {
            db.exec("UPDATE items SET commission_rate = :cr WHERE id = :id", "id", id, "cr", rate(b.num("commissionRate", null)));
        }
        return db.one("Item", "SELECT " + ITEM_COLUMNS
                + " FROM items i LEFT JOIN categories c ON c.id = i.category_id WHERE i.id = :id", "id", id);
    }

    @DeleteMapping("/items/{id}")
    public void archiveItem(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        db.exec("UPDATE items SET active = false WHERE id = :id", "id", id);
    }

    /**
     * Spreadsheet import: the browser parses the sheet and posts rows. Categories are created
     * by name, opening stock lands in the default location. Trials convert when people see their own data.
     */
    @PostMapping("/items/import")
    public Map<String, Object> importItems(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        List<Map<String, Object>> rows = new Body(raw).list("rows");
        if (rows.size() > 5000) {
            throw ApiException.bad("Import up to 5,000 rows at a time");
        }
        UUID loc = db.value("SELECT id FROM locations WHERE is_default LIMIT 1", UUID.class);
        long itemCount = limits.items();
        Map<String, UUID> cats = new HashMap<>();
        db.list("SELECT id, name FROM categories").forEach(r ->
                cats.put(r.get("name").toString().toLowerCase(), UUID.fromString(r.get("id").toString())));
        int created = 0;
        int updated = 0;
        for (Map<String, Object> r : rows) {
            Body b = new Body(r);
            if (!b.has("name")) {
                continue;
            }
            UUID catId = null;
            if (b.has("category")) {
                String key = b.str("category").toLowerCase();
                catId = cats.get(key);
                if (catId == null) {
                    catId = db.insert("INSERT INTO categories (tenant_id, name) VALUES (:t, :name) RETURNING id", "name", b.str("category"));
                    cats.put(key, catId);
                }
            }
            UUID existing = db.value("SELECT id FROM items WHERE lower(name) = lower(:name) OR (barcode IS NOT NULL AND barcode = :barcode) LIMIT 1",
                    UUID.class, "name", b.str("name"), "barcode", b.str("barcode", null));
            UUID id;
            if (existing == null) {
                limits.checkItems(itemCount++, 1);
                id = db.insert("INSERT INTO items (tenant_id, name, kind) VALUES (:t, :name, :kind) RETURNING id",
                        "name", b.str("name"), "kind", b.str("kind", "PRODUCT").toUpperCase());
                created++;
            } else {
                id = existing;
                updated++;
            }
            db.exec("""
                    UPDATE items SET category_id = coalesce(:cat, category_id), barcode = coalesce(:barcode, barcode),
                           sku = coalesce(:sku, sku), unit = coalesce(:unit, unit), price = coalesce(:price, price),
                           cost = coalesce(:cost, cost), reorder_level = coalesce(:reorder, reorder_level) WHERE id = :id""",
                    "id", id, "cat", catId, "barcode", b.str("barcode", null), "sku", b.str("sku", null), "unit", b.str("unit", null),
                    "price", b.num("price", null), "cost", b.num("cost", null), "reorder", b.num("reorderLevel", null));
            if (b.has("stock") && loc != null) {
                BigDecimal qty = b.num("stock");
                BigDecimal current = db.value("SELECT qty FROM stock_levels WHERE item_id = :id AND location_id = :loc",
                        BigDecimal.class, "id", id, "loc", loc);
                BigDecimal delta = qty.subtract(current == null ? BigDecimal.ZERO : current);
                if (delta.signum() != 0) {
                    db.exec("""
                            INSERT INTO stock_levels (tenant_id, item_id, location_id, qty) VALUES (:t, :id, :loc, :qty)
                            ON CONFLICT (item_id, location_id) DO UPDATE SET qty = excluded.qty""", "id", id, "loc", loc, "qty", qty);
                    db.exec("""
                            INSERT INTO stock_moves (tenant_id, item_id, location_id, qty, reason, ref, user_id)
                            VALUES (:t, :id, :loc, :delta, 'OPENING', 'Spreadsheet import', :u)""", "id", id, "loc", loc, "delta", delta);
                }
            }
        }
        return Map.of("created", created, "updated", updated);
    }

    // ---------- locations ----------

    @GetMapping("/locations")
    public List<Map<String, Object>> locations() {
        return db.list("""
                SELECT l.*, p.name AS parent_name FROM locations l LEFT JOIN locations p ON p.id = l.parent_id
                ORDER BY l.is_default DESC, l.kind, l.name""");
    }

    @PostMapping("/locations")
    public Map<String, Object> createLocation(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        if (!b.has("parentId")) {
            limits.checkLocations(1); // a store inside a branch doesn't count as another branch
        }
        UUID id = db.insert("""
                INSERT INTO locations (tenant_id, name, kind, parent_id) VALUES (:t, :name, :kind, :parentId) RETURNING id""",
                "name", b.str("name"), "kind", b.str("kind", "SHOP"),
                "parentId", b.has("parentId") ? UUID.fromString(b.str("parentId")) : null);
        return db.one("Location", "SELECT * FROM locations WHERE id = :id", "id", id);
    }

    /** A commission percentage between 0 and 100, or null to clear it. */
    public static BigDecimal rate(BigDecimal r) {
        if (r == null) {
            return null;
        }
        if (r.signum() < 0 || r.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw com.lezitech.suite.common.ApiException.bad("A commission is a percentage between 0 and 100");
        }
        return r;
    }

    /** Only what changed, in plain numbers: "shelf price 340 to 320, lowest price none to 289". */
    static String priceEdits(BigDecimal op, BigDecimal np, BigDecimal ow, BigDecimal nw, BigDecimal oc, BigDecimal nc) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (np.compareTo(op) != 0) {
            parts.add("shelf price " + plainNum(op) + " to " + plainNum(np));
        }
        if (nw == null ? ow != null : ow == null || nw.compareTo(ow) != 0) {
            parts.add("lowest price " + plainNum(ow) + " to " + plainNum(nw));
        }
        if (nc.compareTo(oc) != 0) {
            parts.add("buying price " + plainNum(oc) + " to " + plainNum(nc));
        }
        return String.join(", ", parts);
    }

    private static String plainNum(BigDecimal v) {
        return v == null ? "none" : v.stripTrailingZeros().toPlainString();
    }
}
