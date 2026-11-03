package com.lezitech.suite.purchasing;

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

    public PurchaseController(Db db, StockService stock) {
        this.db = db;
        this.stock = stock;
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
        TenantContext.requireRole("MANAGER");
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
            BigDecimal cost = l.has("unitCost") ? l.num("unitCost")
                    : db.value("SELECT cost FROM items WHERE id = :itemId", BigDecimal.class, "itemId", itemId);
            db.exec("""
                    INSERT INTO purchase_lines (tenant_id, po_id, item_id, qty, unit_cost, batch_no, expiry_date)
                    VALUES (:t, :po, :itemId, :qty, :cost, :batch, :exp)""",
                    "po", id, "itemId", itemId, "qty", qty, "cost", cost, "batch", l.str("batchNo", null), "exp", l.date("expiryDate"));
            total = total.add(qty.multiply(cost));
        }
        db.exec("UPDATE purchase_orders SET total = :total WHERE id = :id", "id", id, "total", total);
        return get(id);
    }

    /** Goods arrived: stock goes up, batches are recorded, item cost updates, and we owe the supplier. */
    @PostMapping("/{id}/receive")
    public Map<String, Object> receive(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        Map<String, Object> p = db.one("Purchase order", "SELECT * FROM purchase_orders WHERE id = :id", "id", id);
        if ("RECEIVED".equals(p.get("status"))) {
            throw ApiException.conflict("Already received");
        }
        UUID loc = UUID.fromString(p.get("locationId").toString());
        for (Map<String, Object> l : db.list("SELECT * FROM purchase_lines WHERE po_id = :id", "id", id)) {
            UUID itemId = UUID.fromString(l.get("itemId").toString());
            BigDecimal qty = (BigDecimal) l.get("qty");
            stock.move(itemId, loc, qty, "RECEIVE", "PO " + id.toString().substring(0, 8), null);
            db.exec("UPDATE items SET cost = :cost WHERE id = :itemId", "itemId", itemId, "cost", l.get("unitCost"));
            if (l.get("batchNo") != null) {
                db.exec("""
                        INSERT INTO stock_batches (tenant_id, item_id, location_id, batch_no, expiry_date, qty)
                        VALUES (:t, :itemId, :loc, :batch, cast(:exp as date), :qty)""",
                        "itemId", itemId, "loc", loc, "batch", l.get("batchNo"), "exp", l.get("expiryDate"), "qty", qty);
            }
        }
        db.exec("UPDATE purchase_orders SET status = 'RECEIVED', received_at = now() WHERE id = :id", "id", id);
        if (p.get("supplierId") != null) {
            db.exec("UPDATE suppliers SET balance = balance + :total WHERE id = :s", "s", p.get("supplierId"), "total", p.get("total"));
        }
        return get(id);
    }
}
