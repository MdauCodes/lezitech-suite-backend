package com.lezitech.suite.publicapi;

import com.lezitech.suite.common.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only queries behind the customer-facing screens. Callers wrap these in TenantContext.runAs. */
@Service
@Transactional(readOnly = true)
public class PublicService {

    private final Db db;

    public PublicService(Db db) {
        this.db = db;
    }

    /** Orders still in the kitchen, and orders ready to collect, for the TV board. */
    public Map<String, Object> board() {
        String base = """
                SELECT o.id, o.number, o.channel, coalesce(o.customer_label, t.label, split_part(c.name, ' ', 1)) AS label,
                       t.label AS table_label, o.ready_at, min(l.sent_at) AS sent_at
                FROM orders o JOIN order_lines l ON l.order_id = o.id
                LEFT JOIN dining_tables t ON t.id = o.table_id LEFT JOIN customers c ON c.id = o.customer_id
                WHERE o.status <> 'VOID' AND o.created_at > now() - interval '8 hours' AND l.sent_at IS NOT NULL""";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preparing", db.list(base + """

                GROUP BY o.id, t.label, c.name
                HAVING bool_or(l.kitchen_status IN ('NEW','PREPARING'))
                ORDER BY min(l.sent_at)"""));
        out.put("ready", db.list(base + """

                GROUP BY o.id, t.label, c.name
                HAVING NOT bool_or(l.kitchen_status IN ('NEW','PREPARING')) AND bool_or(l.kitchen_status = 'READY')
                ORDER BY o.ready_at DESC NULLS LAST"""));
        return out;
    }

    public Map<String, Object> track(UUID token) {
        Map<String, Object> o = new LinkedHashMap<>(db.one("Order", """
                SELECT o.id, o.number, o.kind, o.channel, o.status, o.total, o.paid, o.created_at, o.ready_at, o.closed_at,
                       o.pickup_code, o.held_for_payment, o.estimated_ready_at, o.customer_label, o.self_order, o.tip,
                       t.label AS table_label
                FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id WHERE o.public_token = :token""", "token", token));
        List<Map<String, Object>> lines = db.list("""
                SELECT l.id, l.name, l.qty, l.line_total, l.options, l.kitchen_status, l.substitute_status, l.rejected_reason,
                       s.name AS substitute_name, s.price AS substitute_price
                FROM order_lines l LEFT JOIN items s ON s.id = l.substitute_item_id
                WHERE l.order_id = :id ORDER BY l.course, l.created_at""",
                "id", o.get("id"));
        o.put("notices", db.list("""
                SELECT kind, message, created_at FROM order_notices
                WHERE order_id = :id AND kind IN ('ITEM_REMOVED', 'SUBSTITUTE_ACCEPTED') ORDER BY created_at""", "id", o.get("id")));
        o.remove("id");
        o.put("lines", lines);
        boolean cooking = lines.stream().anyMatch(l -> List.of("NEW", "PREPARING").contains(l.get("kitchenStatus")));
        boolean sent = lines.stream().anyMatch(l -> !"PENDING".equals(l.get("kitchenStatus")));
        boolean ready = sent && !cooking && lines.stream().anyMatch(l -> "READY".equals(l.get("kitchenStatus")));
        boolean served = sent && lines.stream().filter(l -> !"PENDING".equals(l.get("kitchenStatus")))
                .allMatch(l -> "SERVED".equals(l.get("kitchenStatus")));
        String stage = "VOID".equals(o.get("status")) ? "CANCELLED"
                : served ? "DONE" : ready ? "READY" : lines.stream().anyMatch(l -> "PREPARING".equals(l.get("kitchenStatus"))) ? "PREPARING"
                : sent ? "RECEIVED" : "OPEN";
        o.put("stage", stage);
        return o;
    }

    /** The table a QR code points at, so the menu can say "Table 5". */
    public Map<String, Object> table(UUID id) {
        return db.find("SELECT id, label FROM dining_tables WHERE id = :id", "id", id).orElseThrow(() -> com.lezitech.suite.common.ApiException.notFound("Table"));
    }

    /** The order behind a tracking link, if this dish is on it. */
    public UUID orderOfLine(UUID token, UUID lineId) {
        return db.value("""
                SELECT o.id FROM orders o JOIN order_lines l ON l.order_id = o.id
                WHERE o.public_token = :token AND l.id = :line""", UUID.class, "token", token, "line", lineId);
    }

    public Map<String, Object> menu() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", db.list("SELECT id, name, color FROM categories ORDER BY sort, name"));
        out.put("items", db.list("""
                SELECT i.id, i.category_id, i.name, i.price, i.options, i.kind, i.duration_min, i.photo_file_id, i.allergens, i.allergen_note,
                       ((i.track_stock AND coalesce((SELECT sum(qty) FROM stock_levels s WHERE s.item_id = i.id), 0) <= 0)
                        OR (i.unavailable_until IS NOT NULL AND i.unavailable_until > now())) AS sold_out
                FROM items i WHERE i.active AND i.kind <> 'SERVICE' ORDER BY i.name"""));
        return out;
    }
}
