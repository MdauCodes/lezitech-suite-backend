package com.lezitech.suite.sales;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@Transactional
public class OrderController {

    private final Db db;
    private final OrderService orders;
    private final ApprovalService approvals;

    public OrderController(Db db, OrderService orders, ApprovalService approvals) {
        this.db = db;
        this.orders = orders;
        this.approvals = approvals;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String kind,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to) {
        return db.list("""
                SELECT o.id, o.number, o.kind, o.channel, o.status, o.total, o.paid, o.created_at, o.closed_at,
                       t.label AS table_label, c.name AS customer_name, u.name AS user_name,
                       (SELECT string_agg(DISTINCT p.method, ', ') FROM payments p WHERE p.order_id = o.id) AS methods,
                       (SELECT count(*) FROM order_lines l WHERE l.order_id = o.id) AS line_count
                FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id LEFT JOIN customers c ON c.id = o.customer_id
                LEFT JOIN users u ON u.id = o.user_id
                WHERE (cast(:status as text) IS NULL OR o.status = cast(:status as text))
                  AND (cast(:kind as text) IS NULL OR o.kind = cast(:kind as text))
                  AND (cast(:q as text) IS NULL OR c.name ILIKE '%' || cast(:q as text) || '%' OR o.number::text = cast(:q as text))
                  AND (cast(:from as date) IS NULL OR o.created_at >= cast(:from as date))
                  AND (cast(:to as date) IS NULL OR o.created_at < cast(:to as date) + 1)
                ORDER BY o.created_at DESC LIMIT 500""",
                "status", blank(status), "kind", blank(kind), "q", blank(q), "from", from, "to", to);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return orders.detail(id);
    }

