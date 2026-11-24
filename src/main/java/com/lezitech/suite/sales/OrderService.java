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

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlanFeatures planFeatures;

    private final Db db;
    private final JdbcTemplate jdbc;
    private final StockService stock;
    private final ApprovalService approvals;
    private final com.lezitech.suite.etims.EtimsService etims;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.crm.CustomerLedger customerLedger;
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
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.restaurant.Deals deals;

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
        if ("QUOTE".equals(kind)) {
            planFeatures.require("quotes");
        }
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
        requireNotCatering(orderId, "add dishes");
        addLines(orderId, b.list("lines"), b, false);
    }

    private static UUID staffIdOrBad(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.bad("That staff member isn't on this business, so the sale can't be credited to them");
        }
    }

    private void addLines(UUID orderId, List<Map<String, Object>> lines, Body approvalSource, boolean trusted) {
        Map<String, Object> order = requireOpen(orderId);
        boolean wholesale = "WHOLESALE".equals(order.get("channel"))
                || (order.get("customerId") != null && "WHOLESALE".equals(db.value(
                "SELECT kind FROM customers WHERE id = :id", String.class, "id", order.get("customerId"))));
        // Cashiers may bargain down to an item's lowest price (its wholesale price) without a PIN, unless the owner turned that off.
        boolean bargain = !Boolean.FALSE.equals(db.value(
                "SELECT (settings->>'bargainToLowest')::boolean FROM tenants WHERE id = :t", Boolean.class));
        // A line may name who made the sale; that person must be on this business. Checked once for the whole request.
        List<UUID> staffIds = new ArrayList<>();
        for (Map<String, Object> raw : lines) {
            Body sl = new Body(raw);
            if (sl.has("staffId")) {
                UUID sid = staffIdOrBad(sl.str("staffId"));
                if (!staffIds.contains(sid)) {
                    staffIds.add(sid);
                }
            }
        }
        if (!staffIds.isEmpty()) {
            Long known = db.value("SELECT count(*) FROM users WHERE tenant_id = :t AND id IN (:ids)", Long.class, "ids", staffIds);
            if (known == null || known != staffIds.size()) {
                throw ApiException.bad("That staff member isn't on this business, so the sale can't be credited to them");
            }
        }
        for (Map<String, Object> raw : lines) {
            Body l = new Body(raw);
            BigDecimal qty = l.num("qty", BigDecimal.ONE);
            if (qty.signum() <= 0) {
                continue;
            }
            Map<String, Object> item = null;
            UUID happyDeal = null;
            String name;
            BigDecimal price;
            BigDecimal tax;
            String station = null;
            // A whole pack (a carton of 24): one line, its own price, and that many pieces off stock.
            BigDecimal packSize = null;
            boolean byWeight = false; // sold by weight: the quantity is kilos
            if (l.has("itemId")) {
                item = db.one("Item", "SELECT * FROM items WHERE id = :id", "id", l.str("itemId"));
                name = (String) item.get("name");
                if (item.get("variantAxes") != null) {
                    throw ApiException.bad("Choose which size or colour of " + name + " to sell");
                }
                if (com.lezitech.suite.restaurant.Recipes.KITCHEN_KINDS.contains(item.get("kind"))) {
                    throw ApiException.bad(name + " is a kitchen ingredient, not something to sell");
                }
                price = (BigDecimal) item.get("price");
                byWeight = ByWeight.is(item);
                if (byWeight) {
                    qty = ByWeight.checkedKg(qty, name);
                }
                if (wholesale && item.get("wholesalePrice") != null) {
                    BigDecimal min = (BigDecimal) item.get("wholesaleMinQty");
                    if (min == null || qty.compareTo(min) >= 0) {
                        price = (BigDecimal) item.get("wholesalePrice");
                    }
                }
                tax = (BigDecimal) item.get("taxRate");
                station = (String) item.get("station");
                // R6: happy hour. The price is fixed now, when it's ordered.
                if (!wholesale) {
                    var happy = deals.happyPrice(item, java.time.Instant.now());
                    if (happy != null && happy.price().compareTo(price) < 0) {
                        price = happy.price();
                        happyDeal = happy.dealId();
                    }
                }
                if (l.bool("asPack", false) && byWeight) {
                    throw ApiException.bad(name + " is sold by weight, not by the pack");
                }
                if (l.bool("asPack", false)) {
                    BigDecimal size = (BigDecimal) item.get("buySize");
                    if (size == null || size.signum() <= 0 || !"PRODUCT".equals(item.get("kind"))) {
                        throw ApiException.bad(name + " isn't sold by the " + (item.get("buyUnit") == null ? "pack" : item.get("buyUnit")));
                    }
                    packSize = size;
                    String unitName = item.get("buyUnit") == null ? "pack" : (String) item.get("buyUnit");
                    price = item.get("packPrice") != null ? (BigDecimal) item.get("packPrice") : ((BigDecimal) item.get("price")).multiply(size);
                    name = name + " (" + unitName + " of " + size.stripTrailingZeros().toPlainString() + ")";
                    happyDeal = null;
                }
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
            // A by-weight item is always sold at its price per kg; a price typed at the till is ignored.
            if (l.has("unitPrice") && item != null && !byWeight && l.num("unitPrice").compareTo(price) != 0) {
                // A price typed at the till. Down to the lowest price is the cashier's call (if the owner allows);
                // anything below it needs a manager's PIN. Both are written to the activity log.
                BigDecimal asked = l.num("unitPrice");
                BigDecimal shelf = packSize != null ? price : (BigDecimal) item.get("price");
                BigDecimal lowest = packSize != null ? null : (BigDecimal) item.get("wholesalePrice");
                boolean belowLowest = lowest != null ? asked.compareTo(lowest) < 0 : asked.compareTo(price) < 0;
                // Below what the item cost (a pack costs its pieces together): always a manager's call, on top of any other reason.
                BigDecimal unitCost = item.get("cost") == null ? BigDecimal.ZERO : (BigDecimal) item.get("cost");
                BigDecimal cost = packSize != null ? unitCost.multiply(packSize) : unitCost;
                boolean belowCost = cost.signum() > 0 && asked.compareTo(cost) < 0;
                boolean needsPin = (asked.compareTo(price) < 0 && (belowLowest || !bargain)) || belowCost;
                String by = null;
                if (!trusted && needsPin) {
                    String needed = belowCost
                            ? "selling " + name + " below what it cost (" + cost.toPlainString() + ")"
                            : belowLowest && lowest != null
                            ? "selling " + name + " below its lowest price (" + lowest.toPlainString() + ")" : "a price change on " + name;
                    by = belowCost ? approvals.requireManager(approvalSource, needed) : approvals.require(approvalSource, needed);
                }
                if (!trusted && belowCost) {
                    activity.record("BELOW_COST", com.lezitech.suite.activity.ActivityLog.CONCERN,
                            name + " sold at " + asked.toPlainString() + ", below what it cost (" + cost.toPlainString() + ") × "
                                    + qty.stripTrailingZeros().toPlainString(),
                            orderId, UUID.fromString(item.get("id").toString()), cost.subtract(asked).multiply(qty), by,
                            Map.of("cost", cost, "sold", asked, "qty", qty));
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
            boolean combo = item != null && "COMBO".equals(item.get("kind"));
            List<UUID> parts = combo ? comboParts(item, options) : List.of();
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
                BigDecimal lowest = item == null || packSize != null ? null : (BigDecimal) item.get("wholesalePrice");
                boolean underLowest = lowest != null && unit.multiply(qty).subtract(discount).compareTo(lowest.multiply(qty)) < 0;
                activity.record("DISCOUNT", underLowest || bigDiscount(discount, unit.multiply(qty)) ? com.lezitech.suite.activity.ActivityLog.CONCERN
                                : com.lezitech.suite.activity.ActivityLog.NOTICE,
                        "Discount of " + discount.toPlainString() + " on " + name + (underLowest ? ", taking it below its lowest price" : ""),
                        orderId, item == null ? null : UUID.fromString(item.get("id").toString()), discount, by, Map.of("qty", qty));
            }
            BigDecimal lineTotal = unit.multiply(qty).subtract(discount).setScale(byWeight ? 0 : 2, RoundingMode.HALF_UP);
            if (lineTotal.signum() < 0) {
                throw ApiException.bad("The discount on " + name + " is bigger than its price");
            }
            UUID lineId = db.insert("""
                    INSERT INTO order_lines (tenant_id, order_id, item_id, name, qty, unit_price, tax_rate, discount, line_total,
                                             options, note, course, station, staff_id, happy_deal_id, kitchen_status, pack_size, unit_name)
                    VALUES (:t, :order, :item, :name, :qty, :unit, :tax, :discount, :total, cast(:options as jsonb), :note,
                            :course, :station, :staff, :happy, :ks, :pack, :unitName) RETURNING id""",
                    "order", orderId, "item", item == null ? null : UUID.fromString(item.get("id").toString()), "name", name,
                    "qty", qty, "unit", unit, "tax", tax, "discount", discount, "total", lineTotal,
                    "options", db.toJson(options), "note", l.str("note", null), "course", l.integer("course", 1),
                    "station", combo ? null : station, "staff", l.has("staffId") ? staffIdOrBad(l.str("staffId")) : null,
                    "happy", happyDeal, "ks", combo ? "SERVED" : "PENDING", "pack", packSize, "unitName", byWeight ? "kg" : null);
            // R6: a combo's parts go to the kitchen and bar (and use up stock and recipes) as KES 0 lines under it.
            for (UUID part : parts) {
                Map<String, Object> pi = db.one("Item", "SELECT name, station, tax_rate FROM items WHERE id = :id", "id", part);
                db.exec("""
                        INSERT INTO order_lines (tenant_id, order_id, item_id, name, qty, unit_price, tax_rate, discount, line_total,
                                                 options, note, course, station, parent_line_id)
                        VALUES (:t, :order, :item, :name, :qty, 0, :tax, 0, 0, '[]'::jsonb, :note, :course, :station, :parent)""",
                        "order", orderId, "item", part, "name", pi.get("name"), "qty", qty, "tax", pi.get("taxRate"),
                        "note", "Part of " + name + (l.str("note", null) != null ? ". " + l.str("note", null) : ""),
                        "course", l.integer("course", 1), "station", pi.get("station"), "parent", lineId);
            }
        }
        recalc(orderId);
    }

    /**
     * The one way to take a line (and its parts) off a bill. Anything worth money leaves an ITEM_REMOVED
     * note in the activity log; a line worth nothing (a combo part, a deal line) goes quietly.
     * {@code why} is the wording after the bill number, e.g. " after the bill was printed"; when empty it is worked out here.
     */
    public void removeLine(UUID lineId, UUID orderId, String why) {
        removeLine(lineId, orderId, why, false);
    }

    /** {@code routine}: a charge the system itself added and withdrew (a payment fee), noted but never raised as a concern. */
    public void removeLine(UUID lineId, UUID orderId, String why, boolean routine) {
        Map<String, Object> l = db.find("""
                SELECT l.name, l.qty, l.item_id, l.kitchen_status,
                       l.line_total + coalesce((SELECT sum(c.line_total) FROM order_lines c WHERE c.parent_line_id = l.id), 0) AS worth
                FROM order_lines l WHERE l.id = :id AND l.order_id = :order""", "id", lineId, "order", orderId).orElse(null);
        if (l == null) {
            return;
        }
        Map<String, Object> o = db.one("Order", "SELECT number, bill_printed_at FROM orders WHERE id = :id", "id", orderId);
        db.exec("DELETE FROM order_lines WHERE id = :id OR parent_line_id = :id", "id", lineId);
        BigDecimal worth = (BigDecimal) l.get("worth");
        if (worth == null || worth.signum() <= 0) {
            return;
        }
        boolean printed = o.get("billPrintedAt") != null;
        boolean cooking = !"PENDING".equals(l.get("kitchenStatus"));
        String reason = why != null && !why.isBlank() ? why
                : printed ? " after the bill was printed" : cooking ? " after the kitchen started it" : "";
        String severity = !routine && (printed || cooking) ? com.lezitech.suite.activity.ActivityLog.CONCERN
                : com.lezitech.suite.activity.ActivityLog.NOTICE;
        BigDecimal qty = (BigDecimal) l.get("qty");
        // A charge the system itself withdrew (a failed payment's fee) is noted, but it is not a staff member
        // taking goods off a bill, so it must not make an ordinary bill need a manager to cancel.
        activity.record(routine ? "LINE_DROPPED" : "ITEM_REMOVED", severity,
                "Took " + qty.stripTrailingZeros().toPlainString() + " × " + l.get("name") + " off #" + o.get("number")
                        + reason + " (KES " + worth.setScale(0, RoundingMode.HALF_UP) + ")",
                orderId, l.get("itemId") == null ? null : UUID.fromString(l.get("itemId").toString()), worth, null, Map.of("qty", qty));
    }

    /** Prices are VAT-inclusive (the norm on Kenyan shelves); tax is the VAT portion. */
    public void recalc(UUID orderId) {
        // R6: bundle and buy-one-get-one deals on what's on the bill now.
        deals.apply(orderId);
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

    /** R8: a discount at or above the owner's alert % (20% unless changed) is worth an alert. */
    private boolean bigDiscount(BigDecimal discount, BigDecimal of) {
        if (of == null || of.signum() <= 0) {
            return false;
        }
        Integer pct = db.value("SELECT coalesce((settings->'nightSummary'->>'discountAlertPct')::int, 20) FROM tenants WHERE id = :t", Integer.class);
        return discount.multiply(BigDecimal.valueOf(100)).compareTo(of.multiply(BigDecimal.valueOf(pct == null ? 20 : pct))) >= 0;
    }

    /** The bill was printed for the customer (from the till): later changes to it are a warning sign. */
    public void printed(UUID orderId) {
        db.exec("UPDATE orders SET bill_printed_at = coalesce(bill_printed_at, now()), bill_prints = bill_prints + 1 WHERE id = :id", "id", orderId);
    }

    /** A catering bill is changed on the Catering page only, so its dishes, prices and discounts can't drift from the order. */
    public void requireNotCatering(UUID orderId, String what) {
        if (Boolean.TRUE.equals(db.value("SELECT exists(SELECT 1 FROM catering_orders WHERE order_id = :id)", Boolean.class, "id", orderId))) {
            throw ApiException.conflict("This bill belongs to a catering order, so you can't " + what + " here. Change it from the Catering page.");
        }
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
        requireNotCatering(orderId, "give a discount");
        BigDecimal subtotal = db.value("SELECT coalesce(sum(line_total), 0) FROM order_lines WHERE order_id = :id", BigDecimal.class, "id", orderId);
        if (discount.signum() > 0) {
            String by = approvals.require(approvalSource, "a discount");
            BigDecimal change = discount.subtract(current == null ? BigDecimal.ZERO : current);
            if (change.signum() > 0) {
                activity.record("DISCOUNT", bigDiscount(discount, subtotal) ? com.lezitech.suite.activity.ActivityLog.CONCERN
                                : com.lezitech.suite.activity.ActivityLog.NOTICE, "Discount of " + discount.toPlainString() + " on the whole bill"
                                + (subtotal.signum() > 0 ? " (" + discount.multiply(BigDecimal.valueOf(100)).divide(subtotal, 0, RoundingMode.HALF_UP) + "%)" : ""),
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
    /** A combo's chosen parts: exactly one choice from each part, mapped to the dish it names. */
    private List<UUID> comboParts(Map<String, Object> item, List<Map<String, Object>> picked) {
        com.fasterxml.jackson.databind.JsonNode groups = (com.fasterxml.jackson.databind.JsonNode) item.get("options");
        List<UUID> out = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode g : groups) {
            String group = g.path("name").asText();
            List<Map<String, Object>> mine = picked.stream().filter(p -> group.equals(p.get("group"))).toList();
            if (mine.isEmpty() && g.path("choices").size() == 1) {
                // A fixed part ("Beef burger"): nothing to choose.
                out.add(UUID.fromString(g.path("choices").get(0).path("itemId").asText()));
                continue;
            }
            if (mine.size() != 1) {
                throw ApiException.bad("Choose one " + group.toLowerCase() + " for the " + item.get("name"));
            }
            for (com.fasterxml.jackson.databind.JsonNode c : g.path("choices")) {
                if (c.path("name").asText().equals(mine.get(0).get("name"))) {
                    out.add(UUID.fromString(c.path("itemId").asText()));
                }
            }
        }
        return out;
    }

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
        return payInternal(orderId, b, false);
    }

    /**
     * A bill closed by the business itself, not money at a till: e.g. a bill still unpaid when a waiter clears, closed as
     * owed by that waiter (method WAITER). No till needs to be open.
     */
    public Map<String, Object> settleByServer(UUID orderId, String method, BigDecimal amount, String reference) {
        return payInternal(orderId, new Body(Map.of("payments", List.of(Map.of("method", method, "amount", amount, "reference", reference)))), true);
    }

    /**
     * Money paid ahead (a table booking's, a trip's, a stay's deposit) put on a bill: it counts towards the bill
     * but doesn't close it, and none of it is change. Only the business's own booking screens call this, and each
     * builds the method and amount itself. The paying endpoints use pay(), which ignores a "deposit" asked for in a
     * request: the flag switches off both the change calculation and the check that stops someone overpaying by
     * card or M-Pesa, so a request that could set it could overpay.
     */
    public Map<String, Object> depositByServer(UUID orderId, String method, BigDecimal amount, String reference) {
        if ("CASH".equalsIgnoreCase(method)) {
            // Cash over the counter has to land in somebody's till count, the same as a sale or a refund.
            tills.requireOpenTill();
        }
        return payInternal(orderId, new Body(Map.of("deposit", true,
                "payments", List.of(Map.of("method", method, "amount", amount, "reference", reference)))), true);
    }

    /** Points used on a bill (checked and recorded by LoyaltyService): a POINTS payment, closing the bill if that settles it. */
    public Map<String, Object> payWithPoints(UUID orderId, BigDecimal value, String reference) {
        return payInternal(orderId, new Body(Map.of("payments", List.of(Map.of("method", "POINTS", "amount", value, "reference", reference)))), true);
    }

    private Map<String, Object> payInternal(UUID orderId, Body b, boolean allowDeposit) {
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
        // Only a body the server built may call this a deposit: see depositByServer.
        boolean deposit = allowDeposit && b.bool("deposit", false);
        BigDecimal change = deposit ? BigDecimal.ZERO : sum.subtract(due).max(BigDecimal.ZERO);
        final BigDecimal changeDue = change;
        if (!deposit) {
            BigDecimal nonCash = payments.stream().filter(p -> !"CASH".equalsIgnoreCase(String.valueOf(p.get("method"))))
                    .map(p -> new Body(p).num("amount", BigDecimal.ZERO)).filter(x -> x.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (nonCash.compareTo(due) > 0) {
                throw ApiException.bad("That is more than the " + due.toPlainString() + " still to pay. Only cash can give change, so lower the card, M-Pesa or account amount");
            }
        }
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
            if (limit.signum() <= 0) {
                throw ApiException.bad(c.get("name") + " has no credit allowed, so they must pay at once. Raise their credit limit first if you want to put this on their account");
            }
            if (newBalance.compareTo(limit) > 0) {
                throw ApiException.bad(c.get("name") + " would go over their credit limit of " + limit.toPlainString());
            }
            db.exec("UPDATE customers SET balance = balance + :c WHERE id = :id", "id", customerId, "c", credit);
            customerLedger.record(UUID.fromString(String.valueOf(customerId)), "SALE", credit, orderId, null, null, null);
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
        for (Map<String, Object> l : db.list("SELECT item_id, qty * coalesce(pack_size, 1) AS qty FROM order_lines WHERE order_id = :id AND item_id IS NOT NULL", "id", orderId)) {
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
        voidOrder(orderId, reason, null);
    }

    /** Same, noting which manager approved it (null when a manager did it themselves, or the system). */
    public void voidOrder(UUID orderId, String reason, String approvedBy) {
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
        boolean printed = o.get("billPrintedAt") != null;
        activity.record("VOID", wentToKitchen || printed || ((BigDecimal) o.get("paid")).signum() > 0 ? com.lezitech.suite.activity.ActivityLog.CONCERN
                        : com.lezitech.suite.activity.ActivityLog.NOTICE,
                "Cancelled #" + o.get("number") + " worth " + ((BigDecimal) o.get("total")).toPlainString()
                        + (printed ? " after the bill was printed" : wentToKitchen ? " after it went to the kitchen" : "") + ": " + reason,
                orderId, null, (BigDecimal) o.get("total"), approvedBy, Map.of("reason", reason));
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
        for (Map<String, Object> l : db.list("SELECT item_id, qty * coalesce(pack_size, 1) AS qty FROM order_lines WHERE order_id = :id AND item_id IS NOT NULL", "id", orderId)) {
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
            if ("CASH".equals(back)) {
                // Cash going out must land in somebody's till count.
                tills.requireOpenTill();
            }
            db.exec("""
                    INSERT INTO payments (tenant_id, order_id, shift_id, method, amount, kind, refund_reason, user_id)
                    VALUES (:t, :order, :shift, :method, :amount, 'REFUND', :reason, :u)""",
                    "order", orderId, "shift", shift, "method", back, "amount", amount.negate(), "reason", reason);
        }
        if (toAccount.signum() > 0 && o.get("customerId") != null) {
            db.list("SELECT id FROM customers WHERE id = :id FOR UPDATE", "id", o.get("customerId"));
            db.exec("UPDATE customers SET balance = balance - :c WHERE id = :id", "id", o.get("customerId"), "c", toAccount);
            customerLedger.record(UUID.fromString(o.get("customerId").toString()), "REFUND", toAccount.negate(), orderId, null, null, reason);
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
        // If the customer had already seen the bill, the room folio knows it: otherwise moving a printed bill
        // onto a fresh folio would be a way to take items off it for nothing.
        db.exec("""
                UPDATE orders SET bill_printed_at = coalesce(bill_printed_at, (SELECT bill_printed_at FROM orders WHERE id = :id))
                WHERE id = :folio""", "id", orderId, "folio", folio);
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
                SELECT l.*, s.name AS staff_name FROM order_lines l LEFT JOIN users s ON s.id = l.staff_id AND s.tenant_id = :t
                WHERE l.order_id = :id ORDER BY l.course, l.created_at""", "id", orderId));
        o.put("payments", db.list("SELECT * FROM payments WHERE order_id = :id ORDER BY created_at", "id", orderId));
        return o;
    }
}
