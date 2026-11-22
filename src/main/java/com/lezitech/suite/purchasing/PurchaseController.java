package com.lezitech.suite.purchasing;

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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Buying from suppliers: order, then receive into stock (with batch and expiry where it matters). */
@RestController
@RequestMapping("/api/purchases")
@Transactional
public class PurchaseController {

    private final Db db;
    private final StockService stock;
    private final ActivityLog activity;

    public PurchaseController(Db db, StockService stock, ActivityLog activity) {
        this.db = db;
        this.stock = stock;
        this.activity = activity;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return db.list("""
                SELECT p.*, s.name AS supplier_name, l.name AS location_name,
                       (SELECT count(*) FROM purchase_lines pl WHERE pl.po_id = p.id) AS line_count
                FROM purchase_orders p LEFT JOIN suppliers s ON s.id = p.supplier_id LEFT JOIN locations l ON l.id = p.location_id
                ORDER BY p.created_at DESC LIMIT 200""");
    }

    /** Items at or below their reorder level, with a suggested order quantity. */
    @GetMapping("/suggestions")
    public List<Map<String, Object>> suggestions() {
        return db.list("""
                SELECT i.id, i.name, i.unit, i.cost, i.reorder_level, coalesce(sum(s.qty), 0) AS on_hand,
                       greatest(i.reorder_level * 2 - coalesce(sum(s.qty), 0), 1) AS suggested_qty
                FROM items i LEFT JOIN stock_levels s ON s.item_id = i.id
                WHERE i.active AND i.track_stock
                GROUP BY i.id HAVING coalesce(sum(s.qty), 0) <= i.reorder_level ORDER BY i.name""");
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        Map<String, Object> p = new LinkedHashMap<>(db.one("Purchase order", """
                SELECT p.*, s.name AS supplier_name FROM purchase_orders p LEFT JOIN suppliers s ON s.id = p.supplier_id
                WHERE p.id = :id""", "id", id));
        p.put("lines", db.list("""
                SELECT pl.*, i.name AS item_name, i.unit FROM purchase_lines pl JOIN items i ON i.id = pl.item_id
                WHERE pl.po_id = :id""", "id", id));
        return p;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        Body b = new Body(raw);
        UUID loc = b.has("locationId") ? UUID.fromString(b.str("locationId")) : stock.defaultLocation();
        UUID id = db.insert("""
                INSERT INTO purchase_orders (tenant_id, supplier_id, location_id, status, note)
                VALUES (:t, :supplierId, :loc, 'ORDERED', :note) RETURNING id""",
                "supplierId", b.has("supplierId") ? UUID.fromString(b.str("supplierId")) : null, "loc", loc, "note", b.str("note", null));
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> raw2 : b.list("lines")) {
            Body l = new Body(raw2);
            BigDecimal qty = l.num("qty");
            UUID itemId = UUID.fromString(l.str("itemId"));
            // Ordered in packs (cartons): qty is packs and the cost is a pack's; receiving adds packs × pieces.
            boolean inPacks = l.bool("inPacks", false);
            Map<String, Object> it = db.one("Item", "SELECT name, kind, cost, buy_size, buy_price FROM items WHERE id = :itemId", "itemId", itemId);
            if (inPacks && ("INGREDIENT".equals(it.get("kind")) || it.get("buySize") == null)) {
                inPacks = "INGREDIENT".equals(it.get("kind")) ? false : failNoPack((String) it.get("name"));
            }
            BigDecimal cost = l.has("unitCost") ? l.num("unitCost")
                    : inPacks ? (it.get("buyPrice") != null ? (BigDecimal) it.get("buyPrice") : ((BigDecimal) it.get("cost")).multiply((BigDecimal) it.get("buySize")))
                    : (BigDecimal) it.get("cost");
            db.exec("""
                    INSERT INTO purchase_lines (tenant_id, po_id, item_id, qty, unit_cost, batch_no, expiry_date, in_packs)
                    VALUES (:t, :po, :itemId, :qty, :cost, :batch, :exp, :packs)""",
                    "po", id, "itemId", itemId, "qty", qty, "cost", cost, "batch", l.str("batchNo", null), "exp", l.date("expiryDate"), "packs", inPacks);
            total = total.add(qty.multiply(cost));
        }
        db.exec("UPDATE purchase_orders SET total = :total WHERE id = :id", "id", id, "total", total);
        return get(id);
    }

    private static boolean failNoPack(String name) {
        throw ApiException.bad(name + " has no pack size. Set Bought in packs on the product first");
    }

    /** Goods arrived: stock goes up, batches are recorded, item cost updates, and we owe the supplier. */
    @PostMapping("/{id}/receive")
    public Map<String, Object> receive(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        Map<String, Object> p = db.one("Purchase order", "SELECT * FROM purchase_orders WHERE id = :id FOR UPDATE", "id", id);
        if ("RECEIVED".equals(p.get("status"))) {
            throw ApiException.conflict("Already received");
        }
        UUID loc = UUID.fromString(p.get("locationId").toString());
        List<Map<String, Object>> lines = db.list("SELECT * FROM purchase_lines WHERE po_id = :id", "id", id);
        // What actually arrived, by line. A line not named arrived in full.
        Map<String, BigDecimal> arrived = new LinkedHashMap<>();
        if (raw != null && raw.get("lines") != null) {
            for (Map<String, Object> r : new Body(raw).list("lines")) {
                Body rb = new Body(r);
                String lineId = rb.str("id");
                Map<String, Object> match = null;
                for (Map<String, Object> l : lines) {
                    if (l.get("id").toString().equalsIgnoreCase(lineId)) {
                        match = l;
                    }
                }
                if (match == null) {
                    throw ApiException.bad("One of the lines you marked is not on this order");
                }
                BigDecimal got = rb.num("received");
                String what = db.value("SELECT name FROM items WHERE id = :i", String.class, "i", match.get("itemId"));
                if (got.signum() < 0) {
                    throw ApiException.bad("The amount received for " + what + " cannot be less than nothing");
                }
                if (got.compareTo((BigDecimal) match.get("qty")) > 0) {
                    throw ApiException.bad("You ordered " + ((BigDecimal) match.get("qty")).stripTrailingZeros().toPlainString() + " of " + what
                            + ", so you cannot receive more than that. Change the order first if more came");
                }
                arrived.put(match.get("id").toString(), got);
            }
        }
        boolean nothingArrived = !lines.isEmpty();
        for (Map<String, Object> l : lines) {
            if (arrived.getOrDefault(l.get("id").toString(), (BigDecimal) l.get("qty")).signum() != 0) {
                nothingArrived = false;
            }
        }
        BigDecimal owed = BigDecimal.ZERO;
        BigDecimal shortValue = BigDecimal.ZERO;
        List<String> shortNames = new java.util.ArrayList<>();
        for (Map<String, Object> l : lines) {
            UUID itemId = UUID.fromString(l.get("itemId").toString());
            BigDecimal ordered = (BigDecimal) l.get("qty");
            BigDecimal qty = arrived.getOrDefault(l.get("id").toString(), ordered);
            BigDecimal unitCost = (BigDecimal) l.get("unitCost");
            db.exec("UPDATE purchase_lines SET received_qty = :got WHERE id = :lid", "got", qty, "lid", l.get("id"));
            owed = owed.add(qty.multiply(unitCost));
            BigDecimal missing = ordered.subtract(qty);
            if (missing.signum() > 0) {
                shortValue = shortValue.add(missing.multiply(unitCost));
                shortNames.add(db.value("SELECT name FROM items WHERE id = :i", String.class, "i", itemId) + " by " + missing.stripTrailingZeros().toPlainString());
            }
            if (qty.signum() == 0) {
                continue;
            }
            // A line in packs: stock is kept in pieces, the pack's price is its buying price.
            BigDecimal packSize = Boolean.TRUE.equals(l.get("inPacks"))
                    ? db.value("SELECT buy_size FROM items WHERE id = :itemId AND kind = 'PRODUCT'", BigDecimal.class, "itemId", itemId) : null;
            if (packSize != null) {
                if (l.get("batchNo") != null || l.get("expiryDate") != null) {
                    db.exec("""
                            INSERT INTO stock_batches (tenant_id, item_id, location_id, batch_no, expiry_date, qty)
                            VALUES (:t, :itemId, :loc, :batch, cast(:exp as date), :qty)""",
                            "itemId", itemId, "loc", loc, "batch", l.get("batchNo") != null ? l.get("batchNo") : "Received " + java.time.LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")),
                            "exp", l.get("expiryDate"), "qty", qty.multiply(packSize));
                }
                stock.move(itemId, loc, qty.multiply(packSize), "RECEIVE", "PO " + id.toString().substring(0, 8), null);
                db.exec("UPDATE items SET buy_price = :cost, cost = round(:cost / buy_size, 2) WHERE id = :itemId", "itemId", itemId, "cost", l.get("unitCost"));
                continue;
            }
            // Record the batch first so the stock move finds batches and counts already in step.
            if (l.get("batchNo") != null || l.get("expiryDate") != null) {
                db.exec("""
                        INSERT INTO stock_batches (tenant_id, item_id, location_id, batch_no, expiry_date, qty)
                        VALUES (:t, :itemId, :loc, :batch, cast(:exp as date), :qty)""",
                        "itemId", itemId, "loc", loc, "batch", l.get("batchNo") != null ? l.get("batchNo") : "Received " + java.time.LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")),
                        "exp", l.get("expiryDate"), "qty", qty);
            }
            // An ingredient bought by the bag: the line is in bags; stock is kept in the cooking unit (g, ml, pc).
            BigDecimal size = db.value("SELECT buy_size FROM items WHERE id = :itemId AND kind = 'INGREDIENT'", BigDecimal.class, "itemId", itemId);
            if (size != null) {
                stock.move(itemId, loc, qty.multiply(size), "RECEIVE", "PO " + id.toString().substring(0, 8), null);
                db.exec("UPDATE items SET buy_price = :cost, cost = round(:cost / buy_size, 2) WHERE id = :itemId",
                        "itemId", itemId, "cost", l.get("unitCost"));
                continue;
            }
            stock.move(itemId, loc, qty, "RECEIVE", "PO " + id.toString().substring(0, 8), null);
            db.exec("UPDATE items SET cost = :cost WHERE id = :itemId", "itemId", itemId, "cost", l.get("unitCost"));
        }
        if (!nothingArrived) {
            db.exec("UPDATE purchase_orders SET status = 'RECEIVED', received_at = now() WHERE id = :id", "id", id);
        }
        if (p.get("supplierId") != null && !nothingArrived) {
            db.exec("UPDATE suppliers SET balance = balance + :total WHERE id = :s", "s", p.get("supplierId"), "total", owed);
        }
        if (!shortNames.isEmpty()) {
            activity.record("SHORT_DELIVERY", ActivityLog.CONCERN, "Delivery came short: " + String.join(", ", shortNames),
                    null, null, shortValue, null, Map.of("purchaseOrderId", id.toString()));
        }
        if (nothingArrived) {
            Map<String, Object> out = new LinkedHashMap<>(get(id));
            out.put("message", "Nothing was recorded as arrived, so the order is still open. When the goods come, receive them then.");
            return out;
        }
        return get(id);
    }
}
