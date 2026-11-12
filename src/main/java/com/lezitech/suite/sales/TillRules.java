package com.lezitech.suite.sales;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Cash-drawer rules (owner decisions, 2026-11-11):
 * - a cashier opens their till with the money in the drawer (the float) before taking any payment;
 * - only the owner, managers and the accountant see what the till should hold (cashiers count blind);
 * - once the owner or a manager reconciles a till session, its sales are locked: no voids or refunds until the owner
 *   unlocks it.
 * Owners and managers may take a payment without a till of their own (they rarely work the counter). Diners paying
 * from their phone and M-Pesa results arriving later have no cashier, so the float rule doesn't apply to them.
 */
@Component
public class TillRules {

    /** Jobs that take payments at the counter and so must open a till first. */
    static final Set<String> TILL_ROLES = Set.of("CASHIER", "WAITER", "SALES", "RECEPTION", "STYLIST");

    /** Jobs that see expected amounts and differences, and reconcile. */
    static final Set<String> SEE_TOTALS = Set.of("OWNER", "MANAGER", "ACCOUNTANT");

    private final Db db;

    public TillRules(Db db) {
        this.db = db;
    }

    public static boolean seesTotals() {
        TenantContext.Caller c = TenantContext.callerOrNull();
        return c == null || SEE_TOTALS.contains(c.role());
    }

    /** Refuse a payment from a cashier who hasn't opened their till yet. */
    public void requireOpenTill() {
        TenantContext.Caller c = TenantContext.callerOrNull();
        if (c == null || c.userId() == null || !TILL_ROLES.contains(c.role())) {
            return;
        }
        if (db.value("SELECT count(*) FROM shifts WHERE user_id = :u AND status = 'OPEN'", Long.class) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "TILL_CLOSED",
                    "Start your till first: count the money in your drawer and enter it. Then you can take payments.");
        }
    }

    /** Refuse changing a sale that belongs to a reconciled (locked) till session. */
    public void requireUnlocked(UUID orderId, String what) {
        Boolean locked = db.value("""
                SELECT EXISTS (SELECT 1 FROM shifts s WHERE s.locked
                    AND (s.id = (SELECT shift_id FROM orders WHERE id = :o) OR s.id IN (SELECT shift_id FROM payments WHERE order_id = :o)))""",
                Boolean.class, "o", orderId);
        if (Boolean.TRUE.equals(locked)) {
            throw new ApiException(HttpStatus.CONFLICT, "TILL_LOCKED",
                    "This sale is in a till day that has been checked and locked, so it can't be " + what
                            + ". The owner can unlock that day under Till days.");
        }
    }
}
