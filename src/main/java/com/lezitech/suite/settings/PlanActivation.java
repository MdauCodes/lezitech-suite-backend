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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlanActivation.class);

    private final JdbcTemplate jdbc;
    private final MetaConversions meta;
    private final Referrals referrals;
    private final ObjectMapper json;

    private org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lezitech.suite.growth.MallOffer mallOffer;

    @org.springframework.beans.factory.annotation.Autowired
    private PlanBilling billing;

    @org.springframework.beans.factory.annotation.Autowired
    void events(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    public PlanActivation(JdbcTemplate jdbc, MetaConversions meta, Referrals referrals, ObjectMapper json) {
        this.jdbc = jdbc;
        this.meta = meta;
        this.referrals = referrals;
        this.json = json;
    }

    /** Plan payment requests still waiting after 48 hours are closed (a new request is made at the current price). Late money still activates by hand. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public int expireStale() {
        return jdbc.update("UPDATE subscription_requests SET status = 'EXPIRED' WHERE status = 'PENDING' AND created_at < now() - interval '48 hours'");
    }

    /** @return the request row as it was before activation */
    public Map<String, Object> activate(UUID requestId, String paidVia, String receipt) {
        return activate(requestId, paidVia, receipt, null, null);
    }

    /** As above, for a payment confirmed by a person at LeziTech: who did it and why are kept with the payment. */
    public Map<String, Object> activate(UUID requestId, String paidVia, String receipt, String by, String reason) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM subscription_requests WHERE id = ? FOR UPDATE", requestId);
        if ("PAID".equals(r.get("status"))) {
            return r;
        }
        UUID tenant = (UUID) r.get("tenant_id");
        if (!"PENDING".equals(r.get("status")) && !"EXPIRED".equals(r.get("status"))) {
            // A closed request (replaced, rejected) never gives a paid period. The money is flagged for the team to sort out by hand.
            log.warn("Payment for request {} ({}) not applied: the request is {}", requestId, tenant, r.get("status"));
            jdbc.update("INSERT INTO platform_audit (admin_name, action, tenant_id, detail) VALUES ('System', 'billing.payment_not_applied', ?, jsonb_build_object('request', ?::text, 'status', ?::text, 'receipt', ?::text))",
                    tenant, requestId.toString(), String.valueOf(r.get("status")), receipt);
            jdbc.update("INSERT INTO tenant_notes (tenant_id, admin_name, note) VALUES (?, 'System', ?)", tenant,
                    "A payment came in for a plan request that was already closed (" + String.valueOf(r.get("status")).toLowerCase() + "). No paid period was given. Check it and record the payment by hand if it is right.");
            return r;
        }
        Integer paidBefore = jdbc.queryForObject("SELECT count(*) FROM subscription_requests WHERE tenant_id = ? AND status = 'PAID' AND paid_via IS DISTINCT FROM 'CREDIT'",
                Integer.class, tenant);
        int months = "ANNUAL".equals(r.get("billing_cycle")) ? 12 : 1;
        // A move down that was chosen earlier starts on the bill date: paid ahead, the business keeps its plan until then.
        Map<String, Object> own = jdbc.queryForMap("SELECT status, plan, next_plan, plan_switch_at, paid_until FROM tenants WHERE id = ?", tenant);
        java.sql.Timestamp oldEnd = (java.sql.Timestamp) own.get("paid_until");
        java.time.Instant periodFrom = oldEnd != null && oldEnd.toInstant().isAfter(java.time.Instant.now()) ? oldEnd.toInstant() : java.time.Instant.now();
        boolean ahead = "ACTIVE".equals(own.get("status")) && oldEnd != null && oldEnd.toInstant().isAfter(java.time.Instant.now());
        boolean waits = ahead && own.get("next_plan") != null && own.get("next_plan").equals(r.get("plan")) && own.get("plan") != null && !own.get("plan").equals(r.get("plan"));
        // A request made for another plan than the one the business is on or moving to is out of date (it moved since): the money
        // still extends the period, but the plan is never set back to the old one.
        Object cur = own.get("plan"), nxt = own.get("next_plan");
        boolean stale = "ACTIVE".equals(own.get("status")) && cur != null && !cur.equals(r.get("plan")) && !r.get("plan").equals(nxt);
        jdbc.update("""
                UPDATE tenants SET status = CASE WHEN status = 'SUSPENDED' THEN 'SUSPENDED' ELSE 'ACTIVE' END,
                       suspended_from = CASE WHEN status = 'SUSPENDED' THEN 'ACTIVE' ELSE suspended_from END,
                       plan = ?, next_plan = ?, plan_switch_at = ?,
                       period_start = greatest(coalesce(paid_until, now()), now()),
                       paid_until = greatest(coalesce(paid_until, now()), now()) + make_interval(months => ?)
                WHERE id = ?""", stale ? cur : waits ? cur : r.get("plan"), stale ? nxt : waits ? nxt : null,
                stale ? own.get("plan_switch_at") : waits ? oldEnd : null, months, tenant);
        if ("SUSPENDED".equals(own.get("status"))) {
            // A paused business may still pay and we keep the money: the period is extended but it stays paused. Only "Open again" switches it on.
            jdbc.update("INSERT INTO platform_audit (admin_name, action, tenant_id, detail) VALUES ('System', 'workspace.paid_while_paused', ?, jsonb_build_object('request', ?::text, 'by', ?::text))", tenant, requestId.toString(), by);
            jdbc.update("INSERT INTO tenant_notes (tenant_id, admin_name, note) VALUES (?, 'System', ?)", tenant,
                    "Paid while paused: a payment of KES " + ((BigDecimal) r.get("amount")).toPlainString() + " was recorded and the paid period extended. The business is still paused until someone opens it again.");
        }
        // The extra lines this payment covered (days of a higher plan) are paid.
        billing.markPaid(tenant, requestId, (java.sql.Timestamp) r.get("created_at"));
        jdbc.update("UPDATE subscription_requests SET status = 'PAID', paid_via = ?, mpesa_receipt = ?, paid_at = now(), activated_by = ?, activated_reason = ?, period_from = ?, period_to = (SELECT paid_until FROM tenants WHERE id = ?) WHERE id = ?",
                paidVia, receipt, by, reason, java.sql.Timestamp.from(periodFrom), tenant, requestId);

        jdbc.update("UPDATE tenants SET billing_credit = greatest(0, billing_credit - ?) WHERE id = ?", r.get("credit_used"), tenant);
        // A plan switched on entirely by thank-you credit moved no money, so it is not a first payment (no rewards, no Subscribe).
        boolean first = !"CREDIT".equals(paidVia) && (paidBefore == null || paidBefore == 0);
        if (first) {
            referrals.rewardOnFirstPayment(tenant);
            if (mallOffer != null) {
                mallOffer.thankOnFirstPayment(tenant);
            }
        }
        report(tenant, requestId, (BigDecimal) r.get("amount"), first);
        // The receipt by email, with their invite link, once this payment is saved.
        if (events != null) {
            events.publishEvent(new PlanPaidEmail.PlanPaid(tenant, requestId));
        }
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
