package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A waiter clears at the end of their shift; what staff owe and how it's settled. */
@RestController
@RequestMapping("/api/waiter-clearing")
@Transactional
public class WaiterClearingController {

    private final WaiterClearing clearing;
    private final Db db;

    public WaiterClearingController(WaiterClearing clearing, Db db) {
        this.clearing = clearing;
        this.db = db;
    }

    /** My own shift so far (any staff member). */
    @GetMapping("/me")
    public Map<String, Object> mine() {
        return clearing.summary(TenantContext.userId());
    }

    /** Staff who served bills and haven't cleared, for the cashier's list. */
    @GetMapping("/waiting")
    public List<Map<String, Object>> waiting() {
        TenantContext.requireRole("CASHIER", "MANAGER", "ACCOUNTANT");
        return db.list("""
                SELECT u.id, u.name, u.role, count(o.id) AS bills, coalesce(sum(o.total), 0) AS sales
                FROM users u JOIN orders o ON coalesce(o.assigned_to, o.user_id) = u.id
                WHERE u.tenant_id = :t AND u.active AND o.kind = 'SALE' AND o.status NOT IN ('VOID', 'REFUNDED')
                  AND o.created_at >= greatest(now() - interval '16 hours',
                        coalesce((SELECT max(c.to_at) FROM waiter_clearings c WHERE c.waiter_id = u.id), now() - interval '16 hours'))
                  AND u.role IN ('WAITER', 'CASHIER', 'SALES')
                GROUP BY u.id, u.name, u.role ORDER BY u.name""");
    }

    @GetMapping("/{waiterId}")
    public Map<String, Object> summary(@PathVariable UUID waiterId) {
        if (!waiterId.equals(TenantContext.userId())) {
            TenantContext.requireRole("CASHIER", "MANAGER", "ACCOUNTANT");
        }
        return clearing.summary(waiterId);
    }

    /** The cashier or a manager counted the cash: {cashReceived, note}. */
    @PostMapping("/{waiterId}/clear")
    public Map<String, Object> clear(@PathVariable UUID waiterId, @RequestBody Map<String, Object> raw) {
        return clearing.clear(waiterId, new Body(raw));
    }

    @GetMapping("/owed")
    public Map<String, Object> owed() {
        return clearing.debts();
    }

    /** {amount, how: PAID_BACK | SALARY | WRITTEN_OFF, note}. */
    @PostMapping("/owed/{userId}/settle")
    public Map<String, Object> settle(@PathVariable UUID userId, @RequestBody Map<String, Object> raw) {
        return clearing.settle(userId, new Body(raw));
    }
}
