package com.lezitech.suite.restaurant;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A waiter clears at the end of their shift: every bill they served since they last cleared, the cash they collected
 * (to hand over), M-Pesa already in, and bills still unpaid. The cashier or a manager counts the cash and confirms.
 * Unpaid bills and any cash short are recorded against the waiter, to be paid back or taken from their salary.
 */
@Service
public class WaiterClearing {

    private static final Set<String> SETTLE = Set.of("PAID_BACK", "SALARY", "WRITTEN_OFF");
    private static final Set<String> SEES_ALL = Set.of("OWNER", "MANAGER", "ACCOUNTANT", "CASHIER");

    private final Db db;
    private final OrderService orders;
    private final ActivityLog activity;

    public WaiterClearing(Db db, OrderService orders, ActivityLog activity) {
        this.db = db;
        this.orders = orders;
        this.activity = activity;
    }

    /** Where this shift starts: the waiter's last clearing, or 16 hours ago. */
    private Timestamp since(UUID waiter) {
        Timestamp last = db.value("SELECT max(to_at) FROM waiter_clearings WHERE waiter_id = :w", Timestamp.class, "w", waiter);
        Timestamp sixteen = Timestamp.from(Instant.now().minusSeconds(16 * 3600));
        return last == null || last.before(sixteen) ? sixteen : last;
    }

