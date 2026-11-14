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
    private final ApprovalService approvals;
    private final com.lezitech.suite.etims.EtimsService etims;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.activity.ActivityLog activity;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.config.AccessPolicy access;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.loyalty.LoyaltyService loyalty;
    @org.springframework.beans.factory.annotation.Autowired
    private TillRules tills;

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.restaurant.Recipes recipes;

    public OrderService(Db db, JdbcTemplate jdbc, StockService stock, ApprovalService approvals, com.lezitech.suite.etims.EtimsService etims) {
        this.db = db;
        this.jdbc = jdbc;
        this.stock = stock;
        this.approvals = approvals;
        this.etims = etims;
    }

    /** An order from the till: prices, add-ons and discounts are checked against the caller's rights. */
    public UUID create(Body b) {
        return create(b, false);
    }

    /** An order built by the server itself (rent bills, room nights): its prices are already trusted. */
    public UUID createTrusted(Body b) {
        return create(b, true);
    }

    private UUID create(Body b, boolean trusted) {
        String kind = b.str("kind", "SALE");
        String channel = b.str("channel", "COUNTER");
        UUID tableId = b.has("tableId") ? UUID.fromString(b.str("tableId")) : null;
        if (tableId != null) {
            UUID open = db.value("SELECT id FROM orders WHERE table_id = :tableId AND status = 'OPEN' LIMIT 1", UUID.class, "tableId", tableId);
            if (open != null && !b.bool("allowSecondBill", false)) {
                addLines(open, b.list("lines"), b, trusted);
                return open;
            }
        }
        // tenants has no RLS; the counter is per workspace and bumped atomically.
        Integer number = jdbc.queryForObject("UPDATE tenants SET order_seq = order_seq + 1 WHERE id = ? RETURNING order_seq",
                Integer.class, TenantContext.tenantId());
        UUID shift = db.value("SELECT id FROM shifts WHERE user_id = :u AND status = 'OPEN' LIMIT 1", UUID.class);
        UUID loc = b.has("locationId") ? UUID.fromString(b.str("locationId")) : stock.defaultLocation();
        UUID id = db.insert("""
                INSERT INTO orders (tenant_id, number, kind, channel, table_id, guests, customer_id, location_id, shift_id, user_id, note,
                                    customer_label, due_on)
                VALUES (:t, :number, :kind, :channel, :tableId, :guests, :customerId, :loc, :shift, :u, :note,
                        :label, :due) RETURNING id""",
                "label", b.str("customerLabel", null), "due", b.date("dueOn"),
                "number", number, "kind", kind, "channel", channel, "tableId", tableId, "guests", b.integer("guests", null),
                "customerId", b.has("customerId") ? UUID.fromString(b.str("customerId")) : null,
                "loc", loc, "shift", shift, "note", b.str("note", null));
        // A waiter's own order stays theirs (table orders from the QR are shared out by load instead).
        TenantContext.Caller who = TenantContext.callerOrNull();
        if (who != null && "WAITER".equals(who.role()) && who.userId() != null) {
            db.exec("UPDATE orders SET assigned_to = :u WHERE id = :id", "id", id);
        }
        addLines(id, b.list("lines"), b, trusted);
        return id;
    }

    /** Server-side additions (tips, room nights): trusted. */
    public void addLines(UUID orderId, List<Map<String, Object>> lines) {
        addLines(orderId, lines, null, true);
    }

    /** Lines sent by the till: price changes, custom prices and line discounts need a manager. */
    public void addLinesFromTill(UUID orderId, Body b) {
        addLines(orderId, b.list("lines"), b, false);
    }

    private void addLines(UUID orderId, List<Map<String, Object>> lines, Body approvalSource, boolean trusted) {
        Map<String, Object> order = requireOpen(orderId);
        boolean wholesale = "WHOLESALE".equals(order.get("channel"))
                || (order.get("customerId") != null && "WHOLESALE".equals(db.value(
                "SELECT kind FROM customers WHERE id = :id", String.class, "id", order.get("customerId"))));
        // Cashiers may bargain down to an item's lowest price (its wholesale price) without a PIN, unless the owner turned that off.
        boolean bargain = !Boolean.FALSE.equals(db.value(
                "SELECT (settings->>'bargainToLowest')::boolean FROM tenants WHERE id = :t", Boolean.class));
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
                if (com.lezitech.suite.restaurant.Recipes.KITCHEN_KINDS.contains(item.get("kind"))) {
                    throw ApiException.bad(name + " is a kitchen ingredient, not something to sell");
                }
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
                if (!trusted) {
                    String by = approvals.require(approvalSource, "a custom-priced item");
                    activity.record("CUSTOM_ITEM", com.lezitech.suite.activity.ActivityLog.NOTICE,
                            "Sold \"" + name + "\", not in the item list, at " + price.toPlainString(), orderId, null, price.multiply(qty), by,
                            Map.of("qty", qty, "price", price));
                }
            }
            if (l.has("unitPrice") && item != null && l.num("unitPrice").compareTo(price) != 0) {
                // A price typed at the till. Down to the lowest price is the cashier's call (if the owner allows);
                // anything below it needs a manager's PIN. Both are written to the activity log.
                BigDecimal asked = l.num("unitPrice");
                BigDecimal shelf = (BigDecimal) item.get("price");
                BigDecimal lowest = (BigDecimal) item.get("wholesalePrice");
                boolean belowLowest = lowest != null ? asked.compareTo(lowest) < 0 : asked.compareTo(price) < 0;
                boolean needsPin = asked.compareTo(price) < 0 && (belowLowest || !bargain);
                String by = null;
                if (!trusted && needsPin) {
                    by = approvals.require(approvalSource, belowLowest && lowest != null
                            ? "selling " + name + " below its lowest price (" + lowest.toPlainString() + ")" : "a price change on " + name);
                }
                if (!trusted && asked.compareTo(shelf) != 0) {
                    boolean down = asked.compareTo(shelf) < 0;
                    activity.record(belowLowest && lowest != null ? "BELOW_LOWEST_PRICE" : "PRICE_CHANGE",
                            belowLowest && lowest != null ? com.lezitech.suite.activity.ActivityLog.CONCERN
                                    : down ? com.lezitech.suite.activity.ActivityLog.NOTICE : com.lezitech.suite.activity.ActivityLog.INFO,
                            name + " sold at " + asked.toPlainString() + " (shelf price " + shelf.toPlainString()
                                    + (lowest != null ? ", lowest " + lowest.toPlainString() : "") + ") × " + qty.stripTrailingZeros().toPlainString(),
                            orderId, UUID.fromString(item.get("id").toString()), down ? shelf.subtract(asked).multiply(qty) : BigDecimal.ZERO, by,
                            Map.of("shelf", shelf, "sold", asked, "qty", qty, "lowest", lowest == null ? "" : lowest));
                }
                price = asked;
            }
            if (price.signum() < 0) {
                throw ApiException.bad("A price can't be below zero");
            }
            List<Map<String, Object>> options = trusted || item == null ? l.list("options") : checkedOptions(item, l.list("options"));
            BigDecimal optionsTotal = BigDecimal.ZERO;
            for (Map<String, Object> o : options) {
                optionsTotal = optionsTotal.add(new Body(o).num("price", BigDecimal.ZERO));
            }
            BigDecimal unit = price.add(optionsTotal);
            BigDecimal discount = l.num("discount", BigDecimal.ZERO);
            if (discount.signum() < 0) {
                throw ApiException.bad("A discount can't be below zero");
            }
            if (discount.signum() > 0 && !trusted) {
                String by = approvals.require(approvalSource, "a discount on " + name);
                approvals.checkDiscountLimit(discount, unit.multiply(qty));
                BigDecimal lowest = item == null ? null : (BigDecimal) item.get("wholesalePrice");
                boolean underLowest = lowest != null && unit.multiply(qty).subtract(discount).compareTo(lowest.multiply(qty)) < 0;
                activity.record("DISCOUNT", underLowest ? com.lezitech.suite.activity.ActivityLog.CONCERN : com.lezitech.suite.activity.ActivityLog.NOTICE,
                        "Discount of " + discount.toPlainString() + " on " + name + (underLowest ? ", taking it below its lowest price" : ""),
                        orderId, item == null ? null : UUID.fromString(item.get("id").toString()), discount, by, Map.of("qty", qty));
            }
            BigDecimal lineTotal = unit.multiply(qty).subtract(discount).setScale(2, RoundingMode.HALF_UP);
            if (lineTotal.signum() < 0) {
                throw ApiException.bad("The discount on " + name + " is bigger than its price");
            }
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

    /** Whole-bill discount: needs a manager (or their PIN), stays within the limit, never makes the bill negative. */
    public void setDiscount(UUID orderId, BigDecimal discount, Body approvalSource) {
        if (discount.signum() < 0) {
            throw ApiException.bad("A discount can't be below zero");
        }
        BigDecimal current = db.value("SELECT discount FROM orders WHERE id = :id", BigDecimal.class, "id", orderId);
        if (current != null && discount.compareTo(current) == 0) {
            return;
        }
        BigDecimal subtotal = db.value("SELECT coalesce(sum(line_total), 0) FROM order_lines WHERE order_id = :id", BigDecimal.class, "id", orderId);
        if (discount.signum() > 0) {
            String by = approvals.require(approvalSource, "a discount");
            BigDecimal change = discount.subtract(current == null ? BigDecimal.ZERO : current);
            if (change.signum() > 0) {
                activity.record("DISCOUNT", com.lezitech.suite.activity.ActivityLog.NOTICE, "Discount of " + discount.toPlainString() + " on the whole bill",
                        orderId, null, change, by, Map.of());
            }
            if (discount.compareTo(subtotal) > 0) {
                throw ApiException.bad("The discount is bigger than the bill");
            }
            BigDecimal lineDiscounts = db.value("SELECT coalesce(sum(discount), 0) FROM order_lines WHERE order_id = :id", BigDecimal.class, "id", orderId);
            approvals.checkDiscountLimit(discount.add(lineDiscounts), subtotal.add(lineDiscounts));
        }
        db.exec("UPDATE orders SET discount = :d WHERE id = :id", "id", orderId, "d", discount);
        recalc(orderId);
    }

    /** Add-on prices come from the item's own definition, never from the till. */
    private List<Map<String, Object>> checkedOptions(Map<String, Object> item, List<Map<String, Object>> picked) {
        if (picked.isEmpty()) {
            return picked;
        }
        com.fasterxml.jackson.databind.JsonNode groups = (com.fasterxml.jackson.databind.JsonNode) item.get("options");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : picked) {
            Body pb = new Body(p);
            String group = pb.str("group", "");
            String name = pb.str("name");
            BigDecimal price = null;
            if (groups != null) {
                for (com.fasterxml.jackson.databind.JsonNode g : groups) {
                    if (!group.isEmpty() && !group.equals(g.path("name").asText())) {
                        continue;
                    }
                    for (com.fasterxml.jackson.databind.JsonNode c : g.path("choices")) {
                        if (name.equals(c.path("name").asText())) {
                            price = new BigDecimal(c.path("price").asText("0"));
                            group = g.path("name").asText();
                        }
                    }
                }
            }
            if (price == null) {
                throw ApiException.bad("\u201c" + name + "\u201d is not one of the choices for " + item.get("name"));
            }
            out.add(Map.of("group", group, "name", name, "price", price));
        }
        return out;
    }

    /**
     * Reserve an idempotency key for this till action. Returns the order an earlier request with
     * the same key already produced, or null if this is the first time (or no key was sent).
     * A second request with the key waits here until the first one commits.
     */
    public UUID claimKey(Body b, String scope) {
        if (!b.has("idempotencyKey")) {
            return null;
        }
        String key = scope + ":" + b.str("idempotencyKey");
        int inserted = db.exec("INSERT INTO idempotency_keys (tenant_id, key) VALUES (:t, :k) ON CONFLICT DO NOTHING", "k", key);
        if (inserted == 1) {
            return null;
        }
        UUID earlier = db.value("SELECT order_id FROM idempotency_keys WHERE key = :k", UUID.class, "k", key);
        if (earlier == null) {
            throw ApiException.conflict("This payment is already being processed");
        }
        return earlier;
    }

    public void bindKey(Body b, String scope, UUID orderId) {
        if (b.has("idempotencyKey")) {
            db.exec("UPDATE idempotency_keys SET order_id = :o WHERE key = :k", "o", orderId, "k", scope + ":" + b.str("idempotencyKey"));
        }
    }

    /**
     * The open order, locked for the rest of the transaction so two tills (or a double tap)
     * can't add, pay or cancel at the same time.
     */
    public Map<String, Object> requireOpen(UUID orderId) {
        Map<String, Object> o = db.one("Order", "SELECT * FROM orders WHERE id = :id FOR UPDATE", "id", orderId);
        if (!"OPEN".equals(o.get("status"))) {
            throw ApiException.conflict("This " + o.get("kind").toString().toLowerCase() + " is already closed");
        }
        return o;
    }

    public Map<String, Object> pay(UUID orderId, Body b) {
        // A sale rung up offline already happened; everything else from a cashier needs their till open.
        if (!b.has("offlineAt") && b.list("payments") != null && !b.list("payments").isEmpty()) {
            tills.requireOpenTill();
        }
        for (Map<String, Object> p : b.list("payments") == null ? List.<Map<String, Object>>of() : b.list("payments")) {
            if ("POINTS".equalsIgnoreCase(String.valueOf(p.get("method")))) {
                throw ApiException.bad("Use the customer's points with \"Use points\", so they come off their balance");
            }
        }
        return payInternal(orderId, b);
    }

    /** Points used on a bill (checked and recorded by LoyaltyService): a POINTS payment, closing the bill if that settles it. */
    public Map<String, Object> payWithPoints(UUID orderId, BigDecimal value, String reference) {
        return payInternal(orderId, new Body(Map.of("payments", List.of(Map.of("method", "POINTS", "amount", value, "reference", reference)))));
    }

    private Map<String, Object> payInternal(UUID orderId, Body b) {
        Map<String, Object> o = requireOpen(orderId);
        if ("QUOTE".equals(o.get("kind"))) {
            throw ApiException.bad("Convert the quotation to a sale before taking payment");
        }
        if (b.has("discount")) {
            setDiscount(orderId, b.num("discount"), b);
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
        // A deposit (room, tour, event) may exceed what's billed so far; it's not change.
        boolean deposit = b.bool("deposit", false);
        BigDecimal change = deposit ? BigDecimal.ZERO : sum.subtract(due).max(BigDecimal.ZERO);
        final BigDecimal changeDue = change;
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
            // Lock the customer too: two tills putting sales on the same account can't both slip under the limit.
            Map<String, Object> c = db.one("Customer", "SELECT balance, credit_limit, name FROM customers WHERE id = :id FOR UPDATE", "id", customerId);
            BigDecimal newBalance = ((BigDecimal) c.get("balance")).add(credit);
            BigDecimal limit = (BigDecimal) c.get("creditLimit");
            if (limit.signum() > 0 && newBalance.compareTo(limit) > 0) {
                throw ApiException.bad(c.get("name") + " would go over their credit limit of " + limit.toPlainString());
            }
            db.exec("UPDATE customers SET balance = balance + :c WHERE id = :id", "id", customerId, "c", credit);
        }
        BigDecimal paid = alreadyPaid.add(tendered);
        db.exec("UPDATE orders SET paid = :paid WHERE id = :id", "id", orderId, "paid", paid);
        if (!deposit && paid.compareTo(total) >= 0) {
            close(orderId, credit.signum() > 0 ? "CREDIT" : "PAID");
        }
        Map<String, Object> result = new LinkedHashMap<>(detail(orderId));
        result.put("change", changeDue);
        return result;
    }

    private void close(UUID orderId, String status) {
        Map<String, Object> o = db.one("Order", "SELECT * FROM orders WHERE id = :id", "id", orderId);
        db.exec("UPDATE orders SET status = :s, closed_at = now() WHERE id = :id", "id", orderId, "s", status);
        if (!Boolean.TRUE.equals(o.get("selfOrder"))) {
            db.exec("UPDATE order_lines SET kitchen_status = 'SERVED' WHERE order_id = :id AND kitchen_status IN ('READY')", "id", orderId);
        }
        UUID loc = o.get("locationId") == null ? stock.defaultLocation() : UUID.fromString(o.get("locationId").toString());
        String ref = "Sale #" + o.get("number");
        for (Map<String, Object> l : db.list("SELECT item_id, qty FROM order_lines WHERE order_id = :id AND item_id IS NOT NULL", "id", orderId)) {
            stock.move(UUID.fromString(l.get("itemId").toString()), loc, ((BigDecimal) l.get("qty")).negate(), "SALE", ref, null);
        }
        // R2: dishes with a recipe use up their ingredients.
        recipes.useForOrder(orderId, loc, -1, "SALE", ref);
        // Loyalty points on the owner's rules (the old flat 1-per-100 counter, customers.points, is no longer used).
        if ("PAID".equals(status)) {
            loyalty.earn(orderId);
        }
        db.exec("UPDATE appointments SET status = 'DONE' WHERE order_id = :id", "id", orderId);
        etims.queue(orderId, "SALE");
    }

    /**
     * Cancel an order. An unpaid one is simply voided (any deposit already taken is given back).
     * A completed sale is refunded instead: see {@link #refund}.
     */
    public void voidOrder(UUID orderId, String reason) {
        Map<String, Object> o = db.one("Order", "SELECT * FROM orders WHERE id = :id FOR UPDATE", "id", orderId);
        String status = (String) o.get("status");
        if ("VOID".equals(status) || "REFUNDED".equals(status)) {
            return;
        }
        tills.requireUnlocked(orderId, "cancelled");
        if (!"OPEN".equals(status)) {
            refund(orderId, reason, null);
            return;
        }
        if (((BigDecimal) o.get("paid")).signum() > 0) {
            // A deposit was taken on this open bill: give it back the way it came in.
            payBack(o, reason, null);
        }
        boolean wentToKitchen = Boolean.TRUE.equals(db.value(
                "SELECT exists(SELECT 1 FROM order_lines WHERE order_id = :id AND kitchen_status <> 'PENDING')", Boolean.class, "id", orderId));
        activity.record("VOID", wentToKitchen || ((BigDecimal) o.get("paid")).signum() > 0 ? com.lezitech.suite.activity.ActivityLog.CONCERN
                        : com.lezitech.suite.activity.ActivityLog.NOTICE,
                "Cancelled #" + o.get("number") + " worth " + ((BigDecimal) o.get("total")).toPlainString()
                        + (wentToKitchen ? " after it went to the kitchen" : "") + ": " + reason,
                orderId, null, (BigDecimal) o.get("total"), null, Map.of("reason", reason));
        db.exec("UPDATE orders SET status = 'VOID', closed_at = now(), note = concat_ws(' · ', note, :reason) WHERE id = :id",
                "id", orderId, "reason", "Cancelled: " + reason);
        db.exec("UPDATE order_lines SET kitchen_status = 'SERVED' WHERE order_id = :id", "id", orderId);
    }

    /**
     * Refund a completed sale: money goes back the way it came in (or all by one method if the
     * manager chooses), as negative payments in the refunder's till session; account sales come
     * off what the customer owes; items go back into stock; loyalty points are taken back.
     */
    public void refund(UUID orderId, String reason, String method) {
        if (!access.mayRefund(TenantContext.tenantId(), TenantContext.caller().role())) {
            throw ApiException.forbidden("Your job can't give refunds. Ask the owner or a manager.");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.bad("Say why the money is being given back");
        }
        Map<String, Object> o = db.one("Order", "SELECT * FROM orders WHERE id = :id FOR UPDATE", "id", orderId);
        if (!"PAID".equals(o.get("status")) && !"CREDIT".equals(o.get("status"))) {
            throw ApiException.conflict("Only completed sales can be refunded");
        }
        tills.requireUnlocked(orderId, "refunded");
        payBack(o, reason, method);
        UUID loc = o.get("locationId") == null ? stock.defaultLocation() : UUID.fromString(o.get("locationId").toString());
        for (Map<String, Object> l : db.list("SELECT item_id, qty FROM order_lines WHERE order_id = :id AND item_id IS NOT NULL", "id", orderId)) {
            stock.move(UUID.fromString(l.get("itemId").toString()), loc, (BigDecimal) l.get("qty"), "VOID", "Refund #" + o.get("number"), reason);
        }
        recipes.useForOrder(orderId, loc, 1, "VOID", "Refund #" + o.get("number"));
        loyalty.refunded(orderId);
        db.exec("""
                UPDATE orders SET status = 'REFUNDED', refunded_at = now(), refunded_by = :u,
                       note = concat_ws(' · ', note, :reason) WHERE id = :id""", "id", orderId, "reason", "Refunded: " + reason);
        boolean ownSale = TenantContext.userId().equals(o.get("userId") == null ? null : UUID.fromString(o.get("userId").toString()));
        activity.record("REFUND", ownSale ? com.lezitech.suite.activity.ActivityLog.CONCERN : com.lezitech.suite.activity.ActivityLog.NOTICE,
                "Refunded #" + o.get("number") + " worth " + ((BigDecimal) o.get("total")).toPlainString() + (ownSale ? " (their own sale)" : "") + ": " + reason,
                orderId, null, (BigDecimal) o.get("total"), null, Map.of("reason", reason));
        etims.queue(orderId, "CREDIT_NOTE");
    }

    /** Negative payment rows mirroring what was paid, net of anything already given back. */
    private void payBack(Map<String, Object> o, String reason, String method) {
        UUID orderId = UUID.fromString(o.get("id").toString());
        UUID shift = db.value("SELECT id FROM shifts WHERE user_id = :u AND status = 'OPEN' LIMIT 1", UUID.class);
        BigDecimal toAccount = BigDecimal.ZERO;
        for (Map<String, Object> p : db.list("""
                SELECT method, sum(amount) AS amount FROM payments WHERE order_id = :id GROUP BY method HAVING sum(amount) > 0""", "id", orderId)) {
            String paidWith = (String) p.get("method");
            BigDecimal amount = (BigDecimal) p.get("amount");
            if ("CREDIT".equals(paidWith)) {
                toAccount = toAccount.add(amount);
            }
            // Account sales come off the account and points go back as points, whatever method the refund is in.
            String back = "CREDIT".equals(paidWith) || "POINTS".equals(paidWith) || method == null ? paidWith : method.toUpperCase();
            db.exec("""
                    INSERT INTO payments (tenant_id, order_id, shift_id, method, amount, kind, refund_reason, user_id)
                    VALUES (:t, :order, :shift, :method, :amount, 'REFUND', :reason, :u)""",
                    "order", orderId, "shift", shift, "method", back, "amount", amount.negate(), "reason", reason);
        }
        if (toAccount.signum() > 0 && o.get("customerId") != null) {
            db.list("SELECT id FROM customers WHERE id = :id FOR UPDATE", "id", o.get("customerId"));
            db.exec("UPDATE customers SET balance = balance - :c WHERE id = :id", "id", o.get("customerId"), "c", toAccount);
        }
        db.exec("UPDATE orders SET paid = 0 WHERE id = :id", "id", orderId);
    }

    /** Hotel guest eats in the restaurant: the bill moves onto their room folio, settled at check-out. */
    public UUID chargeToStay(UUID orderId, UUID stayId) {
        Map<String, Object> o = requireOpen(orderId);
        if (((BigDecimal) o.get("paid")).signum() > 0) {
            throw ApiException.bad("This bill is part-paid; take the rest here instead");
        }
        Map<String, Object> stay = db.one("In-house stay", "SELECT * FROM stays WHERE id = :stayId AND status = 'IN_HOUSE'", "stayId", stayId);
        UUID folio = UUID.fromString(stay.get("folioId").toString());
        if (folio.equals(orderId)) {
            throw ApiException.bad("That's already the room folio");
        }
        db.exec("UPDATE order_lines SET order_id = :folio WHERE order_id = :id", "folio", folio, "id", orderId);
        db.exec("DELETE FROM orders WHERE id = :id", "id", orderId);
        recalc(folio);
        return folio;
    }

    /** Orders whose sent dishes are all cooked get a ready time, for the customer screen. */
    public void markReadyIfDone(UUID orderId) {
        db.exec("""
                UPDATE orders o SET ready_at = CASE
                    WHEN EXISTS (SELECT 1 FROM order_lines l WHERE l.order_id = o.id AND l.kitchen_status IN ('NEW','PREPARING')) THEN NULL
                    ELSE coalesce(o.ready_at, now()) END
                WHERE o.id = :orderId""", "orderId", orderId);
    }

    /** A till that was offline sends its sale later; it keeps the time it really happened (up to a week back). */
    public void markOffline(UUID orderId, java.time.Instant at) {
        java.time.Instant now = java.time.Instant.now();
        if (at.isAfter(now.plusSeconds(300)) || at.isBefore(now.minus(java.time.Duration.ofDays(7)))) {
            throw ApiException.bad("Offline sales can only be sent within 7 days; check the till's date and time");
        }
        java.sql.Timestamp ts = java.sql.Timestamp.from(at);
        db.exec("UPDATE orders SET offline = true, created_at = :at, closed_at = CASE WHEN closed_at IS NULL THEN NULL ELSE cast(:at AS timestamptz) END WHERE id = :id",
                "id", orderId, "at", ts);
        db.exec("UPDATE payments SET created_at = :at WHERE order_id = :id", "id", orderId, "at", ts);
    }

    public Map<String, Object> detail(UUID orderId) {
        Map<String, Object> o = new LinkedHashMap<>(db.one("Order", """
                SELECT o.*, t.label AS table_label, c.name AS customer_name, c.phone AS customer_phone, u.name AS user_name,
                       (SELECT sp.name FROM stays st JOIN spaces sp ON sp.id = st.space_id WHERE st.id = o.stay_id) AS room_name
                FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id LEFT JOIN customers c ON c.id = o.customer_id
                LEFT JOIN users u ON u.id = o.user_id WHERE o.id = :id""", "id", orderId));
        o.put("lines", db.list("""
                SELECT l.*, s.name AS staff_name FROM order_lines l LEFT JOIN users s ON s.id = l.staff_id
                WHERE l.order_id = :id ORDER BY l.course, l.created_at""", "id", orderId));
        o.put("payments", db.list("SELECT * FROM payments WHERE order_id = :id ORDER BY created_at", "id", orderId));
        return o;
    }
}
