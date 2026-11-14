package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A food court in one QR (owner's decisions, 2026-11-14). The mall adds its food stalls, each with a menu and a kitchen
 * screen (a private link, no sign-up). A diner orders from several stalls in one basket and pays once by M-Pesa to the
 * mall; each stall sees only its part, marks it ready, and the diner collects at the stall when their number shows. The
 * mall pays each stall its sales less the mall's optional fee, and records it. Callers run inside the workspace's
 * transaction.
 */
@Service
public class FoodCourt {

    private final Db db;

    public FoodCourt(Db db) {
        this.db = db;
    }

    // ---------------- setting up (the mall) ----------------

    public List<Map<String, Object>> stalls() {
        List<Map<String, Object>> stalls = db.list("SELECT * FROM fc_stalls ORDER BY sort, name");
        for (Map<String, Object> s : stalls) {
            UUID id = UUID.fromString(s.get("id").toString());
            s.put("items", db.list("SELECT * FROM fc_items WHERE stall_id = :s ORDER BY sort, name", "s", id));
            s.putAll(money(id));
        }
        return stalls;
    }

    public Map<String, Object> saveStall(UUID id, Body b) {
        String name = b.str("name", "").trim();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiException.bad("Give the stall a name");
        }
        BigDecimal fee = b.num("feePct", BigDecimal.ZERO);
        if (fee.signum() < 0 || fee.compareTo(BigDecimal.valueOf(50)) > 0) {
            throw ApiException.bad("The mall's fee is a percentage between 0 and 50");
        }
        UUID lease = b.has("leaseId") && !b.str("leaseId", "").isBlank() ? UUID.fromString(b.str("leaseId")) : null;
        Object[] p = {"n", name, "f", fee, "l", lease, "po", b.str("payoutTo", null), "o", b.bool("open", true)};
        if (id == null) {
            Integer sort = db.value("SELECT coalesce(max(sort), 0) + 1 FROM fc_stalls", Integer.class);
            Object[] q = java.util.Arrays.copyOf(p, p.length + 2);
            q[p.length] = "sort";
            q[p.length + 1] = sort;
            id = db.insert("INSERT INTO fc_stalls (tenant_id, name, fee_pct, lease_id, payout_to, open, sort) VALUES (:t, :n, :f, :l, :po, :o, :sort) RETURNING id", q);
        } else {
            db.one("Stall", "SELECT id FROM fc_stalls WHERE id = :id", "id", id);
            Object[] q = java.util.Arrays.copyOf(p, p.length + 2);
            q[p.length] = "id";
            q[p.length + 1] = id;
            db.exec("UPDATE fc_stalls SET name = :n, fee_pct = :f, lease_id = :l, payout_to = :po, open = :o WHERE id = :id", q);
        }
        return db.one("Stall", "SELECT * FROM fc_stalls WHERE id = :id", "id", id);
    }

    public Map<String, Object> saveItem(UUID stallId, UUID itemId, Body b) {
        db.one("Stall", "SELECT id FROM fc_stalls WHERE id = :id", "id", stallId);
        String name = b.str("name", "").trim();
        BigDecimal price = b.num("price", BigDecimal.ZERO);
        if (name.isEmpty() || name.length() > 100 || price.signum() <= 0) {
            throw ApiException.bad("Give the dish a name and a price");
        }
        String desc = b.str("description", null);
        if (itemId == null) {
            Integer sort = db.value("SELECT coalesce(max(sort), 0) + 1 FROM fc_items WHERE stall_id = :s", Integer.class, "s", stallId);
            itemId = db.insert("INSERT INTO fc_items (tenant_id, stall_id, name, description, price, sort) VALUES (:t, :s, :n, :d, :p, :o) RETURNING id",
                    "s", stallId, "n", name, "d", desc == null || desc.isBlank() ? null : desc.trim(), "p", price, "o", sort);
        } else {
            db.exec("UPDATE fc_items SET name = :n, description = :d, price = :p, available = :a WHERE id = :id AND stall_id = :s",
                    "id", itemId, "s", stallId, "n", name, "d", desc == null || desc.isBlank() ? null : desc.trim(), "p", price, "a", b.bool("available", true));
        }
        return db.one("Dish", "SELECT * FROM fc_items WHERE id = :id", "id", itemId);
    }

    public void removeItem(UUID itemId) {
        db.exec("DELETE FROM fc_items WHERE id = :id", "id", itemId);
    }

    public String newScreenLink(UUID stallId) {
        db.exec("UPDATE fc_stalls SET screen_key = replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '') WHERE id = :id", "id", stallId);
        return db.value("SELECT screen_key FROM fc_stalls WHERE id = :id", String.class, "id", stallId);
    }

    /** Sales, the mall's fee, what's been paid to the stall and what's still to pay it. */
    public Map<String, Object> money(UUID stallId) {
        Map<String, Object> s = db.one("Stall", "SELECT fee_pct FROM fc_stalls WHERE id = :id", "id", stallId);
        BigDecimal fee = (BigDecimal) s.get("feePct");
        BigDecimal sales = db.value("""
                SELECT coalesce(sum(l.line_total), 0) FROM fc_order_lines l JOIN fc_orders o ON o.id = l.order_id
                WHERE l.stall_id = :s AND o.status IN ('PAID','DONE')""", BigDecimal.class, "s", stallId);
        BigDecimal today = db.value("""
                SELECT coalesce(sum(l.line_total), 0) FROM fc_order_lines l JOIN fc_orders o ON o.id = l.order_id
                WHERE l.stall_id = :s AND o.status IN ('PAID','DONE') AND o.day = cast(:d as date)""", BigDecimal.class, "s", stallId, "d", LeaseLedger.today());
        BigDecimal mallFee = sales.multiply(fee).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal paid = db.value("SELECT coalesce(sum(amount), 0) FROM fc_payouts WHERE stall_id = :s", BigDecimal.class, "s", stallId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("salesToday", today);
        out.put("salesAll", sales);
        out.put("mallFee", mallFee);
        out.put("paidToStall", paid);
        out.put("toPayStall", sales.subtract(mallFee).subtract(paid));
        return out;
    }

    /** Day by day for one stall (last 30 days): sales, the mall's fee and the stall's share. */
    public List<Map<String, Object>> days(UUID stallId) {
        Map<String, Object> s = db.one("Stall", "SELECT fee_pct FROM fc_stalls WHERE id = :id", "id", stallId);
        BigDecimal fee = (BigDecimal) s.get("feePct");
        List<Map<String, Object>> rows = db.list("""
                SELECT o.day, count(DISTINCT o.id) AS orders, sum(l.line_total) AS sales FROM fc_order_lines l JOIN fc_orders o ON o.id = l.order_id
                WHERE l.stall_id = :s AND o.status IN ('PAID','DONE') AND o.day >= cast(:from as date) GROUP BY o.day ORDER BY o.day DESC""",
                "s", stallId, "from", LeaseLedger.today().minusDays(30));
        for (Map<String, Object> r : rows) {
            BigDecimal sales = (BigDecimal) r.get("sales");
            BigDecimal f = sales.multiply(fee).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            r.put("mallFee", f);
            r.put("stallShare", sales.subtract(f));
        }
        return rows;
    }

    public Map<String, Object> payout(UUID stallId, Body b) {
        db.one("Stall", "SELECT id FROM fc_stalls WHERE id = :id", "id", stallId);
        BigDecimal amount = b.num("amount", BigDecimal.ZERO);
        String method = b.str("method", "MPESA").toUpperCase();
        if (amount.signum() <= 0) {
            throw ApiException.bad("Enter the amount paid to the stall");
        }
        if (!List.of("MPESA", "BANK", "CASH").contains(method)) {
            throw ApiException.bad("Choose how you paid them");
        }
        db.exec("INSERT INTO fc_payouts (tenant_id, stall_id, amount, method, reference, paid_on, user_id) VALUES (:t, :s, :a, :m, :r, :d, :u)",
                "s", stallId, "a", amount, "m", method, "r", b.str("reference", null), "d", b.has("paidOn") ? b.date("paidOn") : LeaseLedger.today(),
                "u", TenantContext.userId());
        return money(stallId);
    }

    public List<Map<String, Object>> todaysOrders() {
        List<Map<String, Object>> orders = db.list("SELECT * FROM fc_orders WHERE day = cast(:d as date) ORDER BY number DESC", "d", LeaseLedger.today());
        for (Map<String, Object> o : orders) {
            o.remove("token");
            o.put("lines", db.list("""
                    SELECT l.name, l.qty, l.line_total, l.status, s.name AS stall FROM fc_order_lines l JOIN fc_stalls s ON s.id = l.stall_id
                    WHERE l.order_id = :o ORDER BY s.name""", "o", o.get("id")));
        }
        return orders;
    }

    // ---------------- diners ----------------

    public List<Map<String, Object>> menu() {
        List<Map<String, Object>> stalls = db.list("SELECT id, name FROM fc_stalls WHERE open ORDER BY sort, name");
        for (Map<String, Object> s : stalls) {
            s.put("items", db.list("SELECT id, name, description, price, available FROM fc_items WHERE stall_id = :s ORDER BY sort, name", "s", s.get("id")));
        }
        return stalls;
    }

    /** One basket over several stalls: prices from the menu (never the phone), open stalls and available dishes only. */
    public Map<String, Object> place(Body b) {
        List<Map<String, Object>> raw = b.list("lines");
        if (raw == null || raw.isEmpty()) {
            throw ApiException.bad("Your basket is empty");
        }
        if (raw.size() > 30) {
            throw ApiException.bad("That's a very big order; please order in two parts");
        }
        String phone = b.str("phone", "").replaceAll("\\s", "");
        if (phone.replaceAll("\\D", "").length() < 9) {
            throw ApiException.bad("Enter your M-Pesa phone number");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : raw) {
            Body x = new Body(r);
            int qty = x.integer("qty", 1);
            if (qty < 1 || qty > 20) {
                throw ApiException.bad("Check the quantities");
            }
            Map<String, Object> item = db.find("""
                    SELECT i.id, i.name, i.price, i.available, i.stall_id, s.open, s.name AS stall FROM fc_items i JOIN fc_stalls s ON s.id = i.stall_id WHERE i.id = :id""",
                    "id", UUID.fromString(x.str("itemId"))).orElseThrow(() -> ApiException.notFound("Dish"));
            if (!Boolean.TRUE.equals(item.get("available")) || !Boolean.TRUE.equals(item.get("open"))) {
                throw ApiException.conflict(item.get("name") + " from " + item.get("stall") + " isn't available right now");
            }
            BigDecimal lineTotal = ((BigDecimal) item.get("price")).multiply(BigDecimal.valueOf(qty));
            total = total.add(lineTotal);
            Map<String, Object> line = new LinkedHashMap<>(item);
            line.put("qty", qty);
            line.put("lineTotal", lineTotal);
            lines.add(line);
        }
        LocalDate day = LeaseLedger.today();
        Integer number = db.value("SELECT coalesce(max(number), 0) + 1 FROM fc_orders WHERE day = cast(:d as date)", Integer.class, "d", day);
        String table = b.str("table", "").trim();
        UUID id = db.insert("""
                INSERT INTO fc_orders (tenant_id, number, day, phone, table_label, total) VALUES (:t, :n, :d, :p, :tb, :tot) RETURNING id""",
                "n", number, "d", day, "p", phone, "tb", table.isEmpty() ? null : table.length() > 20 ? table.substring(0, 20) : table, "tot", total);
        for (Map<String, Object> l : lines) {
            db.exec("""
                    INSERT INTO fc_order_lines (tenant_id, order_id, stall_id, item_id, name, price, qty, line_total)
                    VALUES (:t, :o, :s, :i, :n, :p, :q, :lt)""",
                    "o", id, "s", l.get("stallId"), "i", l.get("id"), "n", l.get("name"), "p", l.get("price"), "q", l.get("qty"), "lt", l.get("lineTotal"));
        }
        return db.one("Order", "SELECT id, number, token, total, phone FROM fc_orders WHERE id = :id", "id", id);
    }

    /** The diner's order: number, payment, and each stall's part (getting ready / ready to collect / collected). */
    public Map<String, Object> track(String token) {
        Map<String, Object> o = db.one("Order", "SELECT id, number, total, status, table_label, created_at, receipt FROM fc_orders WHERE token = :t2", "t2", token);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Map<String, Object> s : db.list("""
                SELECT DISTINCT s.id, s.name FROM fc_order_lines l JOIN fc_stalls s ON s.id = l.stall_id WHERE l.order_id = :o ORDER BY s.name""", "o", o.get("id"))) {
            List<Map<String, Object>> items = db.list("SELECT name, qty, line_total, status FROM fc_order_lines WHERE order_id = :o AND stall_id = :s",
                    "o", o.get("id"), "s", s.get("id"));
            boolean allCollected = items.stream().allMatch(i -> "COLLECTED".equals(i.get("status")));
            boolean allReady = items.stream().allMatch(i -> !"NEW".equals(i.get("status")));
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("stall", s.get("name"));
            part.put("items", items);
            part.put("state", allCollected ? "COLLECTED" : allReady ? "READY" : "PREPARING");
            parts.add(part);
        }
        Map<String, Object> out = new LinkedHashMap<>(o);
        out.remove("id");
        out.put("orderId", o.get("id"));
        out.put("parts", parts);
        return out;
    }

    public UUID orderIdOfToken(String token) {
        return db.value("SELECT id FROM fc_orders WHERE token = :t2", UUID.class, "t2", token);
    }

    // ---------------- stall kitchen screens and the TV ----------------

    public Map<String, Object> stallByKey(UUID stallId, String key) {
        Map<String, Object> s = db.find("SELECT id, name, open FROM fc_stalls WHERE id = :id AND screen_key = :k", "id", stallId, "k", key == null ? "" : key)
                .orElseThrow(() -> ApiException.notFound("Screen"));
        return s;
    }

    /** A stall's screen: its part of each paid order today that isn't collected yet, oldest first, and its dishes. */
    public Map<String, Object> kitchen(UUID stallId) {
        List<Map<String, Object>> orders = db.list("""
                SELECT o.id, o.number, o.table_label, o.paid_at FROM fc_orders o
                WHERE o.status IN ('PAID','DONE') AND o.day >= cast(:d as date) - 1
                  AND EXISTS (SELECT 1 FROM fc_order_lines l WHERE l.order_id = o.id AND l.stall_id = :s AND l.status <> 'COLLECTED')
                ORDER BY o.paid_at""", "s", stallId, "d", LeaseLedger.today());
        for (Map<String, Object> o : orders) {
            o.put("lines", db.list("SELECT id, name, qty, status FROM fc_order_lines WHERE order_id = :o AND stall_id = :s", "o", o.get("id"), "s", stallId));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", orders);
        out.put("items", db.list("SELECT id, name, price, available FROM fc_items WHERE stall_id = :s ORDER BY sort, name", "s", stallId));
        return out;
    }

    /** The stall marks its part of an order ready (shows on the TV and the diner's phone) or collected. */
    public void mark(UUID stallId, UUID orderId, String state) {
        if (!List.of("READY", "COLLECTED").contains(state)) {
            throw ApiException.bad("Unknown step");
        }
        int n = db.exec("""
                UPDATE fc_order_lines SET status = :st, ready_at = coalesce(ready_at, now()), collected_at = CASE WHEN :st = 'COLLECTED' THEN now() ELSE collected_at END
                WHERE order_id = :o AND stall_id = :s AND status <> 'COLLECTED'
                  AND EXISTS (SELECT 1 FROM fc_orders o WHERE o.id = :o AND o.status IN ('PAID','DONE'))""", "st", state, "o", orderId, "s", stallId);
        if (n == 0) {
            throw ApiException.conflict("Nothing to update for this order");
        }
        db.exec("""
                UPDATE fc_orders SET status = 'DONE' WHERE id = :o AND status = 'PAID'
                  AND NOT EXISTS (SELECT 1 FROM fc_order_lines l WHERE l.order_id = :o AND l.status <> 'COLLECTED')""", "o", orderId);
    }

    public void setAvailable(UUID stallId, UUID itemId, boolean available) {
        db.exec("UPDATE fc_items SET available = :a WHERE id = :id AND stall_id = :s", "a", available, "id", itemId, "s", stallId);
    }

    /** The TV: order numbers ready to collect (and where), and those being prepared. */
    public Map<String, Object> board() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", db.list("""
                SELECT o.number, s.name AS stall, max(l.ready_at) AS ready_at FROM fc_order_lines l JOIN fc_orders o ON o.id = l.order_id
                JOIN fc_stalls s ON s.id = l.stall_id
                WHERE l.status = 'READY' AND o.day = cast(:d as date) GROUP BY o.number, s.name ORDER BY max(l.ready_at) DESC LIMIT 40""", "d", LeaseLedger.today()));
        out.put("preparing", db.list("""
                SELECT DISTINCT o.number FROM fc_order_lines l JOIN fc_orders o ON o.id = l.order_id
                WHERE l.status = 'NEW' AND o.status = 'PAID' AND o.day = cast(:d as date) ORDER BY o.number""", "d", LeaseLedger.today())
                .stream().map(r -> r.get("number")).toList());
        return out;
    }

    public boolean boardKeyOk(String key) {
        return Boolean.TRUE.equals(db.value("SELECT fc_board_key = :k FROM tenants WHERE id = :t", Boolean.class, "k", key == null ? "" : key));
    }

    public Map<String, Object> links() {
        Map<String, Object> t = db.one("Business", "SELECT slug, fc_board_key FROM tenants WHERE id = :t");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", "/fc/" + t.get("slug"));
        out.put("board", "/fc/" + t.get("slug") + "/board?k=" + t.get("fcBoardKey"));
        List<Map<String, Object>> kitchens = new ArrayList<>();
        for (Map<String, Object> s : db.list("SELECT id, name, screen_key FROM fc_stalls ORDER BY sort, name")) {
            kitchens.add(Map.of("stallId", s.get("id"), "name", s.get("name"), "link", "/fc/" + t.get("slug") + "/k/" + s.get("id") + "?k=" + s.get("screenKey")));
        }
        out.put("kitchens", kitchens);
        return out;
    }
}