    /** The waiter's shift so far: bills, money by method, cash to hand over, unpaid bills. */
    public Map<String, Object> summary(UUID waiter) {
        Map<String, Object> w = db.one("Staff member", "SELECT id, name, role FROM users WHERE id = :id AND tenant_id = :t", "id", waiter);
        Timestamp since = since(waiter);
        Object[] p = {"w", waiter, "since", since};
        List<Map<String, Object>> bills = db.list("""
                SELECT o.id, o.number, o.status, o.total, o.paid, greatest(o.total - o.paid, 0) AS unpaid, o.created_at, o.customer_label,
                       t.label AS table_label,
                       coalesce((SELECT sum(amount) FROM payments x WHERE x.order_id = o.id AND x.method = 'MPESA'), 0) AS mpesa,
                       coalesce((SELECT sum(amount) FROM payments x WHERE x.order_id = o.id AND x.method = 'CASH'), 0) AS cash,
                       coalesce((SELECT sum(amount) FROM payments x WHERE x.order_id = o.id AND x.method = 'CARD'), 0) AS card
                FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id
                WHERE o.kind = 'SALE' AND o.status NOT IN ('VOID', 'REFUNDED') AND coalesce(o.assigned_to, o.user_id) = :w
                  AND o.created_at >= :since
                ORDER BY o.created_at""", p);
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal mpesa = BigDecimal.ZERO;
        BigDecimal card = BigDecimal.ZERO;
        BigDecimal unpaid = BigDecimal.ZERO;
        for (Map<String, Object> b : bills) {
            sales = sales.add((BigDecimal) b.get("total"));
            mpesa = mpesa.add((BigDecimal) b.get("mpesa"));
            card = card.add((BigDecimal) b.get("card"));
            if ("OPEN".equals(b.get("status"))) {
                unpaid = unpaid.add((BigDecimal) b.get("unpaid"));
            }
        }
        // The cash this waiter took in (on any bill), net of cash they gave back on refunds.
        BigDecimal cash = db.value("""
                SELECT coalesce(sum(amount), 0) FROM payments WHERE user_id = :w AND method = 'CASH' AND created_at >= :since""",
                BigDecimal.class, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("waiter", Map.of("id", w.get("id"), "name", w.get("name")));
        out.put("since", since.toInstant().toString());
        out.put("bills", bills);
        out.put("salesTotal", sales);
        out.put("mpesa", mpesa);
        out.put("card", card);
        out.put("cashToHandOver", cash);
        out.put("unpaidTotal", unpaid);
        out.put("owedBefore", balance(waiter));
        return out;
    }

    /**
     * The cashier or a manager clears a waiter: {cashReceived, note}. Unpaid bills close as owed by the waiter; any cash
     * short is recorded against them.
     */
    public Map<String, Object> clear(UUID waiter, Body b) {
        TenantContext.requireRole("CASHIER", "MANAGER");
        TenantContext.Caller me = TenantContext.caller();
        if (waiter.equals(me.userId()) && !"OWNER".equals(me.role())) {
            throw ApiException.forbidden("Another person (the cashier or a manager) must count your cash and clear you");
        }
        BigDecimal received = b.num("cashReceived", null);
        if (received == null || received.signum() < 0) {
            throw ApiException.bad("Enter the cash the waiter handed over");
        }
        Map<String, Object> s = summary(waiter);
        String name = (String) ((Map<?, ?>) s.get("waiter")).get("name");
        Timestamp from = Timestamp.from(Instant.parse((String) s.get("since")));
        UUID clearing = db.insert("""
                INSERT INTO waiter_clearings (tenant_id, waiter_id, cleared_by, from_at, to_at, bills, sales_total, mpesa, card,
                                              cash_expected, cash_received, short, unpaid_total, note)
                VALUES (:t, :w, :u, :from, now(), :bills, :sales, :mpesa, :card, :exp, :rec, :short, :unpaid, :note) RETURNING id""",
                "w", waiter, "from", from, "bills", ((List<?>) s.get("bills")).size(), "sales", s.get("salesTotal"),
                "mpesa", s.get("mpesa"), "card", s.get("card"), "exp", s.get("cashToHandOver"), "rec", received,
                "short", ((BigDecimal) s.get("cashToHandOver")).subtract(received), "unpaid", s.get("unpaidTotal"), "note", clip(b.str("note", null)));
        // Bills still unpaid: closed as owed by the waiter.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bills = (List<Map<String, Object>>) s.get("bills");
        for (Map<String, Object> bill : bills) {
            BigDecimal due = (BigDecimal) bill.get("unpaid");
            if ("OPEN".equals(bill.get("status")) && due.signum() > 0) {
                UUID order = UUID.fromString(bill.get("id").toString());
                orders.settleByServer(order, "WAITER", due, "Owed by " + name);
                db.exec("""
                        INSERT INTO staff_debts (tenant_id, user_id, kind, amount, order_id, clearing_id, note, by_user)
                        VALUES (:t, :w, 'UNPAID_BILL', :a, :o, :c, :note, :u)""",
                        "w", waiter, "a", due, "o", order, "c", clearing,
                        "note", "Bill #" + bill.get("number") + (bill.get("tableLabel") != null ? " (table " + bill.get("tableLabel") + ")" : "") + " unpaid at end of shift");
            }
        }
        BigDecimal short_ = ((BigDecimal) s.get("cashToHandOver")).subtract(received);
        if (short_.signum() > 0) {
            db.exec("""
                    INSERT INTO staff_debts (tenant_id, user_id, kind, amount, clearing_id, note, by_user)
                    VALUES (:t, :w, 'SHORTAGE', :a, :c, :note, :u)""",
                    "w", waiter, "a", short_, "c", clearing, "note", "Cash short at end of shift");
        }
        BigDecimal unpaid = (BigDecimal) s.get("unpaidTotal");
        if (short_.signum() > 0 || unpaid.signum() > 0) {
            activity.record("WAITER_CLEARED", ActivityLog.CONCERN, name + " cleared: " + (short_.signum() > 0 ? "cash short " + short_.toPlainString() : "")
                    + (short_.signum() > 0 && unpaid.signum() > 0 ? ", " : "") + (unpaid.signum() > 0 ? "unpaid bills " + unpaid.toPlainString() : "")
                    + ". Recorded against them.", null, null, short_.max(BigDecimal.ZERO).add(unpaid), null, Map.of());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clearingId", clearing);
        out.put("short", short_);
        out.put("unpaidTotal", unpaid);
        out.put("owedNow", balance(waiter));
        return out;
    }

    /** What a staff member owes now. */
    public BigDecimal balance(UUID user) {
        return db.value("SELECT coalesce(sum(amount), 0) FROM staff_debts WHERE user_id = :w", BigDecimal.class, "w", user);
    }

    /** Everyone's balance and entries (owner, managers, accountant, cashier); other staff see only their own. */
    public Map<String, Object> debts() {
        TenantContext.Caller me = TenantContext.caller();
        boolean all = SEES_ALL.contains(me.role());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staff", db.list("""
                SELECT u.id, u.name, u.role, sum(d.amount) AS owed,
                       sum(d.amount) FILTER (WHERE d.kind = 'SHORTAGE') AS shortages,
                       sum(d.amount) FILTER (WHERE d.kind = 'UNPAID_BILL') AS unpaid_bills
                FROM staff_debts d JOIN users u ON u.id = d.user_id
                WHERE (:all OR d.user_id = :u)
                GROUP BY u.id, u.name, u.role ORDER BY sum(d.amount) DESC, u.name""", "all", all));
        out.put("entries", db.list("""
                SELECT d.id, d.user_id, u.name, d.kind, d.amount, d.note, d.created_at, b.name AS by_name, o.number AS bill_number
                FROM staff_debts d JOIN users u ON u.id = d.user_id LEFT JOIN users b ON b.id = d.by_user
                LEFT JOIN orders o ON o.id = d.order_id
                WHERE (:all OR d.user_id = :u) ORDER BY d.created_at DESC LIMIT 300""", "all", all));
        out.put("clearings", db.list("""
                SELECT c.id, w.name AS waiter, cb.name AS cleared_by, c.bills, c.sales_total, c.mpesa, c.cash_expected, c.cash_received,
                       c.short, c.unpaid_total, c.created_at
                FROM waiter_clearings c JOIN users w ON w.id = c.waiter_id JOIN users cb ON cb.id = c.cleared_by
                WHERE (:all OR c.waiter_id = :u) ORDER BY c.created_at DESC LIMIT 60""", "all", all));
        return out;
    }

    /** {amount, how: PAID_BACK | SALARY | WRITTEN_OFF, note}: some or all of what a staff member owes is settled. */
    public Map<String, Object> settle(UUID user, Body b) {
        TenantContext.requireRole("MANAGER");
        String how = b.str("how", "").toUpperCase();
        if (!SETTLE.contains(how)) {
            throw ApiException.bad("Say how it was settled: paid back, taken from salary, or written off");
        }
        BigDecimal amount = b.num("amount", null);
        BigDecimal owed = balance(user);
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.bad("Enter the amount");
        }
        if (amount.compareTo(owed) > 0) {
            throw ApiException.bad("That's more than they have to pay back (" + owed.toPlainString() + ")");
        }
        db.exec("""
                INSERT INTO staff_debts (tenant_id, user_id, kind, amount, note, by_user) VALUES (:t, :w, :k, :a, :note, :u)""",
                "w", user, "k", how, "a", amount.negate(), "note", clip(b.str("note", null)));
        return Map.of("owed", balance(user));
    }

    private static String clip(String s) {
        return s == null ? null : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
