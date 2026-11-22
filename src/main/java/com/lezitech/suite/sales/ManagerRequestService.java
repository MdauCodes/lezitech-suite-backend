package com.lezitech.suite.sales;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Things a junior member of staff asks a manager to do (today: cancel a bill).
 * The bill is not touched when someone asks. It changes only when a manager allows it from "Waiting for you".
 */
@Service
@Transactional
public class ManagerRequestService {

    private final Db db;
    private final OrderService orders;
    private final ActivityLog activity;

    public ManagerRequestService(Db db, OrderService orders, ActivityLog activity) {
        this.db = db;
        this.orders = orders;
        this.activity = activity;
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * Ask a manager. Asking twice about the same bill does not make a second copy. If a different member of
     * staff asks about the same bill, they get their own ask, so they are told the answer too.
     */
    public UUID raise(String kind, UUID orderId, UUID deliveryId, String reason, String message) {
        UUID existing = db.value("""
                SELECT id FROM manager_requests WHERE order_id = :o AND kind = :k AND asked_by = :u AND done_at IS NULL
                ORDER BY asked_at LIMIT 1""", UUID.class, "o", orderId, "k", kind);
        if (existing != null) {
            return existing;
        }
        TenantContext.Caller me = TenantContext.caller();
        String name = me.name() == null ? "" : clip(me.name(), 120);
        String why = clip(reason, 200);
        String words = clip(message, 300);
        UUID id;
        try {
            id = db.insert("""
                    INSERT INTO manager_requests (tenant_id, order_id, delivery_id, kind, message, reason, asked_by, asked_by_name)
                    VALUES (:t, :o, :d, :k, :m, :r, :u, :n) RETURNING id""",
                    "o", orderId, "d", deliveryId, "k", kind, "m", words, "r", why, "n", name);
        } catch (org.springframework.dao.DuplicateKeyException race) {
            // They tapped twice at the same moment: keep the ask that got in first.
            UUID already = db.value("""
                    SELECT id FROM manager_requests WHERE order_id = :o AND kind = :k AND asked_by = :u AND done_at IS NULL
                    ORDER BY asked_at LIMIT 1""", UUID.class, "o", orderId, "k", kind);
            if (already != null) {
                return already;
            }
            throw race;
        }
        activity.record("APPROVAL_ASKED", ActivityLog.NOTICE, words, orderId, null, null, null,
                Map.of("requestId", id.toString(), "reason", why));
        return id;
    }

    /** Everything still waiting for a manager, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> waiting() {
        return db.list("""
                SELECT r.*, o.number AS order_number, o.total AS order_total, o.status AS order_status
                FROM manager_requests r LEFT JOIN orders o ON o.id = r.order_id
                WHERE r.done_at IS NULL ORDER BY r.asked_at DESC LIMIT 100""");
    }

    /** A manager answers. Allowing cancels the bill; refusing leaves it exactly as it is and needs a few words. */
    public Map<String, Object> settle(UUID id, boolean allow, String answer, boolean takeOver) {
        TenantContext.requireRole("MANAGER");
        TenantContext.Caller me = TenantContext.caller();
        Map<String, Object> r = db.one("Request", "SELECT * FROM manager_requests WHERE id = :id FOR UPDATE", "id", id);
        if (r.get("doneAt") != null) {
            throw ApiException.conflict("A manager has already dealt with this one");
        }
        String said = clip(answer, 200);
        if (!allow && said.isEmpty()) {
            throw ApiException.bad("Say why, so they know what to do next");
        }
        UUID orderId = r.get("orderId") == null ? null : UUID.fromString(r.get("orderId").toString());
        String myName = me.name() == null ? "" : me.name();
        if (allow && "CANCEL_BILL".equals(r.get("kind")) && orderId != null) {
            Map<String, Object> bill = db.one("Order", "SELECT status, paid FROM orders WHERE id = :id", "id", orderId);
            String status = (String) bill.get("status");
            if (!"OPEN".equals(status) && !"VOID".equals(status)) {
                throw ApiException.conflict("This bill has been paid or changed since they asked. Look at it before cancelling.");
            }
            // Money has come in since they asked. Cancelling now would pay it back out, which is a refund
            // and a separate decision, so stop and say so in words rather than moving the money quietly.
            java.math.BigDecimal paid = (java.math.BigDecimal) bill.get("paid");
            if (paid != null && paid.signum() > 0 && "OPEN".equals(status)) {
                throw ApiException.conflict("Money has been paid on this bill since they asked ("
                        + paid.stripTrailingZeros().toPlainString()
                        + "). Cancelling it now would give that money back, so deal with the bill yourself instead.");
            }
            orders.voidOrder(orderId, String.valueOf(r.get("reason")), myName);
        }
        db.exec("""
                UPDATE manager_requests SET done_at = now(), done_by = :u, done_by_name = :n, outcome = :o, answer = :a, taken_over = :x
                WHERE id = :id""", "id", id, "n", clip(myName, 120), "o", allow ? "ALLOWED" : "REFUSED",
                "a", said.isEmpty() ? null : said, "x", !allow && takeOver);
        activity.record("APPROVAL_ANSWERED", ActivityLog.NOTICE,
                myName + (allow ? " allowed: " : " said no to: ") + r.get("message") + (said.isEmpty() ? "" : " (" + said + ")"),
                orderId, null, null, null, Map.of("requestId", id.toString(), "outcome", allow ? "ALLOWED" : "REFUSED"));
        return db.one("Request", """
                SELECT r.*, o.number AS order_number, o.total AS order_total, o.status AS order_status
                FROM manager_requests r LEFT JOIN orders o ON o.id = r.order_id WHERE r.id = :id""", "id", id);
    }

    /** The caller's own requests: the ones still waiting, and answers they have not read yet. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine() {
        return db.list("""
                SELECT r.*, o.number AS order_number, o.status AS order_status
                FROM manager_requests r LEFT JOIN orders o ON o.id = r.order_id
                WHERE r.asked_by = :u AND (r.done_at IS NULL OR r.seen_at IS NULL)
                ORDER BY r.asked_at DESC LIMIT 100""");
    }

    /** The asker has read the answer. */
    public void markSeen(UUID id) {
        db.exec("UPDATE manager_requests SET seen_at = now() WHERE id = :id AND asked_by = :u AND seen_at IS NULL", "id", id);
    }
}
