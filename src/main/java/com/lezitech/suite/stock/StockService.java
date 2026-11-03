package com.lezitech.suite.stock;

import com.lezitech.suite.common.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional
public class StockService {

    private final Db db;

    public StockService(Db db) {
        this.db = db;
    }

    /** Apply a signed quantity change and journal it. Items that don't track stock are ignored. */
    public void move(UUID itemId, UUID locationId, BigDecimal qty, String reason, String ref, String note) {
        Boolean tracks = db.value("SELECT track_stock FROM items WHERE id = :id", Boolean.class, "id", itemId);
        if (!Boolean.TRUE.equals(tracks) || qty.signum() == 0) {
            return;
        }
        db.exec("""
                INSERT INTO stock_levels (tenant_id, item_id, location_id, qty) VALUES (:t, :item, :loc, :qty)
                ON CONFLICT (item_id, location_id) DO UPDATE SET qty = stock_levels.qty + excluded.qty""",
                "item", itemId, "loc", locationId, "qty", qty);
        db.exec("""
                INSERT INTO stock_moves (tenant_id, item_id, location_id, qty, reason, ref, note, user_id)
                VALUES (:t, :item, :loc, :qty, :reason, :ref, :note, :u)""",
                "item", itemId, "loc", locationId, "qty", qty, "reason", reason, "ref", ref, "note", note);
        if (qty.signum() < 0) {
            consumeBatches(itemId, locationId, qty.negate());
        }
    }

    /** First-expiry-first-out: sales draw down the batch that expires soonest. */
    private void consumeBatches(UUID itemId, UUID locationId, BigDecimal qty) {
        BigDecimal left = qty;
        for (var b : db.list("""
                SELECT id, qty FROM stock_batches WHERE item_id = :item AND location_id = :loc AND qty > 0
                ORDER BY expiry_date NULLS LAST""", "item", itemId, "loc", locationId)) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal have = (BigDecimal) b.get("qty");
            BigDecimal take = have.min(left);
            db.exec("UPDATE stock_batches SET qty = qty - :take WHERE id = :id", "id", b.get("id"), "take", take);
            left = left.subtract(take);
        }
    }

    public UUID defaultLocation() {
        return db.value("SELECT id FROM locations WHERE is_default LIMIT 1", UUID.class);
    }
}
