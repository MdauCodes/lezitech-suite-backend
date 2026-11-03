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
                       t.label AS table_label
                FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id WHERE o.public_token = :token""", "token", token));
        List<Map<String, Object>> lines = db.list("""
                SELECT name, qty, line_total, options, kitchen_status FROM order_lines WHERE order_id = :id ORDER BY course, created_at""",
                "id", o.get("id"));
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

    public Map<String, Object> menu() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", db.list("SELECT id, name, color FROM categories ORDER BY sort, name"));
        out.put("items", db.list("""
                SELECT i.id, i.category_id, i.name, i.price, i.options, i.kind, i.duration_min,
                       (i.track_stock AND coalesce((SELECT sum(qty) FROM stock_levels s WHERE s.item_id = i.id), 0) <= 0) AS sold_out
                FROM items i WHERE i.active ORDER BY i.name"""));
        return out;
    }
}
