package com.lezitech.suite.restaurant;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The kitchen's side of an order: how long it will take, dishes that are finished, and offering something else
 * instead. The diner sees all of it on their tracking page; the waiter gets a notice when they need to step in.
 */
@Service
@Transactional
public class KitchenService {

    /** Past cooking times count once an item has been cooked this many times in the last 30 days. */
    static final int MIN_SAMPLES = 3;

    private final Db db;
    private final OrderService orders;
    private final ActivityLog activity;

    public KitchenService(Db db, OrderService orders, ActivityLog activity) {
        this.db = db;
        this.orders = orders;
        this.activity = activity;
    }

    /**
     * Suggested minutes for an order: the slowest of its dishes, each from its average cooking time over the last 30
     * days (sent to ready), else the minutes set on the item. Null when nothing is known yet.
     */
    public Integer suggestMinutes(UUID orderId) {
        return db.value("""
                SELECT max(coalesce(
                         (SELECT CASE WHEN count(*) >= :min THEN round(avg(extract(epoch FROM (p.ready_at - p.sent_at)) / 60)) END
                          FROM order_lines p
                          WHERE p.item_id = l.item_id AND p.ready_at IS NOT NULL AND p.sent_at IS NOT NULL
                            AND p.ready_at > p.sent_at AND p.sent_at > now() - interval '30 days'),
                         i.prep_minutes))::int
                FROM order_lines l JOIN items i ON i.id = l.item_id
                WHERE l.order_id = :id AND l.kitchen_status IN ('NEW', 'PREPARING')""", Integer.class, "id", orderId, "min", MIN_SAMPLES);
    }

    /** When an order first reaches the kitchen, give the diner an estimate if we have one. */
    public void autoEstimate(UUID orderId) {
        Integer m = suggestMinutes(orderId);
        if (m != null && m > 0) {
            db.exec("UPDATE orders SET estimated_ready_at = now() + make_interval(mins => :m) WHERE id = :id AND estimated_ready_at IS NULL",
                    "m", m, "id", orderId);
        }
    }

    /** The kitchen sets or changes the estimate. */
    public Instant setEstimate(UUID orderId, int minutes) {
        if (minutes < 1 || minutes > 240) {
            throw ApiException.bad("Choose between 1 and 240 minutes");
        }
        orders.requireOpen(orderId);
        db.exec("UPDATE orders SET estimated_ready_at = now() + make_interval(mins => :m) WHERE id = :id", "m", minutes, "id", orderId);
        return Instant.now().plusSeconds(minutes * 60L);
    }

    /** A dish moves on the kitchen screen; remember when it became ready, for future estimates. */
    public void moved(UUID lineId, String status) {
        if ("READY".equals(status) || "SERVED".equals(status)) {
            db.exec("UPDATE order_lines SET ready_at = coalesce(ready_at, now()) WHERE id = :id", "id", lineId);
        }
    }

    /**
     * Mark an item finished: until restocked (no time), until a time, or back on now. The menu shows it as finished
     * and table orders for it are refused.
     */
    public void setAvailability(UUID itemId, String until, Instant at) {
        String name = db.find("SELECT name FROM items WHERE id = :id", "id", itemId).map(i -> (String) i.get("name"))
                .orElseThrow(() -> ApiException.notFound("Item"));
        switch (until == null ? "" : until.toUpperCase()) {
            case "RESTOCK" -> db.exec("UPDATE items SET unavailable_until = 'infinity' WHERE id = :id", "id", itemId);
            case "TIME" -> {
                if (at == null || at.isBefore(Instant.now()) || at.isAfter(Instant.now().plusSeconds(7 * 86_400))) {
                    throw ApiException.bad("Choose a time in the next 7 days");
                }
                db.exec("UPDATE items SET unavailable_until = :at WHERE id = :id", "at", java.sql.Timestamp.from(at), "id", itemId);
            }
            case "NOW" -> db.exec("UPDATE items SET unavailable_until = NULL, finished_by_stock = false WHERE id = :id", "id", itemId);
            default -> throw ApiException.bad("Say until when " + name + " is finished");
        }
    }

    public List<Map<String, Object>> finishedItems() {
        return db.list("""
                SELECT id, name, CASE WHEN unavailable_until = 'infinity' THEN NULL ELSE unavailable_until END AS until
                FROM items WHERE unavailable_until IS NOT NULL AND unavailable_until > now() ORDER BY name""");
    }

