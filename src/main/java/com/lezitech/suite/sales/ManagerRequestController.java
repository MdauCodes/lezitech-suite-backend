package com.lezitech.suite.sales;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The "Waiting for you" list for managers, and the answers staff read back. */
@RestController
@RequestMapping("/api/approvals")
@Transactional
public class ManagerRequestController {

    private final Db db;
    private final ManagerRequestService requests;

    public ManagerRequestController(Db db, ManagerRequestService requests) {
        this.db = db;
        this.requests = requests;
    }

    /** Everything that needs a manager: staff requests, payments to check, and things flagged as a concern this week. */
    @GetMapping("/waiting")
    public Map<String, Object> waiting() {
        TenantContext.requireRole("MANAGER");
        List<Map<String, Object>> asks = requests.waiting();
        List<Map<String, Object>> payments = db.list("""
                SELECT o.id, o.number, o.channel, o.total, o.paid, o.closed_at, t.label AS table_label,
                       coalesce(o.assigned_to, o.user_id) AS waiter_id, u.name AS waiter_name
                FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id
                LEFT JOIN users u ON u.id = coalesce(o.assigned_to, o.user_id)
                WHERE o.needs_pay_check AND o.pay_checked_at IS NULL
                ORDER BY o.closed_at DESC NULLS LAST LIMIT 50""");
        List<Map<String, Object>> concerns = db.list("""
                SELECT * FROM activity_log WHERE severity = 'CONCERN' AND created_at > now() - interval '7 days'
                ORDER BY created_at DESC LIMIT 50""");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", asks);
        out.put("payments", payments);
        out.put("concerns", concerns);
        // The sidebar badge counts only what a manager has to answer. Payments to check and "worth a look"
        // are there to read, and counting a week of them would leave the badge permanently high and useless.
        out.put("count", asks.size());
        out.put("paymentsCount", payments.size());
        out.put("concernsCount", concerns.size());
        return out;
    }

    @PostMapping("/{id}/allow")
    public Map<String, Object> allow(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        Body b = new Body(raw == null ? Map.of() : raw);
        return requests.settle(id, true, b.str("answer", ""), false);
    }

    @PostMapping("/{id}/refuse")
    public Map<String, Object> refuse(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        Body b = new Body(raw == null ? Map.of() : raw);
        return requests.settle(id, false, b.str("answer", ""), b.bool("takeOver", false));
    }

    @GetMapping("/mine")
    public Map<String, Object> mine() {
        return Map.of("requests", requests.mine());
    }

    @PostMapping("/{id}/seen")
    public void seen(@PathVariable UUID id) {
        requests.markSeen(id);
    }
}
