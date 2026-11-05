package com.lezitech.suite.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Turns a paid subscription request into an active plan. Used by the control panel and by M-Pesa. */
@Component
public class PlanActivation {

    private final JdbcTemplate jdbc;

    public PlanActivation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the request row as it was before activation */
    public Map<String, Object> activate(UUID requestId, String paidVia, String receipt) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM subscription_requests WHERE id = ? FOR UPDATE", requestId);
        if ("PAID".equals(r.get("status"))) {
            return r;
        }
        int months = "ANNUAL".equals(r.get("billing_cycle")) ? 12 : 1;
        jdbc.update("""
                UPDATE tenants SET status = 'ACTIVE', plan = ?, paid_until = greatest(coalesce(paid_until, now()), now()) + make_interval(months => ?)
                WHERE id = ?""", r.get("plan"), months, r.get("tenant_id"));
        jdbc.update("UPDATE subscription_requests SET status = 'PAID', paid_via = ?, mpesa_receipt = ? WHERE id = ?", paidVia, receipt, requestId);
        return r;
    }
}
