package com.lezitech.suite.stock;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/stock")
@Transactional
public class StockController {

    private final Db db;
    private final StockService stock;

    public StockController(Db db, StockService stock) {
        this.db = db;
        this.stock = stock;
    }

    /** One row per stocked item with quantity per location and a low-stock flag. */
    @GetMapping
    public List<Map<String, Object>> levels() {
        return db.list("""
                SELECT i.id, i.name, i.unit, i.barcode, i.cost, i.price, i.reorder_level, c.name AS category_name,
                       coalesce(sum(s.qty), 0) AS on_hand,
                       coalesce(sum(s.qty), 0) * i.cost AS stock_value,
                       coalesce(sum(s.qty), 0) <= i.reorder_level AS low,
                       coalesce(jsonb_object_agg(s.location_id, s.qty) FILTER (WHERE s.location_id IS NOT NULL), '{}') AS by_location
                FROM items i
                LEFT JOIN categories c ON c.id = i.category_id
                LEFT JOIN stock_levels s ON s.item_id = i.id
                WHERE i.active AND i.track_stock
                GROUP BY i.id, c.name, c.sort
                ORDER BY (coalesce(sum(s.qty), 0) <= i.reorder_level) DESC, c.sort NULLS LAST, i.name""");
    }

    @GetMapping("/moves")
    public List<Map<String, Object>> moves(@RequestParam(required = false) UUID itemId) {
        return db.list("""
                SELECT m.*, i.name AS item_name, l.name AS location_name, u.name AS user_name
                FROM stock_moves m JOIN items i ON i.id = m.item_id JOIN locations l ON l.id = m.location_id
                LEFT JOIN users u ON u.id = m.user_id
                WHERE (cast(:itemId as uuid) IS NULL OR m.item_id = cast(:itemId as uuid))
                ORDER BY m.created_at DESC LIMIT 300""", "itemId", itemId);
    }

    /** Stock count: set what was physically counted; the difference is journaled. */
    @PostMapping("/adjust")
    public Map<String, Object> adjust(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        UUID item = UUID.fromString(b.str("itemId"));
        UUID loc = b.has("locationId") ? UUID.fromString(b.str("locationId")) : stock.defaultLocation();
        BigDecimal counted = b.num("counted");
        if (counted.signum() < 0) {
            throw com.lezitech.suite.common.ApiException.bad("A count can't be below zero");
        }
        BigDecimal current = db.value("SELECT qty FROM stock_levels WHERE item_id = :item AND location_id = :loc",
                BigDecimal.class, "item", item, "loc", loc);
        BigDecimal delta = counted.subtract(current == null ? BigDecimal.ZERO : current);
        stock.move(item, loc, delta, "ADJUST", "Stock count", b.str("note", null));
        return Map.of("delta", delta);
    }

    /** Store feeds shop: move stock between locations in one step. */
    @PostMapping("/transfer")
    public Map<String, Object> transfer(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        UUID from = UUID.fromString(b.str("fromId"));
        UUID to = UUID.fromString(b.str("toId"));
        if (from.equals(to)) {
            throw ApiException.bad("Pick two different locations");
        }
        String ref = "Transfer " + db.value("SELECT name FROM locations WHERE id = :id", String.class, "id", from)
                + " → " + db.value("SELECT name FROM locations WHERE id = :id", String.class, "id", to);
        int lines = 0;
        for (Map<String, Object> l : b.list("lines")) {
            Body lb = new Body(l);
            BigDecimal qty = lb.num("qty");
            if (qty.signum() <= 0) {
                continue;
            }
            UUID item = UUID.fromString(lb.str("itemId"));
            stock.move(item, from, qty.negate(), "TRANSFER", ref, null);
            stock.move(item, to, qty, "TRANSFER", ref, null);
            lines++;
        }
        return Map.of("lines", lines);
    }

    /** Every batch of one item, including stock with no batch number. */
    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam UUID itemId) {
        return db.list("""
                SELECT b.*, l.name AS location_name FROM stock_batches b JOIN locations l ON l.id = b.location_id
                WHERE b.item_id = :itemId ORDER BY b.expiry_date NULLS LAST""", "itemId", itemId);
    }

    /** Batches expiring within the given number of days (pharmacy, food). */
    @GetMapping("/expiring")
    public List<Map<String, Object>> expiring(@RequestParam(defaultValue = "90") int days) {
        return db.list("""
                SELECT b.*, i.name AS item_name, l.name AS location_name, (b.expiry_date - current_date) AS days_left
                FROM stock_batches b JOIN items i ON i.id = b.item_id JOIN locations l ON l.id = b.location_id
                WHERE b.qty > 0 AND b.expiry_date IS NOT NULL AND b.expiry_date <= current_date + :days
                ORDER BY b.expiry_date""", "days", days);
    }
}
