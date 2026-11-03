package com.lezitech.suite.sales;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.stock.StockService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class OrderService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final Db db;
    private final JdbcTemplate jdbc;
    private final StockService stock;

    public OrderService(Db db, JdbcTemplate jdbc, StockService stock) {
        this.db = db;
        this.jdbc = jdbc;
        this.stock = stock;
    }

    public UUID create(Body b) {
        String kind = b.str("kind", "SALE");
        String channel = b.str("channel", "COUNTER");
        UUID tableId = b.has("tableId") ? UUID.fromString(b.str("tableId")) : null;
        if (tableId != null) {
            UUID open = db.value("SELECT id FROM orders WHERE table_id = :tableId AND status = 'OPEN' LIMIT 1", UUID.class, "tableId", tableId);
            if (open != null && !b.bool("allowSecondBill", false)) {
                addLines(open, b.list("lines"));
                return open;
            }
        }
        // tenants has no RLS; the counter is per workspace and bumped atomically.
        Integer number = jdbc.queryForObject("UPDATE tenants SET order_seq = order_seq + 1 WHERE id = ? RETURNING order_seq",
                Integer.class, TenantContext.tenantId());
        UUID shift = db.value("SELECT id FROM shifts WHERE user_id = :u AND status = 'OPEN' LIMIT 1", UUID.class);
        UUID loc = b.has("locationId") ? UUID.fromString(b.str("locationId")) : stock.defaultLocation();
        UUID id = db.insert("""
                INSERT INTO orders (tenant_id, number, kind, channel, table_id, guests, customer_id, location_id, shift_id, user_id, note)
                VALUES (:t, :number, :kind, :channel, :tableId, :guests, :customerId, :loc, :shift, :u, :note) RETURNING id""",
                "number", number, "kind", kind, "channel", channel, "tableId", tableId, "guests", b.integer("guests", null),
                "customerId", b.has("customerId") ? UUID.fromString(b.str("customerId")) : null,
                "loc", loc, "shift", shift, "note", b.str("note", null));
        addLines(id, b.list("lines"));
        return id;
    }

    public void addLines(UUID orderId, List<Map<String, Object>> lines) {
        Map<String, Object> order = requireOpen(orderId);
        boolean wholesale = "WHOLESALE".equals(order.get("channel"))
                || (order.get("customerId") != null && "WHOLESALE".equals(db.value(
                "SELECT kind FROM customers WHERE id = :id", String.class, "id", order.get("customerId"))));
        for (Map<String, Object> raw : lines) {
            Body l = new Body(raw);
            BigDecimal qty = l.num("qty", BigDecimal.ONE);
            if (qty.signum() <= 0) {
                continue;
            }
            Map<String, Object> item = null;
            String name;
            BigDecimal price;
            BigDecimal tax;
            String station = null;
            if (l.has("itemId")) {
                item = db.one("Item", "SELECT * FROM items WHERE id = :id", "id", l.str("itemId"));
                name = (String) item.get("name");
                price = (BigDecimal) item.get("price");
                if (wholesale && item.get("wholesalePrice") != null) {
                    BigDecimal min = (BigDecimal) item.get("wholesaleMinQty");
                    if (min == null || qty.compareTo(min) >= 0) {
                        price = (BigDecimal) item.get("wholesalePrice");
                    }
                }
                tax = (BigDecimal) item.get("taxRate");
                station = (String) item.get("station");
            } else {
                name = l.str("name");
                price = l.num("unitPrice");
                tax = l.num("taxRate", BigDecimal.ZERO);
            }
            if (l.has("unitPrice") && item != null) {
                // Manual price override at the till (e.g. negotiated wholesale price).
                price = l.num("unitPrice");
            }
            BigDecimal optionsTotal = BigDecimal.ZERO;
            List<Map<String, Object>> options = l.list("options");
            for (Map<String, Object> o : options) {
                optionsTotal = optionsTotal.add(new Body(o).num("price", BigDecimal.ZERO));
            }
            BigDecimal unit = price.add(optionsTotal);
            BigDecimal discount = l.num("discount", BigDecimal.ZERO);
            BigDecimal lineTotal = unit.multiply(qty).subtract(discount).setScale(2, RoundingMode.HALF_UP);
            db.exec("""
                    INSERT INTO order_lines (tenant_id, order_id, item_id, name, qty, unit_price, tax_rate, discount, line_total,
                                             options, note, course, station, staff_id)
                    VALUES (:t, :order, :item, :name, :qty, :unit, :tax, :discount, :total, cast(:options as jsonb), :note,
                            :course, :station, :staff)""",
                    "order", orderId, "item", item == null ? null : UUID.fromString(item.get("id").toString()), "name", name,
                    "qty", qty, "unit", unit, "tax", tax, "discount", discount, "total", lineTotal,
                    "options", db.toJson(options), "note", l.str("note", null), "course", l.integer("course", 1),
                    "station", station, "staff", l.has("staffId") ? UUID.fromString(l.str("staffId")) : null);
        }
        recalc(orderId);
    }

    /** Prices are VAT-inclusive (the norm on Kenyan shelves); tax is the VAT portion. */
    public void recalc(UUID orderId) {
        db.exec("""
                UPDATE orders o SET
                  subtotal = x.subtotal,
                  tax = x.tax,
                  total = greatest(x.subtotal - o.discount, 0)
                FROM (SELECT coalesce(sum(line_total), 0) AS subtotal,
                             coalesce(sum(round(line_total * tax_rate / (100 + tax_rate), 2)), 0) AS tax
                      FROM order_lines WHERE order_id = :id) x
                WHERE o.id = :id""", "id", orderId);
    }

    public Map<String, Object> requireOpen(UUID orderId) {
        Map<String, Object> o = db.one("Order", "SELECT * FROM orders WHERE id = :id", "id", orderId);
        if (!"OPEN".equals(o.get("status"))) {
            throw ApiException.conflict("This " + o.get("kind").toString().toLowerCase() + " is already closed");
        }
        return o;
    }

    public Map<String, Object> pay(UUID orderId, Body b) {
        Map<String, Object> o = requireOpen(orderId);
        if ("QUOTE".equals(o.get("kind"))) {
            throw ApiException.bad("Convert the quotation to a sale before taking payment");
        }
        if (b.has("discount")) {
            BigDecimal discount = b.num("discount");
            if (discount.signum() < 0) {
                throw ApiException.bad("Discount can't be negative");
            }
            db.exec("UPDATE orders SET discount = :d WHERE id = :id", "id", orderId, "d", discount);
        }
        if (b.has("tip") && b.num("tip").signum() > 0) {
            addLines(orderId, List.of(Map.of("name", "Tip", "unitPrice", b.num("tip"), "qty", 1)));
        }
        recalc(orderId);
        o = db.one("Order", "SELECT * FROM orders WHERE id = :id", "id", orderId);
        BigDecimal total = (BigDecimal) o.get("total");
        BigDecimal alreadyPaid = (BigDecimal) o.get("paid");
        BigDecimal due = total.subtract(alreadyPaid);

        BigDecimal tendered = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        UUID shift = db.value("SELECT id FROM shifts WHERE user_id = :u AND status = 'OPEN' LIMIT 1", UUID.class);
        List<Map<String, Object>> payments = new ArrayList<>(b.list("payments"));
        // Cash given beyond what's due is change, not revenue; trim the cash line by it.
        BigDecimal sum = payments.stream().map(p -> new Body(p).num("amount", BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal change = sum.subtract(due).max(BigDecimal.ZERO);
        for (Map<String, Object> p : payments) {
            Body pb = new Body(p);
            String method = pb.str("method").toUpperCase();
            BigDecimal amount = pb.num("amount");
            if (amount.signum() <= 0) {
                continue;
            }
            if ("CASH".equals(method) && change.signum() > 0) {
                BigDecimal trim = change.min(amount);
                amount = amount.subtract(trim);
                change = change.subtract(trim);
                if (amount.signum() == 0) {
                    continue;
                }
            }
            if ("CREDIT".equals(method)) {
                credit = credit.add(amount);
            }
            db.exec("""
                    INSERT INTO payments (tenant_id, order_id, shift_id, method, amount, reference, user_id)
                    VALUES (:t, :order, :shift, :method, :amount, :ref, :u)""",
                    "order", orderId, "shift", shift, "method", method, "amount", amount, "ref", pb.str("reference", null));
            tendered = tendered.add(amount);
        }
        if (credit.signum() > 0) {
            Object customerId = o.get("customerId");
            if (customerId == null) {
                throw ApiException.bad("Pick the customer to put this on their account");
            }
            Map<String, Object> c = db.one("Customer", "SELECT balance, credit_limit, name FROM customers WHERE id = :id", "id", customerId);
            BigDecimal newBalance = ((BigDecimal) c.get("balance")).add(credit);
            BigDecimal limit = (BigDecimal) c.get("creditLimit");
            if (limit.signum() > 0 && newBalance.compareTo(limit) > 0) {
                throw ApiException.bad(c.get("name") + " would go over their credit limit of " + limit.toPlainString());
            }
            db.exec("UPDATE customers SET balance = balance + :c WHERE id = :id", "id", customerId, "c", credit);
        }
        BigDecimal paid = alreadyPaid.add(tendered);
        db.exec("UPDATE orders SET paid = :paid WHERE id = :id", "id", orderId, "paid", paid);
        if (paid.compareTo(total) >= 0) {
            close(orderId, credit.signum() > 0 ? "CREDIT" : "PAID");
        }
        Map<String, Object> result = new LinkedHashMap<>(detail(orderId));
        result.put("change", sum.subtract(due).max(BigDecimal.ZERO));
        return result;
    }

    private void close(UUID orderId, String status) {
        Map<String, Object> o = db.one("Order", "SELECT * FROM orders WHERE id = :id", "id", orderId);
        db.exec("UPDATE orders SET status = :s, closed_at = now() WHERE id = :id", "id", orderId, "s", status);
        db.exec("UPDATE order_lines SET kitchen_status = 'SERVED' WHERE order_id = :id AND kitchen_status IN ('READY')", "id", orderId);
        UUID loc = o.get("locationId") == null ? stock.defaultLocation() : UUID.fromString(o.get("locationId").toString());
        String ref = "Sale #" + o.get("number");
        for (Map<String, Object> l : db.list("SELECT item_id, qty FROM order_lines WHERE order_id = :id AND item_id IS NOT NULL", "id", orderId)) {
            stock.move(UUID.fromString(l.get("itemId").toString()), loc, ((BigDecimal) l.get("qty")).negate(), "SALE", ref, null);
        }
        if (o.get("customerId") != null) {
            int points = ((BigDecimal) o.get("total")).divide(HUNDRED, 0, RoundingMode.DOWN).intValue();
            db.exec("UPDATE customers SET points = points + :p WHERE id = :id", "id", o.get("customerId"), "p", points);
        }
        db.exec("UPDATE appointments SET status = 'DONE' WHERE order_id = :id", "id", orderId);
    }

    public void voidOrder(UUID orderId, String reason) {
        Map<String, Object> o = db.one("Order", "SELECT * FROM orders WHERE id = :id", "id", orderId);
        if ("VOID".equals(o.get("status"))) {
            return;
        }
        if (!"OPEN".equals(o.get("status"))) {
            // Undo a completed sale: return stock and reverse any account charge.
            UUID loc = o.get("locationId") == null ? stock.defaultLocation() : UUID.fromString(o.get("locationId").toString());
            for (Map<String, Object> l : db.list("SELECT item_id, qty FROM order_lines WHERE order_id = :id AND item_id IS NOT NULL", "id", orderId)) {
                stock.move(UUID.fromString(l.get("itemId").toString()), loc, (BigDecimal) l.get("qty"), "VOID", "Void #" + o.get("number"), reason);
            }
            BigDecimal credit = db.value("SELECT coalesce(sum(amount), 0) FROM payments WHERE order_id = :id AND method = 'CREDIT'",
                    BigDecimal.class, "id", orderId);
            if (credit != null && credit.signum() > 0 && o.get("customerId") != null) {
                db.exec("UPDATE customers SET balance = balance - :c WHERE id = :id", "id", o.get("customerId"), "c", credit);
            }
        }
        db.exec("UPDATE orders SET status = 'VOID', closed_at = now(), note = concat_ws(' · ', note, :reason) WHERE id = :id",
                "id", orderId, "reason", "Void: " + reason);
        db.exec("UPDATE order_lines SET kitchen_status = 'SERVED' WHERE order_id = :id", "id", orderId);
    }

    public Map<String, Object> detail(UUID orderId) {
        Map<String, Object> o = new LinkedHashMap<>(db.one("Order", """
                SELECT o.*, t.label AS table_label, c.name AS customer_name, c.phone AS customer_phone, u.name AS user_name
                FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id LEFT JOIN customers c ON c.id = o.customer_id
                LEFT JOIN users u ON u.id = o.user_id WHERE o.id = :id""", "id", orderId));
        o.put("lines", db.list("""
                SELECT l.*, s.name AS staff_name FROM order_lines l LEFT JOIN users s ON s.id = l.staff_id
                WHERE l.order_id = :id ORDER BY l.course, l.created_at""", "id", orderId));
        o.put("payments", db.list("SELECT * FROM payments WHERE order_id = :id ORDER BY created_at", "id", orderId));
        return o;
    }
}
