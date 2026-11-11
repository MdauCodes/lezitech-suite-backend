package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orders placed by diners from the table QR (or the menu link, for takeaway). Runs as the restaurant (callers wrap it
 * in TenantContext.runAs) through the same OrderService as the till, so prices, add-ons and the table's open bill work
 * exactly as they do for staff. The diner can only choose items, quantities, add-ons and a note: nothing that sets a
 * price.
 */
@Service
@Transactional
public class TableOrderService {

    static final int MAX_LINES = 40;
    static final int MAX_QTY = 50;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;
    private final OrderService orders;
    private final KitchenService kitchen;

    public TableOrderService(Db db, OrderService orders, KitchenService kitchen) {
        this.db = db;
        this.orders = orders;
        this.kitchen = kitchen;
    }

    public record Placed(UUID orderId, int number, UUID token, BigDecimal total, String pickupCode, boolean heldForPayment,
                         String tableLabel, boolean addedToOpenBill) {
    }

    public Placed place(Map<String, Object> raw, String payWhen) {
        Body b = new Body(raw);
        UUID tableId = b.has("tableId") ? parse(b.str("tableId"), "That table") : null;
        String tableLabel = null;
        if (tableId != null) {
            tableLabel = db.find("SELECT label FROM dining_tables WHERE id = :id", "id", tableId)
                    .map(t -> (String) t.get("label")).orElseThrow(() -> ApiException.notFound("That table"));
        }
        List<Map<String, Object>> lines = cleanLines(b.list("lines"));
        String label = clip(b.str("customerLabel", null), 40);
        String phone = phone(b.str("phone", null));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", tableId == null ? "TAKEAWAY" : "DINE_IN");
        if (tableId != null) {
            body.put("tableId", tableId.toString());
        }
        if (label != null) {
            body.put("customerLabel", label);
        }
        body.put("lines", lines);
        UUID before = tableId == null ? null
                : db.value("SELECT id FROM orders WHERE table_id = :id AND status = 'OPEN' LIMIT 1", UUID.class, "id", tableId);
        UUID orderId = orders.create(new Body(body));
        boolean added = before != null && before.equals(orderId);

        boolean first = "FIRST".equals(payWhen);
        String code = tableId == null ? String.format("%04d", RANDOM.nextInt(10_000)) : null;
        UUID waiter = db.value("SELECT assigned_to FROM orders WHERE id = :id", UUID.class, "id", orderId);
        if (waiter == null && tableId != null) {
            waiter = leastBusyWaiter();
        }
        db.exec("""
                UPDATE orders SET self_order = true, customer_phone = coalesce(:phone, customer_phone),
                       customer_label = coalesce(customer_label, :label), assigned_to = :waiter,
                       pickup_code = coalesce(pickup_code, :code), held_for_payment = :held
                WHERE id = :id""",
                "phone", phone, "label", label, "waiter", waiter, "code", code, "held", first, "id", orderId);
        if (!first) {
            sendToKitchen(orderId);
        }
        Map<String, Object> o = db.one("Order", "SELECT number, public_token, total, pickup_code FROM orders WHERE id = :id", "id", orderId);
        return new Placed(orderId, (Integer) o.get("number"), UUID.fromString(o.get("publicToken").toString()),
                (BigDecimal) o.get("total"), (String) o.get("pickupCode"), first, tableLabel, added);
    }

    /** Fire the order's waiting lines to the kitchen and bar screens (as the till's Send does). */
    public int sendToKitchen(UUID orderId) {
        int n = db.exec("""
                UPDATE order_lines SET kitchen_status = 'NEW', sent_at = now()
                WHERE order_id = :id AND kitchen_status = 'PENDING' AND item_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM items i WHERE i.id = order_lines.item_id AND (i.kind = 'MENU' OR i.station IS NOT NULL))""",
                "id", orderId);
        orders.markReadyIfDone(orderId);
        if (n > 0) {
            kitchen.autoEstimate(orderId);
        }
        return n;
    }

    /**
     * The waiter with the lightest load: tables they have open plus plates still to serve. Waiters who signed in within
     * the last 16 hours come first; if none have, any active waiter; none at all leaves the order for the floor.
     */
    public UUID leastBusyWaiter() {
        String sql = """
                SELECT u.id FROM users u
                WHERE u.tenant_id = :t AND u.role = 'WAITER' AND u.active
                  AND (:recent = false OR u.last_login_at > now() - interval '16 hours')
                ORDER BY (SELECT count(DISTINCT o.table_id) FROM orders o WHERE o.assigned_to = u.id AND o.status = 'OPEN')
                       + (SELECT coalesce(sum(l.qty), 0) FROM orders o JOIN order_lines l ON l.order_id = o.id
                          WHERE o.assigned_to = u.id AND o.status = 'OPEN' AND l.kitchen_status <> 'SERVED'),
                         u.name LIMIT 1""";
        UUID on = db.value(sql, UUID.class, "recent", true);
        return on != null ? on : db.value(sql, UUID.class, "recent", false);
    }

    /** Only what a diner may choose; every item must be on the menu and available right now. */
    private List<Map<String, Object>> cleanLines(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.bad("Add something to your order first");
        }
        if (raw.size() > MAX_LINES) {
            throw ApiException.bad("That's a very big order. Please ask a waiter to help.");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            Body l = new Body(r);
            if (!l.has("itemId")) {
                throw ApiException.bad("Choose items from the menu");
            }
            UUID itemId = parse(l.str("itemId"), "That item");
            BigDecimal qty = l.num("qty", BigDecimal.ONE);
            if (qty.signum() <= 0 || qty.compareTo(BigDecimal.valueOf(MAX_QTY)) > 0 || qty.stripTrailingZeros().scale() > 0) {
                throw ApiException.bad("Choose between 1 and " + MAX_QTY + " of each item");
            }
            Map<String, Object> item = db.find("""
                    SELECT name, active, kind, (unavailable_until IS NOT NULL AND unavailable_until > now()) AS unavailable,
                           (track_stock AND coalesce((SELECT sum(qty) FROM stock_levels s WHERE s.item_id = items.id), 0) < :qty) AS short
                    FROM items WHERE id = :id""", "id", itemId, "qty", qty).orElseThrow(() -> ApiException.notFound("That item"));
            if (!Boolean.TRUE.equals(item.get("active")) || "SERVICE".equals(item.get("kind"))) {
                throw ApiException.bad(item.get("name") + " isn't on the menu any more");
            }
            if (Boolean.TRUE.equals(item.get("unavailable"))) {
                throw ApiException.bad("Sorry, " + item.get("name") + " is finished for now");
            }
            if (Boolean.TRUE.equals(item.get("short"))) {
                throw ApiException.bad("Sorry, there isn't enough " + item.get("name") + " left");
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("itemId", itemId.toString());
            line.put("qty", qty);
            line.put("options", l.list("options") == null ? List.of() : l.list("options"));
            String note = clip(l.str("note", null), 200);
            if (note != null) {
                line.put("note", note);
            }
            out.add(line);
        }
        return out;
    }

    static String phone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String d = raw.replaceAll("\\D", "");
        if (d.startsWith("0") && d.length() == 10) {
            d = "254" + d.substring(1);
        } else if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) {
            d = "254" + d;
        }
        if (!d.matches("254[71]\\d{8}")) {
            throw ApiException.bad("Enter your M-Pesa number like 0712 345 678");
        }
        return d;
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static UUID parse(String s, String what) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw ApiException.notFound(what);
        }
    }
}
