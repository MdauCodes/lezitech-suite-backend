package com.lezitech.suite.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class StockService {

    /** Batch row that holds stock nobody recorded a batch number for. */
    static final String NO_BATCH = "NO-BATCH";

    private final Db db;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SectorCatalog sectors;

    public StockService(Db db, JdbcTemplate jdbc, ObjectMapper json, SectorCatalog sectors) {
        this.db = db;
        this.jdbc = jdbc;
        this.json = json;
        this.sectors = sectors;
    }

    /**
     * Apply a signed quantity change and journal it. Items that don't track stock are ignored.
     * Going below zero is refused unless the business allows it (restaurants usually do; shops
     * and pharmacies don't). The stock row is locked so two tills can't both sell the last one.
     */
    public void move(UUID itemId, UUID locationId, BigDecimal qty, String reason, String ref, String note) {
        move(itemId, locationId, qty, reason, ref, note, false);
    }

    /**
     * As above; {@code belowZeroOk} lets a kitchen ingredient run below zero (a dish sold, a batch made: the kitchen may
     * hold more than the records say). Moving an ingredient or a batch updates which dishes show as finished.
     */
    public void move(UUID itemId, UUID locationId, BigDecimal qty, String reason, String ref, String note, boolean belowZeroOk) {
        Map<String, Object> item = db.find("SELECT name, track_stock, kind FROM items WHERE id = :id", "id", itemId).orElse(null);
        if (item == null || !Boolean.TRUE.equals(item.get("trackStock")) || qty.signum() == 0) {
            return;
        }
        db.exec("""
                INSERT INTO stock_levels (tenant_id, item_id, location_id, qty) VALUES (:t, :item, :loc, 0)
                ON CONFLICT (item_id, location_id) DO NOTHING""", "item", itemId, "loc", locationId);
        BigDecimal current = db.value("SELECT qty FROM stock_levels WHERE item_id = :item AND location_id = :loc FOR UPDATE",
                BigDecimal.class, "item", itemId, "loc", locationId);
        BigDecimal after = current.add(qty);
        if (qty.signum() < 0 && after.signum() < 0 && !belowZeroOk && !allowNegative()) {
            String where = db.value("SELECT name FROM locations WHERE id = :loc", String.class, "loc", locationId);
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "OUT_OF_STOCK",
                    "Not enough " + item.get("name") + ": only " + current.stripTrailingZeros().toPlainString() + " left in " + where
                            + ". Fix the count in Stock if the shelf has more.");
        }
        db.exec("UPDATE stock_levels SET qty = :after WHERE item_id = :item AND location_id = :loc",
                "item", itemId, "loc", locationId, "after", after);
        db.exec("""
                INSERT INTO stock_moves (tenant_id, item_id, location_id, qty, reason, ref, note, user_id)
                VALUES (:t, :item, :loc, :qty, :reason, :ref, :note, :u)""",
                "item", itemId, "loc", locationId, "qty", qty, "reason", reason, "ref", ref, "note", note);
        reconcileBatches(itemId, locationId, after);
        if ("INGREDIENT".equals(item.get("kind")) || "BATCH".equals(item.get("kind"))) {
            syncFinishedDishes(itemId);
        }
    }

    /**
     * R2: a dish shows as finished while something its recipe always uses has run out (zero or below), and comes back
     * when that is restocked. Only ingredients the restaurant has actually counted or received count, so a new
     * restaurant that hasn't entered its stock yet doesn't see every dish go finished. {@code ingredient} limits the
     * check to dishes using it; null checks every dish.
     */
    public void syncFinishedDishes(UUID ingredient) {
        String uses = ingredient == null ? "true"
                : "EXISTS (SELECT 1 FROM recipe_lines r WHERE r.item_id = d.id AND r.option_group IS NULL AND r.ingredient_id = cast(:ing as uuid))";
        String short_ = """
                EXISTS (SELECT 1 FROM recipe_lines r WHERE r.item_id = d.id AND r.option_group IS NULL
                          AND EXISTS (SELECT 1 FROM stock_moves m WHERE m.item_id = r.ingredient_id
                                      AND (m.reason IN ('RECEIVE', 'ADJUST', 'OPENING', 'TRANSFER') OR (m.reason = 'BATCH' AND m.qty > 0)))
                          AND coalesce((SELECT sum(s.qty) FROM stock_levels s WHERE s.item_id = r.ingredient_id), 0) <= 0)""";
        db.exec("UPDATE items d SET unavailable_until = 'infinity', finished_by_stock = true"
                + " WHERE d.kind NOT IN ('INGREDIENT', 'BATCH') AND (d.unavailable_until IS NULL OR d.unavailable_until <= now())"
                + " AND " + uses + " AND " + short_, "ing", ingredient);
        db.exec("UPDATE items d SET unavailable_until = NULL, finished_by_stock = false"
                + " WHERE d.finished_by_stock AND " + uses + " AND NOT " + short_, "ing", ingredient);
    }

    /**
     * Keep batch quantities equal to the stock count for items that use batches: shortfalls come
     * out of the batch that expires first, surpluses go to a "no batch" row.
     */
    private void reconcileBatches(UUID itemId, UUID locationId, BigDecimal level) {
        if (!Boolean.TRUE.equals(db.value("SELECT exists(SELECT 1 FROM stock_batches WHERE item_id = :item)", Boolean.class, "item", itemId))) {
            return;
        }
        BigDecimal target = level.max(BigDecimal.ZERO);
        BigDecimal inBatches = db.value("SELECT coalesce(sum(qty), 0) FROM stock_batches WHERE item_id = :item AND location_id = :loc",
                BigDecimal.class, "item", itemId, "loc", locationId);
        BigDecimal diff = target.subtract(inBatches);
        if (diff.signum() > 0) {
            int updated = db.exec("UPDATE stock_batches SET qty = qty + :d WHERE item_id = :item AND location_id = :loc AND batch_no = :nb",
                    "item", itemId, "loc", locationId, "d", diff, "nb", NO_BATCH);
            if (updated == 0) {
                db.exec("""
                        INSERT INTO stock_batches (tenant_id, item_id, location_id, batch_no, qty) VALUES (:t, :item, :loc, :nb, :d)""",
                        "item", itemId, "loc", locationId, "nb", NO_BATCH, "d", diff);
            }
        } else if (diff.signum() < 0) {
            BigDecimal left = diff.negate();
            for (var b : db.list("""
                    SELECT id, qty FROM stock_batches WHERE item_id = :item AND location_id = :loc AND qty > 0
                    ORDER BY expiry_date NULLS LAST, batch_no = :nb DESC""", "item", itemId, "loc", locationId, "nb", NO_BATCH)) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal take = ((BigDecimal) b.get("qty")).min(left);
                db.exec("UPDATE stock_batches SET qty = qty - :take WHERE id = :id", "id", b.get("id"), "take", take);
                left = left.subtract(take);
            }
        }
    }

    /** Workspace switch; when the owner never set it, the sector's default applies. */
    public boolean allowNegative() {
        Map<String, Object> t = jdbc.queryForMap("SELECT sector, settings::text AS s FROM tenants WHERE id = ?", TenantContext.tenantId());
        try {
            JsonNode v = json.readTree((String) t.get("s")).path("features").path("allowNegativeStock");
            if (v.isBoolean()) {
                return v.asBoolean();
            }
        } catch (Exception ignored) {
            // fall back to the sector default
        }
        return sectors.get((String) t.get("sector")).path("features").path("allowNegativeStock").asBoolean(false);
    }

    public UUID defaultLocation() {
        return db.value("SELECT id FROM locations WHERE is_default LIMIT 1", UUID.class);
    }
}
