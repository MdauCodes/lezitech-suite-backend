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
            i.photo_file_id, i.allergens, i.allergen_note, i.buy_unit, i.buy_size, i.buy_price, i.pack_price,
            (i.unavailable_until IS NOT NULL AND i.unavailable_until > now()) AS finished, i.finished_by_stock,
            i.sold_by_weight, i.age_restricted, i.count_nightly, i.baked_daily, i.day_old_of, i.variant_of, i.variant_values, i.variant_axes,
            coalesce((SELECT sum(qty) FROM stock_levels s WHERE s.item_id = i.id
                      -- Staff at a branch see that branch's stock; the owner and the main place see it all.
                      AND s.location_id = coalesce((SELECT location_id FROM users WHERE id = cast(:u as uuid)), s.location_id)), 0) AS on_hand""";

    private final Db db;

    /** JSON columns come back as Jackson nodes; this turns them into plain lists and maps. */
    private static final com.fasterxml.jackson.databind.ObjectMapper PLAIN = new com.fasterxml.jackson.databind.ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.settings.PlanLimits limits;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.activity.ActivityLog activity;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.files.FileService files;
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
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        Body b = new Body(raw);
        UUID id = db.insert("""
                INSERT INTO categories (tenant_id, name, color, sort)
                VALUES (:t, :name, :color, coalesce((SELECT max(sort) + 1 FROM categories), 0)) RETURNING id""",
                "name", b.str("name"), "color", b.str("color", null));
        return db.one("Category", "SELECT * FROM categories WHERE id = :id", "id", id);
    }

    @PutMapping("/categories/{id}")
    public Map<String, Object> updateCategory(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        Body b = new Body(raw);
        db.exec("UPDATE categories SET name = :name, color = :color WHERE id = :id",
                "id", id, "name", b.str("name"), "color", b.str("color", null));
        return db.one("Category", "SELECT * FROM categories WHERE id = :id", "id", id);
    }

    @DeleteMapping("/categories/{id}")
    public void deleteCategory(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        db.exec("DELETE FROM categories WHERE id = :id", "id", id);
    }

    // ---------- items ----------

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.restaurant.Deals deals;

    /**
     * Products. families=true also lists products with sizes or colours (the "family" rows the till and the products
     * screen group variants under); other screens (stock, buying, recipes) only get things that are stocked and sold.
     */
    @GetMapping("/items")
    public List<Map<String, Object>> items(@RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "false") boolean all,
                                           @RequestParam(defaultValue = "false") boolean kitchen,
                                           @RequestParam(defaultValue = "false") boolean families) {
        List<Map<String, Object>> list = db.list("SELECT " + ITEM_COLUMNS + """
                 FROM items i LEFT JOIN categories c ON c.id = i.category_id
                WHERE (:all OR i.active) AND (:kitchen OR i.kind NOT IN ('INGREDIENT', 'BATCH'))
                  AND (:families OR i.variant_axes IS NULL)
                  AND (cast(:q as text) IS NULL OR i.name ILIKE '%' || cast(:q as text) || '%'
                       OR i.barcode = cast(:q as text) OR i.sku ILIKE cast(:q as text))
                ORDER BY c.sort NULLS LAST, i.name""", "q", q == null || q.isBlank() ? null : q.trim(), "all", all, "kitchen", kitchen, "families", families);
        // R6: the happy-hour price right now, so the till shows what the bill will charge.
        deals.markHappy(list);
        return list;
    }

    @GetMapping("/items/barcode/{code}")
    public Map<String, Object> byBarcode(@PathVariable String code) {
        return db.one("Item with that barcode", "SELECT " + ITEM_COLUMNS
                + " FROM items i LEFT JOIN categories c ON c.id = i.category_id WHERE i.active AND i.kind NOT IN ('INGREDIENT', 'BATCH')"
                + " AND (i.barcode = :code OR i.sku = :code)",
                "code", code);
    }

    @PostMapping("/items")
    public Map<String, Object> createItem(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        limits.checkItems(1);
        UUID id = db.insert("""
                INSERT INTO items (tenant_id, name, kind, category_id, sku) VALUES (:t, :name, :kind, :cat, :sku) RETURNING id""",
                "name", new Body(raw).str("name"), "kind", new Body(raw).str("kind", "PRODUCT"),
                // Category and code first, so the automatic code starts with the category's letters (DRI-0012).
                "cat", new Body(raw).has("categoryId") ? UUID.fromString(new Body(raw).str("categoryId")) : null,
                "sku", new Body(raw).str("sku", "").isBlank() ? null : codeFreeOrThrow(new Body(raw).str("sku")));
        Map<String, Object> saved = updateItem(id, raw);
        // "How many do you have now?" on a new product: its opening stock, at the place the person works.
        Body b = new Body(raw);
        BigDecimal opening = b.num("openingStock", null);
        if (opening != null && opening.signum() > 0 && b.bool("trackStock", false)) {
            UUID loc = db.value("""
                    SELECT coalesce((SELECT location_id FROM users WHERE id = cast(:u as uuid)), (SELECT id FROM locations WHERE is_default LIMIT 1))""", UUID.class);
            if (loc != null) {
                db.exec("""
                        INSERT INTO stock_levels (tenant_id, item_id, location_id, qty) VALUES (:t, :id, :loc, :qty)
                        ON CONFLICT (item_id, location_id) DO UPDATE SET qty = stock_levels.qty + excluded.qty""", "id", id, "loc", loc, "qty", opening);
                db.exec("""
                        INSERT INTO stock_moves (tenant_id, item_id, location_id, qty, reason, ref, user_id)
                        VALUES (:t, :id, :loc, :qty, 'OPENING', 'When added', :u)""", "id", id, "loc", loc, "qty", opening);
            }
        }
        return saved;
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> updateItem(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        Body b = new Body(raw);
        String kind = b.str("kind", "PRODUCT");
        if (b.str("sku", null) != null && !b.str("sku", "").isBlank()) {
            VariantsController.codeFree(db, b.str("sku"), id);
        }
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
        // Buy by the carton, sell by the piece: the pack's name, pieces in it, its buying price and (optional) selling price.
        if ("PRODUCT".equals(kind) && b.has("packSize")) {
            BigDecimal size = b.num("packSize", null);
            if (size == null || size.signum() <= 0) {
                db.exec("UPDATE items SET buy_unit = NULL, buy_size = NULL, buy_price = NULL, pack_price = NULL WHERE id = :id", "id", id);
            } else {
                if (size.compareTo(BigDecimal.ONE) <= 0) {
                    throw ApiException.bad("A pack holds more than one piece");
                }
                String unitName = b.str("packUnit", "").trim();
                BigDecimal buy = b.num("packBuyPrice", null);
                BigDecimal sell = b.num("packPrice", null);
                if ((buy != null && buy.signum() < 0) || (sell != null && sell.signum() < 0)) {
                    throw ApiException.bad("A price can't be below zero");
                }
                db.exec("""
                        UPDATE items SET buy_unit = :u, buy_size = :s, buy_price = :bp, pack_price = :pp,
                               cost = CASE WHEN cast(:bp as numeric) IS NOT NULL THEN round(cast(:bp as numeric) / :s, 2) ELSE cost END
                        WHERE id = :id""", "id", id, "u", unitName.isEmpty() ? "pack" : unitName.toLowerCase(), "s", size, "bp", buy, "pp", sell);
            }
        }
        familyFollows(id);
        if (b.str("barcode", null) != null && !b.str("barcode", "").isBlank()) {
            usesBarcodes();
        }
        // R7: priced per kg and sold by the weight from the scale. Only changed when sent.
        if (b.has("soldByWeight")) {
            boolean kg = b.bool("soldByWeight", false);
            db.exec("UPDATE items SET sold_by_weight = :w, unit = CASE WHEN :w THEN 'kg' ELSE unit END WHERE id = :id", "id", id, "w", kg);
        }
        // "18+ only": the till checks the customer's age before such an item is paid for. Only the owner or a manager changes it.
        if (b.has("ageRestricted")) {
            boolean now = b.bool("ageRestricted", false);
            if (now != Boolean.TRUE.equals(db.value("SELECT age_restricted FROM items WHERE id = :id", Boolean.class, "id", id))) {
                TenantContext.requireRole("MANAGER");
                db.exec("UPDATE items SET age_restricted = :a WHERE id = :id", "id", id, "a", now);
                activity.record("ITEM_AGE_FLAG", com.lezitech.suite.activity.ActivityLog.INFO,
                        b.str("name") + (now ? " is now marked 18+ only" : " is no longer marked 18+ only"), null, id, null, null, Map.of());
            }
        }
        // The owner's "Count every night" choice for the nightly bottle count. Only changed when sent.
        if (b.has("countNightly")) {
            db.exec("UPDATE items SET count_nightly = :c WHERE id = :id", "id", id, "c", b.bool("countNightly", false));
        }
        // A service's own commission rate (overrides the staff member's); only changed when sent.
        if (b.has("commissionRate")) {
            db.exec("UPDATE items SET commission_rate = :cr WHERE id = :id", "id", id, "cr", rate(b.num("commissionRate", null)));
        }
        // Allergens shown on the menu: only names from the fixed list, plus a short note. Only changed when sent.
        if (raw.containsKey("allergens")) {
            db.exec("UPDATE items SET allergens = cast(:a as text[]) WHERE id = :id", "id", id, "a", allergens(raw.get("allergens")));
        }
        if (raw.containsKey("allergenNote")) {
            String note = b.str("allergenNote", null);
            note = note == null || note.isBlank() ? null : note.trim();
            db.exec("UPDATE items SET allergen_note = :n WHERE id = :id", "id", id,
                    "n", note == null ? null : note.length() > 200 ? note.substring(0, 200) : note);
        }
        return db.one("Item", "SELECT " + ITEM_COLUMNS
                + " FROM items i LEFT JOIN categories c ON c.id = i.category_id WHERE i.id = :id", "id", id);
    }

    /** The allergens a menu item can be marked with (the common ones diners ask about). */
    public static final List<String> ALLERGENS = List.of("nuts", "peanuts", "gluten", "dairy", "eggs", "fish", "shellfish", "soy", "sesame");

    /** A Postgres array literal of the known allergens in the input, in the list's order; anything else is refused. */
    static String allergens(Object raw) {
        if (raw == null) {
            return "{}";
        }
        if (!(raw instanceof List<?> list)) {
            throw ApiException.bad("Choose allergens from the list");
        }
        java.util.Set<String> chosen = new java.util.HashSet<>();
        for (Object o : list) {
            String a = String.valueOf(o).trim().toLowerCase(java.util.Locale.ROOT);
            if (!ALLERGENS.contains(a)) {
                throw ApiException.bad("\"" + o + "\" isn't on the allergen list. Write it in the note instead.");
            }
            chosen.add(a);
        }
        return "{" + String.join(",", ALLERGENS.stream().filter(chosen::contains).toList()) + "}";
    }

    @DeleteMapping("/items/{id}")
    public void archiveItem(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        // Putting a product away puts away its sizes and colours too.
        db.exec("UPDATE items SET active = false WHERE id = :id OR variant_of = :id", "id", id);
    }

    private String codeFreeOrThrow(String sku) {
        VariantsController.codeFree(db, sku, null);
        return sku.trim();
    }

    /** A product was saved with a barcode: this business uses barcodes, so the till and screens offer scanning. */
    void usesBarcodes() {
        db.exec("""
                UPDATE tenants SET settings = settings || jsonb_build_object('features', coalesce(settings -> 'features', '{}'::jsonb) || '{"barcode": true}'::jsonb)
                WHERE id = :t AND coalesce(settings #>> '{features,barcode}', 'false') <> 'true'""");
    }

    /**
     * A product with sizes or colours isn't stocked itself, and its sizes follow its name, category, kind, unit, tax
     * and kitchen station ("T-shirt" renamed "Polo" makes "Polo M / Red").
     */
    @SuppressWarnings("unchecked")
    private void familyFollows(UUID id) {
        Map<String, Object> f = db.find("SELECT * FROM items WHERE id = :id AND variant_axes IS NOT NULL", "id", id).orElse(null);
        if (f == null) {
            return;
        }
        db.exec("UPDATE items SET track_stock = false WHERE id = :id", "id", id);
        List<Map<String, Object>> axes = PLAIN.convertValue(f.get("variantAxes"), List.class);
        for (Map<String, Object> v : db.list("SELECT id, variant_values FROM items WHERE variant_of = :id", "id", id)) {
            Map<String, Object> vals = v.get("variantValues") == null ? null : PLAIN.convertValue(v.get("variantValues"), Map.class);
            List<String> parts = new java.util.ArrayList<>();
            for (Map<String, Object> a : axes) {
                Object x = vals == null ? null : vals.get(a.get("name"));
                if (x != null) {
                    parts.add(x.toString());
                }
            }
            db.exec("""
                    UPDATE items c SET name = :name, category_id = p.category_id, kind = p.kind, unit = p.unit, tax_rate = p.tax_rate,
                           station = p.station, age_restricted = p.age_restricted FROM items p WHERE p.id = :fid AND c.id = :vid""",
                    "fid", id, "vid", UUID.fromString(v.get("id").toString()), "name", f.get("name") + " " + String.join(" / ", parts));
        }
    }

    /**
     * Spreadsheet import: the browser parses the sheet and posts rows. Categories are created
     * by name, opening stock lands in the default location. Trials convert when people see their own data.
     */
    @PostMapping("/items/import")
    public Map<String, Object> importItems(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
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
        int photos = 0;
        List<String> photoProblems = new java.util.ArrayList<>();
        List<String> codeNotes = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) {
            Body b = new Body(r);
            if (!b.has("name") && !b.has("group")) {
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
            // A size or colour of another product: "Group" names the product, "Size" and/or "Colour" say which one.
            UUID variant = b.has("group") && (b.has("size") || b.has("colour")) ? variantRow(b, catId) : null;
            UUID existing = variant != null ? variant : db.value("SELECT id FROM items WHERE lower(name) = lower(:name) OR (barcode IS NOT NULL AND barcode = :barcode) LIMIT 1",
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
            String rowSku = b.str("sku", null);
            if (rowSku != null && !rowSku.isBlank() && db.value("SELECT name FROM items WHERE lower(sku) = lower(:s) AND id <> :id LIMIT 1",
                    String.class, "s", rowSku.trim(), "id", id) != null) {
                codeNotes.add(b.str("name", b.str("group", "")) + ": code " + rowSku.trim() + " is already on another product, so it kept its own code");
                r = new java.util.HashMap<>(r);
                r.remove("sku");
                b = new Body(r);
            }
            db.exec("""
                    UPDATE items SET category_id = coalesce(:cat, category_id), barcode = coalesce(:barcode, barcode),
                           sku = coalesce(:sku, sku), unit = coalesce(:unit, unit), price = coalesce(:price, price),
                           cost = coalesce(:cost, cost), reorder_level = coalesce(:reorder, reorder_level) WHERE id = :id""",
                    "id", id, "cat", catId, "barcode", b.str("barcode", null), "sku", b.str("sku", null), "unit", b.str("unit", null),
                    "price", b.num("price", null), "cost", b.num("cost", null), "reorder", b.num("reorderLevel", null));
            // Pack columns: "Pack" (carton), "Pieces in a pack", "Pack buying price", "Pack selling price".
            BigDecimal packSize = b.num("packSize", null);
            if (packSize != null && packSize.compareTo(BigDecimal.ONE) > 0) {
                BigDecimal buy = b.num("packBuyPrice", null);
                db.exec("""
                        UPDATE items SET buy_unit = :u, buy_size = :s, buy_price = coalesce(:bp, buy_price), pack_price = coalesce(:pp, pack_price),
                               cost = CASE WHEN cast(:bp as numeric) IS NOT NULL THEN round(cast(:bp as numeric) / :s, 2) ELSE cost END
                        WHERE id = :id AND kind = 'PRODUCT'""", "id", id, "u", b.str("pack", "pack").trim().toLowerCase(), "s", packSize,
                        "bp", buy, "pp", b.num("packPrice", null));
            }
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
            // "Photo link": the item's photo fetched from that web address (a bad link doesn't stop the import).
            if (b.has("photo") && !b.str("photo").isBlank()) {
                try {
                    byte[] img = com.lezitech.suite.files.PhotoLinks.fetch(b.str("photo"), 2 * 1024 * 1024);
                    UUID file = files.save(com.lezitech.suite.files.FileService.Purpose.ITEM_PHOTO, img);
                    Object old = db.value("SELECT photo_file_id FROM items WHERE id = :id", Object.class, "id", id);
                    db.exec("UPDATE items SET photo_file_id = :f WHERE id = :id", "f", file, "id", id);
                    if (old != null) {
                        files.remove(UUID.fromString(old.toString()));
                    }
                    photos++;
                } catch (ApiException e) {
                    photoProblems.add(b.str("name", b.str("group", "")) + ": " + e.getMessage());
                }
            }
        }
        if (rows.stream().anyMatch(r -> r.get("barcode") != null && !String.valueOf(r.get("barcode")).isBlank())) {
            usesBarcodes();
        }
        return Map.of("created", created, "updated", updated, "photos", photos, "photoProblems", photoProblems.stream().limit(10).toList(),
                "codeNotes", codeNotes.stream().limit(10).toList());
    }

    /**
     * The item for an import row that is a size or colour of a product: the product (the "family") is found by name or
     * made, its Size and Colour choices grow to include this row's, and the variant is found or made.
     */
    @SuppressWarnings("unchecked")
    private UUID variantRow(Body b, UUID catId) {
        String group = b.str("group").trim();
        UUID fam = db.value("SELECT id FROM items WHERE lower(name) = lower(:g) AND variant_of IS NULL ORDER BY active DESC LIMIT 1",
                UUID.class, "g", group);
        if (fam == null) {
            fam = db.insert("INSERT INTO items (tenant_id, name, kind, category_id, track_stock, variant_axes) VALUES (:t, :g, 'PRODUCT', :cat, false, '[]'::jsonb) RETURNING id",
                    "g", group, "cat", catId);
        }
        Map<String, Object> f = db.one("Product", "SELECT name, variant_axes FROM items WHERE id = :id", "id", fam);
        List<Map<String, Object>> axes = new java.util.ArrayList<>();
        if (f.get("variantAxes") != null) {
            for (Object o : PLAIN.convertValue(f.get("variantAxes"), List.class)) {
                Map<String, Object> a = new java.util.LinkedHashMap<>((Map<String, Object>) o);
                a.put("values", new java.util.ArrayList<>((List<String>) a.get("values")));
                axes.add(a);
            }
        }
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String axis : List.of("Size", "Colour")) {
            String v = b.str(axis.toLowerCase(), "").trim();
            if (v.isEmpty()) {
                continue;
            }
            Map<String, Object> a = axes.stream().filter(x -> axis.equalsIgnoreCase((String) x.get("name"))).findFirst().orElse(null);
            if (a == null) {
                a = new java.util.LinkedHashMap<>(Map.of("name", axis, "values", new java.util.ArrayList<String>()));
                axes.add(a);
            }
            List<String> vals = (List<String>) a.get("values");
            String match = vals.stream().filter(x -> x.equalsIgnoreCase(v)).findFirst().orElse(null);
            if (match == null) {
                vals.add(v);
                match = v;
            }
            values.put((String) a.get("name"), match);
        }
        db.exec("UPDATE items SET variant_axes = cast(:axes as jsonb), track_stock = false, active = true WHERE id = :id",
                "id", fam, "axes", db.toJson(VariantsController.cleanAxes(axes)));
        UUID vid = db.value("SELECT id FROM items WHERE variant_of = :f AND variant_values = cast(:v as jsonb) LIMIT 1",
                UUID.class, "f", fam, "v", db.toJson(values));
        String name = f.get("name") + " " + VariantsController.label(values);
        if (vid == null) {
            vid = db.insert("""
                    INSERT INTO items (tenant_id, name, kind, category_id, unit, tax_rate, track_stock, variant_of, variant_values)
                    SELECT tenant_id, :name, kind, coalesce(:cat, category_id), unit, tax_rate, true, id, cast(:v as jsonb) FROM items WHERE id = :f RETURNING id""",
                    "f", fam, "name", name, "cat", catId, "v", db.toJson(values));
        }
        db.exec("UPDATE items SET active = true WHERE id = :id", "id", vid);
        if (!b.has("sku")) {
            VariantsController.giveSizeCode(db, fam, vid, values);
        }
        return vid;
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
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
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
