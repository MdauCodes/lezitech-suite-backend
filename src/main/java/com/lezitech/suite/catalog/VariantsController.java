package com.lezitech.suite.catalog;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sizes, colours and other choices of one product. The product becomes a "family" that holds the choices
 * (e.g. Size: S, M, L and Colour: Red, Blue); each combination is its own item with its own price, cost, barcode,
 * stock and reorder level, named "T-shirt M / Red" so receipts, stock and reports read naturally.
 * The family itself isn't sold or stocked: the till asks which one.
 */
@RestController
@RequestMapping("/api/items/{id}/variants")
@Transactional
public class VariantsController {

    static final int MAX_AXES = 3;
    static final int MAX_VALUES = 30;
    static final int MAX_VARIANTS = 200;

    private final Db db;

    public VariantsController(Db db) {
        this.db = db;
    }

    /** The family's choices and every variant with its price, stock and codes. */
    @GetMapping
    public Map<String, Object> get(@PathVariable UUID id) {
        Map<String, Object> family = db.one("Product", "SELECT id, name, variant_axes, variant_of FROM items WHERE id = :id", "id", id);
        if (family.get("variantOf") != null) {
            throw ApiException.bad("That is already a size or colour of another product");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", family.get("name"));
        out.put("axes", family.get("variantAxes") == null ? List.of() : family.get("variantAxes"));
        out.put("variants", db.list("""
                SELECT i.id, i.name, i.variant_values, i.price, i.cost, i.sku, i.barcode, i.reorder_level, i.active, i.photo_file_id,
                       coalesce((SELECT sum(qty) FROM stock_levels s WHERE s.item_id = i.id), 0) AS on_hand
                FROM items i WHERE i.variant_of = :id ORDER BY i.active DESC, i.created_at""", "id", id));
        return out;
    }

    /**
     * Save the choices and the variants. Variants are matched by id, else by their choices; new combinations become
     * new items (copying the family's category, kind, unit, tax and photo); combinations left out are put away
     * (archived, so past sales and stock history stay). Sending no choices turns the product back into a single one.
     */
    @PutMapping
    public Map<String, Object> save(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        Map<String, Object> family = db.one("Product", "SELECT * FROM items WHERE id = :id", "id", id);
        if (family.get("variantOf") != null) {
            throw ApiException.bad("That is already a size or colour of another product");
        }
        Body b = new Body(raw);
        List<Map<String, Object>> axes = cleanAxes(b.list("axes"));
        List<Map<String, Object>> rows = b.list("variants") == null ? List.of() : b.list("variants");

        if (axes.isEmpty()) {
            db.exec("UPDATE items SET active = false WHERE variant_of = :id", "id", id);
            db.exec("UPDATE items SET variant_axes = NULL, track_stock = kind = 'PRODUCT' WHERE id = :id", "id", id);
            return get(id);
        }
        if (rows.size() > MAX_VARIANTS) {
            throw ApiException.bad("Up to " + MAX_VARIANTS + " sizes and colours on one product");
        }
        boolean hadStock = Boolean.TRUE.equals(db.value("""
                SELECT exists(SELECT 1 FROM stock_levels WHERE item_id = :id AND qty <> 0) AND (SELECT variant_axes IS NULL FROM items WHERE id = :id)""",
                Boolean.class, "id", id));
        if (hadStock) {
            throw ApiException.bad("This product has stock of its own. Count it into its sizes first: set its stock to 0, then add the sizes with their stock.");
        }
        db.exec("UPDATE items SET variant_axes = cast(:axes as jsonb), track_stock = false WHERE id = :id",
                "id", id, "axes", db.toJson(axes));

        Set<String> seen = new HashSet<>();
        Set<UUID> kept = new HashSet<>();
        boolean usedBarcode = false;
        String familyName = (String) family.get("name");
        for (Map<String, Object> r : rows) {
            Body v = new Body(r);
            Map<String, String> values = cleanValues(v.map("values"), axes);
            String key = values.toString();
            if (!seen.add(key)) {
                throw ApiException.bad(label(values) + " is listed twice");
            }
            UUID vid = v.has("id") ? UUID.fromString(v.str("id")) : db.value("""
                    SELECT id FROM items WHERE variant_of = :id AND variant_values = cast(:vals as jsonb) LIMIT 1""",
                    UUID.class, "id", id, "vals", db.toJson(values));
            if (vid != null && !Boolean.TRUE.equals(db.value("SELECT variant_of = :id FROM items WHERE id = :vid", Boolean.class, "id", id, "vid", vid))) {
                throw ApiException.bad("That size or colour belongs to another product");
            }
            if (vid == null) {
                vid = db.insert("""
                        INSERT INTO items (tenant_id, name, kind, category_id, unit, tax_rate, station, track_stock, photo_file_id,
                                           color, needs_prescription, variant_of)
                        SELECT tenant_id, :name, kind, category_id, unit, tax_rate, station, kind = 'PRODUCT', photo_file_id,
                               color, needs_prescription, id FROM items WHERE id = :id RETURNING id""",
                        "id", id, "name", familyName + " " + label(values));
            }
            String sku = blank(v.str("sku", null));
            if (sku != null) {
                codeFree(db, sku, vid);
            }
            String barcode = blank(v.str("barcode", null));
            if (barcode != null && Boolean.TRUE.equals(db.value(
                    "SELECT exists(SELECT 1 FROM items WHERE barcode = :b AND id <> :vid)", Boolean.class, "b", barcode, "vid", vid))) {
                throw ApiException.bad("Barcode " + barcode + " is already on another product");
            }
            BigDecimal price = v.num("price", (BigDecimal) family.get("price"));
            BigDecimal cost = v.num("cost", (BigDecimal) family.get("cost"));
            if (price.signum() < 0 || cost.signum() < 0) {
                throw ApiException.bad("Prices can't be below zero");
            }
            db.exec("""
                    UPDATE items SET name = :name, variant_values = cast(:vals as jsonb), price = :price, cost = :cost,
                           barcode = :barcode, reorder_level = :reorder, active = true WHERE id = :vid""",
                    "vid", vid, "name", familyName + " " + label(values), "vals", db.toJson(values), "price", price, "cost", cost,
                    "barcode", barcode, "reorder", v.num("reorderLevel", BigDecimal.ZERO));
            if (sku != null) {
                db.exec("UPDATE items SET sku = :sku WHERE id = :vid", "vid", vid, "sku", sku);
            } else {
                giveSizeCode(db, id, vid, values);
            }
            kept.add(vid);
            if (barcode != null) {
                usedBarcode = true;
            }
        }
        if (usedBarcode) {
            // This business uses barcodes: the till and screens offer scanning from now on.
            db.exec("""
                    UPDATE tenants SET settings = settings || jsonb_build_object('features', coalesce(settings -> 'features', '{}'::jsonb) || '{"barcode": true}'::jsonb)
                    WHERE id = :t AND coalesce(settings #>> '{features,barcode}', 'false') <> 'true'""");
        }
        // Put away combinations no longer listed (their history stays).
        for (Map<String, Object> old : db.list("SELECT id FROM items WHERE variant_of = :id AND active", "id", id)) {
            UUID oid = UUID.fromString(old.get("id").toString());
            if (!kept.contains(oid)) {
                db.exec("UPDATE items SET active = false WHERE id = :oid", "oid", oid);
            }
        }
        return get(id);
    }

    /** A code typed for a product must not already be on another product of the business. */
    public static void codeFree(Db db, String sku, UUID self) {
        String other = db.value("SELECT name FROM items WHERE lower(sku) = lower(:s) AND (cast(:self as uuid) IS NULL OR id <> cast(:self as uuid)) LIMIT 1",
                String.class, "s", sku.trim(), "self", self == null ? null : self.toString());
        if (other != null) {
            throw ApiException.bad("Code " + sku.trim() + " is already on " + other + ". Give this one another code, or leave it empty and Busynez makes one.");
        }
    }

    /**
     * A size's own code: its product's code and the size, e.g. CLO-0004-M-RED. Kept if the size already has that
     * kind of code (or one the owner typed that isn't a plain number code).
     */
    public static void giveSizeCode(Db db, UUID family, UUID vid, Map<String, String> values) {
        String famSku = db.value("SELECT sku FROM items WHERE id = :f", String.class, "f", family);
        String current = db.value("SELECT sku FROM items WHERE id = :v", String.class, "v", vid);
        if (famSku == null) {
            return;
        }
        if (current != null && current.toUpperCase().startsWith(famSku.toUpperCase() + "-")) {
            return;
        }
        StringBuilder code = new StringBuilder(famSku);
        for (String val : values.values()) {
            String part = val.toUpperCase().replaceAll("[^A-Z0-9]", "");
            if (!part.isEmpty()) {
                code.append('-').append(part, 0, Math.min(6, part.length()));
            }
        }
        String base = code.toString();
        String candidate = base;
        for (int n = 2; Boolean.TRUE.equals(db.value("SELECT exists(SELECT 1 FROM items WHERE lower(sku) = lower(:s) AND id <> :v)",
                Boolean.class, "s", candidate, "v", vid)); n++) {
            candidate = base + "-" + n;
        }
        db.exec("UPDATE items SET sku = :s WHERE id = :v", "s", candidate, "v", vid);
    }

    /** "M / Red", in the order of the choices. */
    static String label(Map<String, String> values) {
        return String.join(" / ", values.values());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Up to 3 choices (Size, Colour...), each with up to 30 options, names trimmed, no repeats. */
    static List<Map<String, Object>> cleanAxes(List<Map<String, Object>> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        Set<String> names = new HashSet<>();
        for (Map<String, Object> a : raw) {
            String name = String.valueOf(a.getOrDefault("name", "")).trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > 30) {
                throw ApiException.bad("Keep the choice name short, like Size or Colour");
            }
            if (!names.add(name.toLowerCase())) {
                throw ApiException.bad(name + " is listed twice");
            }
            List<String> values = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (a.get("values") instanceof List<?> list) {
                for (Object o : list) {
                    String val = String.valueOf(o).trim();
                    if (!val.isEmpty() && seen.add(val.toLowerCase())) {
                        if (val.length() > 40) {
                            throw ApiException.bad("Keep each " + name.toLowerCase() + " short (up to 40 letters)");
                        }
                        values.add(val);
                    }
                }
            }
            if (values.isEmpty()) {
                throw ApiException.bad("Give " + name + " at least one option, e.g. S, M, L");
            }
            if (values.size() > MAX_VALUES) {
                throw ApiException.bad("Up to " + MAX_VALUES + " options for " + name);
            }
            out.add(Map.of("name", name, "values", values));
        }
        if (out.size() > MAX_AXES) {
            throw ApiException.bad("Up to " + MAX_AXES + " choices, e.g. Size, Colour and Material");
        }
        return out;
    }

    /** One variant's choice for each axis, from that axis's options, in axis order. */
    @SuppressWarnings("unchecked")
    static Map<String, String> cleanValues(Map<String, Object> raw, List<Map<String, Object>> axes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> a : axes) {
            String name = (String) a.get("name");
            Object v = raw == null ? null : raw.get(name);
            String val = v == null ? "" : String.valueOf(v).trim();
            List<String> allowed = (List<String>) a.get("values");
            String match = allowed.stream().filter(x -> x.equalsIgnoreCase(val)).findFirst().orElse(null);
            if (match == null) {
                throw ApiException.bad("Choose the " + name.toLowerCase() + " for each size or colour");
            }
            out.put(name, match);
        }
        return out;
    }
}