    /** Create a sale, bill or quotation. With payments in the body it's a one-step counter sale. */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        UUID id = orders.create(b);
        if (!b.list("payments").isEmpty() || b.has("discount")) {
            return orders.pay(id, b);
        }
        return orders.detail(id);
    }

    @PostMapping("/{id}/lines")
    public Map<String, Object> addLines(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        orders.addLinesFromTill(id, new Body(raw));
        return orders.detail(id);
    }

    /** Change quantity or remove a line that hasn't gone to the kitchen yet. */
    @PatchMapping("/{id}/lines/{lineId}")
    public Map<String, Object> updateLine(@PathVariable UUID id, @PathVariable UUID lineId, @RequestBody Map<String, Object> raw) {
        orders.requireOpen(id);
        Body b = new Body(raw);
        Map<String, Object> line = db.one("Line", "SELECT * FROM order_lines WHERE id = :lineId AND order_id = :id", "id", id, "lineId", lineId);
        if (!"PENDING".equals(line.get("kitchenStatus"))) {
            TenantContext.requireRole("MANAGER");
        }
        BigDecimal qty = b.num("qty", (BigDecimal) line.get("qty"));
        BigDecimal newDiscount = b.num("discount", (BigDecimal) line.get("discount"));
        if (newDiscount.signum() < 0) {
            throw com.lezitech.suite.common.ApiException.bad("A discount can't be below zero");
        }
        if (newDiscount.compareTo((BigDecimal) line.get("discount")) != 0) {
            approvals.require(b, "a discount");
            approvals.checkDiscountLimit(newDiscount, ((BigDecimal) line.get("unitPrice")).multiply(qty));
        }
        if (((BigDecimal) line.get("unitPrice")).multiply(qty).compareTo(newDiscount) < 0) {
            throw com.lezitech.suite.common.ApiException.bad("The discount is bigger than the price");
        }
        if (qty.signum() <= 0) {
            db.exec("DELETE FROM order_lines WHERE id = :lineId", "lineId", lineId);
        } else {
            db.exec("""
                    UPDATE order_lines SET qty = :qty, note = :note, discount = :discount,
                           line_total = round(unit_price * :qty - :discount, 2) WHERE id = :lineId""",
                    "lineId", lineId, "qty", qty, "note", b.str("note", (String) line.get("note")),
                    "discount", b.num("discount", (BigDecimal) line.get("discount")));
        }
        orders.recalc(id);
        return orders.detail(id);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        orders.requireOpen(id);
        Body b = new Body(raw);
        if (b.has("customerId") || raw.containsKey("customerId")) {
            db.exec("UPDATE orders SET customer_id = :c WHERE id = :id", "id", id,
                    "c", b.has("customerId") ? UUID.fromString(b.str("customerId")) : null);
        }
        if (b.has("tableId")) {
            db.exec("UPDATE orders SET table_id = :tableId WHERE id = :id", "id", id, "tableId", UUID.fromString(b.str("tableId")));
        }
        if (b.has("guests")) {
            db.exec("UPDATE orders SET guests = :g WHERE id = :id", "id", id, "g", b.integer("guests", null));
        }
        if (b.has("note")) {
            db.exec("UPDATE orders SET note = :n WHERE id = :id", "id", id, "n", b.str("note"));
        }
        if (b.has("discount")) {
            orders.setDiscount(id, b.num("discount"), b);
        }
        return orders.detail(id);
    }

    /** Fire pending lines to the kitchen/bar screens, optionally just one course. */
    @PostMapping("/{id}/send")
    public Map<String, Object> send(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        orders.requireOpen(id);
        Integer course = new Body(raw).integer("course", null);
        int n = db.exec("""
                UPDATE order_lines SET kitchen_status = 'NEW', sent_at = now()
                WHERE order_id = :id AND kitchen_status = 'PENDING' AND item_id IS NOT NULL
                  AND (cast(:course as int) IS NULL OR course = cast(:course as int))
                  AND EXISTS (SELECT 1 FROM items i WHERE i.id = order_lines.item_id AND (i.kind = 'MENU' OR i.station IS NOT NULL))""",
                "id", id, "course", course);
        orders.markReadyIfDone(id);
        Map<String, Object> out = new java.util.LinkedHashMap<>(orders.detail(id));
        out.put("sent", n);
        return out;
    }

    @PostMapping("/{id}/pay")
    public Map<String, Object> pay(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return orders.pay(id, new Body(raw));
    }

    /** Split by item: the chosen lines move to a new bill on the same table. */
    @PostMapping("/{id}/split")
    public Map<String, Object> split(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Map<String, Object> o = orders.requireOpen(id);
        @SuppressWarnings("unchecked")
        List<String> lineIds = (List<String>) raw.getOrDefault("lineIds", List.of());
        if (lineIds.isEmpty()) {
            throw ApiException.bad("Pick the items to move to the new bill");
        }
        Body nb = new Body(Map.of("channel", o.get("channel"), "allowSecondBill", true,
                "tableId", o.get("tableId") == null ? "" : o.get("tableId")));
        UUID newId = orders.create(nb);
        for (String lineId : lineIds) {
            db.exec("UPDATE order_lines SET order_id = :newId WHERE id = :lineId AND order_id = :id",
                    "newId", newId, "lineId", UUID.fromString(lineId), "id", id);
        }
        orders.recalc(id);
        orders.recalc(newId);
        return orders.detail(newId);
    }

    @PostMapping("/{id}/charge-to-stay")
    public Map<String, Object> chargeToStay(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        UUID folio = orders.chargeToStay(id, UUID.fromString(new Body(raw).str("stayId")));
        return orders.detail(folio);
    }

    @PostMapping("/{id}/void")
    public Map<String, Object> voidOrder(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Map<String, Object> o = db.one("Order", "SELECT status, paid FROM orders WHERE id = :id", "id", id);
        boolean hasKitchenWork = Boolean.TRUE.equals(db.value(
                "SELECT exists(SELECT 1 FROM order_lines WHERE order_id = :id AND kitchen_status <> 'PENDING')", Boolean.class, "id", id));
        if (!"OPEN".equals(o.get("status")) || hasKitchenWork) {
            TenantContext.requireRole("MANAGER");
        }
        orders.voidOrder(id, new Body(raw).str("reason", "No reason given"));
        return orders.detail(id);
    }

    /** Quotation accepted: it becomes an open sale/invoice ready for payment or account. */
    @PostMapping("/{id}/convert")
    public Map<String, Object> convert(@PathVariable UUID id) {
        orders.requireOpen(id);
        db.exec("UPDATE orders SET kind = 'SALE' WHERE id = :id AND kind = 'QUOTE'", "id", id);
        return orders.detail(id);
    }

    @DeleteMapping("/{id}")
    public void deleteEmpty(@PathVariable UUID id) {
        orders.requireOpen(id);
        db.exec("DELETE FROM orders WHERE id = :id AND NOT EXISTS (SELECT 1 FROM order_lines WHERE order_id = :id)", "id", id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