    /**
     * The kitchen can't make a dish. With a substitute, the diner is asked on their phone (the line waits, ON_HOLD).
     * Without one, the dish comes off the bill and the diner and waiter are told. Optionally mark the item finished.
     */
    public void reject(UUID lineId, String reason, UUID substituteId, boolean markFinished) {
        Map<String, Object> l = db.one("Line", """
                SELECT l.id, l.order_id, l.item_id, l.name, l.qty, l.line_total, l.kitchen_status, o.status, o.self_order
                FROM order_lines l JOIN orders o ON o.id = l.order_id WHERE l.id = :id""", "id", lineId);
        if (!"OPEN".equals(l.get("status")) || !List.of("NEW", "PREPARING").contains(l.get("kitchenStatus"))) {
            throw ApiException.conflict("Only dishes not yet ready can be swapped or taken off");
        }
        UUID orderId = UUID.fromString(l.get("orderId").toString());
        String name = (String) l.get("name");
        String why = reason == null || reason.isBlank() ? "finished" : reason.trim();
        if (why.length() > 150) {
            why = why.substring(0, 150);
        }
        if (markFinished && l.get("itemId") != null) {
            setAvailability(UUID.fromString(l.get("itemId").toString()), "RESTOCK", null);
        }
        if (substituteId != null) {
            Map<String, Object> sub = db.find("""
                    SELECT name, price FROM items WHERE id = :id AND active AND kind <> 'SERVICE'
                      AND (unavailable_until IS NULL OR unavailable_until <= now())""", "id", substituteId)
                    .orElseThrow(() -> ApiException.bad("That dish can't be offered instead"));
            db.exec("""
                    UPDATE order_lines SET kitchen_status = 'ON_HOLD', substitute_item_id = :sub, substitute_status = 'OFFERED',
                           rejected_reason = :why WHERE id = :id""", "sub", substituteId, "why", why, "id", lineId);
            notice(orderId, "SUBSTITUTE_OFFERED", "Sorry, " + name + " is " + why + ". Would " + sub.get("name") + " do instead?", false);
            log(orderId, name + " " + why + "; offered " + sub.get("name") + " instead", BigDecimal.ZERO);
        } else {
            removeLine(lineId, orderId);
            notice(orderId, "ITEM_REMOVED", "Sorry, " + name + " is " + why + ". It has been taken off your bill.", true);
            log(orderId, name + " " + why + "; taken off the bill", (BigDecimal) l.get("lineTotal"));
        }
        orders.markReadyIfDone(orderId);
    }

    /** The diner answers the substitute question on their phone. */
    public void answerSubstitute(UUID lineId, boolean accept) {
        Map<String, Object> l = db.one("Dish", """
                SELECT l.id, l.order_id, l.name, l.qty, l.note, l.course, l.line_total, l.substitute_item_id, l.substitute_status,
                       s.name AS sub_name
                FROM order_lines l LEFT JOIN items s ON s.id = l.substitute_item_id WHERE l.id = :id""", "id", lineId);
        if (!"OFFERED".equals(l.get("substituteStatus"))) {
            throw ApiException.conflict("This has already been answered");
        }
        UUID orderId = UUID.fromString(l.get("orderId").toString());
        removeLine(lineId, orderId);
        if (accept) {
            Map<String, Object> line = new java.util.LinkedHashMap<>();
            line.put("itemId", l.get("substituteItemId").toString());
            line.put("qty", l.get("qty"));
            if (l.get("note") != null) {
                line.put("note", l.get("note"));
            }
            line.put("course", l.get("course"));
            orders.addLines(orderId, List.of(line));
            db.exec("""
                    UPDATE order_lines SET kitchen_status = 'NEW', sent_at = now()
                    WHERE order_id = :id AND kitchen_status = 'PENDING' AND item_id = :item""",
                    "id", orderId, "item", UUID.fromString(l.get("substituteItemId").toString()));
            notice(orderId, "SUBSTITUTE_ACCEPTED", l.get("subName") + " is on its way instead of " + l.get("name") + ".", false);
        } else {
            notice(orderId, "SUBSTITUTE_DECLINED", "No " + l.get("name") + " today and the swap wasn't wanted. It has been taken off the bill; please check with the table.", true);
        }
        orders.markReadyIfDone(orderId);
    }

    public void noticeDone(UUID id) {
        if (db.exec("UPDATE order_notices SET done_at = now(), done_by = :u WHERE id = :id AND done_at IS NULL", "id", id) == 0) {
            throw ApiException.notFound("Notice");
        }
    }

    private void removeLine(UUID lineId, UUID orderId) {
        db.exec("DELETE FROM order_lines WHERE id = :id", "id", lineId);
        orders.recalc(orderId);
    }

    private void notice(UUID orderId, String kind, String message, boolean forWaiter) {
        db.exec("INSERT INTO order_notices (tenant_id, order_id, kind, message, for_waiter) VALUES (:t, :o, :k, :m, :w)",
                "o", orderId, "k", kind, "m", message.length() > 300 ? message.substring(0, 300) : message, "w", forWaiter);
    }

    private void log(UUID orderId, String summary, BigDecimal amount) {
        activity.record("KITCHEN_CHANGE", ActivityLog.INFO, summary, orderId, null, amount, null, Map.of());
    }
}
