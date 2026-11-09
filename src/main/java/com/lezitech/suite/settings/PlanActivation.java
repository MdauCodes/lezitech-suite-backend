package com.lezitech.suite.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.growth.Acquisition;
import com.lezitech.suite.growth.MetaConversions;
import com.lezitech.suite.growth.Referrals;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Turns a paid subscription request into an active plan. Used by the control panel and by M-Pesa. */
@Component
public class PlanActivation {

    private final JdbcTemplate jdbc;
    private final MetaConversions meta;
    private final Referrals referrals;
    private final ObjectMapper json;

    public PlanActivation(JdbcTemplate jdbc, MetaConversions meta, Referrals referrals, ObjectMapper json) {
        this.jdbc = jdbc;
        this.meta = meta;
        this.referrals = referrals;
        this.json = json;
    }

    /** @return the request row as it was before activation */
    public Map<String, Object> activate(UUID requestId, String paidVia, String receipt) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM subscription_requests WHERE id = ? FOR UPDATE", requestId);
        if ("PAID".equals(r.get("status"))) {
            return r;
        }
        UUID tenant = (UUID) r.get("tenant_id");
        Integer paidBefore = jdbc.queryForObject("SELECT count(*) FROM subscription_requests WHERE tenant_id = ? AND status = 'PAID'",
                Integer.class, tenant);
        int months = "ANNUAL".equals(r.get("billing_cycle")) ? 12 : 1;
        jdbc.update("""
                UPDATE tenants SET status = 'ACTIVE', plan = ?, paid_until = greatest(coalesce(paid_until, now()), now()) + make_interval(months => ?)
                WHERE id = ?""", r.get("plan"), months, tenant);
        jdbc.update("UPDATE subscription_requests SET status = 'PAID', paid_via = ?, mpesa_receipt = ? WHERE id = ?", paidVia, receipt, requestId);

        boolean first = paidBefore == null || paidBefore == 0;
        if (first) {
            referrals.rewardOnFirstPayment(tenant);
        }
        report(tenant, requestId, (BigDecimal) r.get("amount"), first);
        return r;
    }

    /** Tell Meta which ad's visitor paid: Subscribe on the first payment, Purchase (with the amount) on every payment. */
    private void report(UUID tenant, UUID requestId, BigDecimal amount, boolean first) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.phone, t.is_demo, t.acquisition::text AS acquisition,
                       (SELECT email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' ORDER BY u.created_at LIMIT 1) AS email
                FROM tenants t WHERE t.id = ?""", tenant);
        if (rows.isEmpty() || Boolean.TRUE.equals(rows.get(0).get("is_demo"))) {
            return;
        }
        Map<String, Object> t = rows.get(0);
        JsonNode acq = null;
        try {
            acq = t.get("acquisition") == null ? null : json.readTree((String) t.get("acquisition"));
        } catch (Exception ignored) {
            // no ad information; Meta still matches on email and phone
        }
        MetaConversions.Visitor v = Acquisition.visitor(acq, null);
        String email = (String) t.get("email");
        String phone = (String) t.get("phone");
        if (first) {
            meta.track("Subscribe", "sub-" + requestId, tenant, email, phone, v, amount);
        }
        meta.track("Purchase", "pay-" + requestId, tenant, email, phone, v, amount);
    }
}
