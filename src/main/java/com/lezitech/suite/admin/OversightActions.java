package com.lezitech.suite.admin;

import com.lezitech.suite.auth.LoginCodes;
import com.lezitech.suite.auth.TokenVersions;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.notify.TenantNotices;
import com.lezitech.suite.platform.Offers;
import com.lezitech.suite.platform.PlatformSettings;
import com.lezitech.suite.settings.PlanActivation;
import com.lezitech.suite.settings.PlanBilling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the team can do for one business from the control panel, and what happens to every business when LeziTech changes a plan price
 * or offer. Each action needs a short reason, is written to the platform audit with who and when, and tells the business (a notice in
 * the app, an email, and a WhatsApp text for the team to send) so the business always knows what changed about what it pays or gets.
 * The money itself is always done by the same functions the customer side uses (Offers, PlanBilling, PlanActivation).
 */
@Service
public class OversightActions {

    private static final Logger log = LoggerFactory.getLogger(OversightActions.class);

    private final JdbcTemplate jdbc;
    private final Oversight ov;
    private final TenantNotices notices;
    private final PlanBilling billing;
    private final PlanActivation activation;
    private final Offers offers;
    private final PlatformSettings platform;
    private final AdminService admin;
    private final LoginCodes loginCodes;
    private final Mailer mailer;
    private final TokenVersions tokens;

    public OversightActions(JdbcTemplate jdbc, Oversight ov, TenantNotices notices, PlanBilling billing, PlanActivation activation, Offers offers,
                            PlatformSettings platform, AdminService admin, LoginCodes loginCodes, Mailer mailer, TokenVersions tokens) {
        this.jdbc = jdbc;
        this.ov = ov;
        this.notices = notices;
        this.billing = billing;
        this.activation = activation;
        this.offers = offers;
        this.platform = platform;
        this.admin = admin;
        this.loginCodes = loginCodes;
        this.mailer = mailer;
        this.tokens = tokens;
    }

    // ---------- shared ----------

    /** The short reason every action needs. */
    public static String reason(Map<String, Object> raw) {
        String r = raw == null || raw.get("reason") == null ? "" : String.valueOf(raw.get("reason")).trim();
        if (r.length() < 3) {
            throw ApiException.bad("Say why, in a few words");
        }
        return Oversight.clip(r, 300);
    }

    /** The code or reference of a payment: 3 to 30 characters (the most the payment record holds). */
    static String code(String reference) {
        String r = reference == null ? "" : reference.trim().toUpperCase();
        if (r.length() < 3) {
            throw ApiException.bad("Type the M-Pesa code or reference of the payment");
        }
        if (r.length() > 30) {
            throw ApiException.bad("The code or reference can be at most 30 characters");
        }
        return r;
    }

    private static ApiException sameCode() {
        return ApiException.conflict("That code or reference was already recorded for a payment");
    }

    private Map<String, Object> tenant(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, slug, sector, status, plan, next_plan, in_setup, trial_ends_at, paid_until, suspended_from, suspended_reason, is_demo
                FROM tenants WHERE id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Workspace");
        }
        return rows.get(0);
    }

    private String cycleFor(UUID id) {
        List<String> c = jdbc.queryForList("SELECT billing_cycle FROM subscription_requests WHERE tenant_id = ? AND status = 'PAID' ORDER BY coalesce(paid_at, created_at) DESC LIMIT 1",
                String.class, id);
        return !c.isEmpty() && "ANNUAL".equals(c.get(0)) ? "ANNUAL" : "MONTHLY";
    }

    /** The next bill in words, as the business sees it (the same function): "Your next bill on 30 Nov is now KES 3,600." */
    private String billSentence(UUID id, boolean now) {
        Map<String, Object> bill = ov.asTenant(id, () -> billing.nextBill(id, cycleFor(id)));
        long total = ((Number) bill.get("total")).longValue();
        String date = (String) bill.get("dateText");
        if (Boolean.TRUE.equals(bill.get("trial"))) {
            return "When your free days end on " + date + ", your bill will be " + PlanBilling.kes(total) + ".";
        }
        return "Your next bill on " + date + " is " + (now ? "now " : "") + PlanBilling.kes(total) + ".";
    }

    private UUID tell(UUID id, String kind, String title, String body, String by) {
        try {
            return notices.send(id, kind, title, body, by);
        } catch (RuntimeException e) {
            log.warn("Could not tell {} about {}: {}", id, kind, e.toString());
            return null;
        }
    }

    private static String by(String adminName) {
        return "LeziTech (" + adminName + ")";
    }

    private String support() {
        return mailer.support().isBlank() ? "" : " on " + mailer.support();
    }

    // ---------- record a payment received another way ----------

    /**
     * A payment that came another way (cash, bank, M-Pesa to a till). It must equal the next bill, or say why it doesn't. It goes through the
     * same activation as an M-Pesa payment, so the period, the plan and the extra lines on the bill (proration, plan moves) are all settled once.
     */
    public Map<String, Object> recordPayment(UUID id, Map<String, Object> raw, UUID adminId, String adminName) {
        Body b = new Body(raw);
        String reason = reason(raw);
        String reference = code(b.str("reference", ""));
        BigDecimal amount = b.num("amount", BigDecimal.ZERO);
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
            throw ApiException.bad("Type the amount received in whole shillings");
        }
        String cycle = "ANNUAL".equalsIgnoreCase(b.str("cycle", "MONTHLY")) ? "ANNUAL" : "MONTHLY";
        String difference = b.str("differenceReason", "").trim();
        Map<String, Object> t = tenant(id);
        // A paused business may still pay: the money is kept and the period extended, but it stays paused until it is opened again.
        if (Boolean.TRUE.equals(t.get("in_setup"))) {
            throw ApiException.bad("This business is still being set up by LeziTech");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM subscription_requests WHERE status = 'PAID' AND upper(mpesa_receipt) = ?", Long.class, reference) > 0) {
            throw ApiException.conflict("That code or reference was already recorded for a payment");
        }
        String asked = b.str("plan", "").toUpperCase();
        if (!asked.isEmpty()) {
            billing.onlyKindPlan(id, asked); // a kind of business with its own plan: that plan, or the one it already pays on
        }
        if (!asked.isEmpty() && platform.plan(asked) == null) {
            throw ApiException.bad("Choose the plan");
        }
        Map<String, Object> done;
        try {
            done = recordInside(id, b, asked, cycle, amount, reference, reason, difference, adminName);
        } catch (DataIntegrityViolationException e) {
            throw sameCode();
        }
        return afterPayment(id, amount, reference, reason, difference, cycle, done, adminName);
    }

    private Map<String, Object> recordInside(UUID id, Body b, String asked, String cycle, BigDecimal amount, String reference, String reason, String difference, String adminName) {
        return ov.asTenant(id, () -> {
            String plan = asked.isEmpty() ? billing.billPlan(id) : asked;
            PlanBilling.Quote q = billing.quote(id, plan, cycle);
            boolean same = amount.compareTo(BigDecimal.valueOf(q.total())) == 0;
            if (!same && difference.length() < 3) {
                throw ApiException.bad("The next bill is " + PlanBilling.kes(q.total()) + ". Type why the amount is different").with("expected", q.total());
            }
            UUID rid = jdbc.queryForObject("""
                    INSERT INTO subscription_requests (tenant_id, plan, billing_cycle, amount, mpesa_phone, offer_id, credit_used, lines, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?) RETURNING id""", UUID.class,
                    id, plan, cycle, amount, b.str("phone", null), same ? q.price().offerId() : null, same ? BigDecimal.valueOf(q.price().credit()) : BigDecimal.ZERO,
                    billing.linesJson(q), same ? null : Oversight.clip("Different from the bill of " + PlanBilling.kes(q.total()) + ": " + difference, 300));
            jdbc.update("""
                    UPDATE subscription_requests SET status = 'SUPERSEDED', note = 'The payment was recorded another way.'
                    WHERE tenant_id = ? AND status = 'PENDING' AND id <> ?""", id, rid);
            activation.activate(rid, "MANUAL", reference, adminName, reason);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requestId", rid.toString());
            out.put("plan", plan);
            out.put("expected", q.total());
            out.put("matched", same);
            return out;
        });
    }

    private Map<String, Object> afterPayment(UUID id, BigDecimal amount, String reference, String reason, String difference, String cycle, Map<String, Object> done, String adminName) {
        Timestamp until = jdbc.queryForObject("SELECT paid_until FROM tenants WHERE id = ?", Timestamp.class, id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("amount", amount.longValue());
        detail.put("expected", done.get("expected"));
        detail.put("reference", reference);
        detail.put("plan", done.get("plan"));
        detail.put("cycle", cycle);
        detail.put("reason", reason);
        detail.put("differenceReason", difference.isEmpty() ? null : difference);
        detail.put("requestId", done.get("requestId"));
        admin.audit("workspace.record_payment", id, detail);
        String planName = billing.nameOf(id, (String) done.get("plan"));
        UUID notice = tell(id, "PAYMENT", "We recorded your payment",
                "LeziTech recorded your payment of " + PlanBilling.kes(amount.longValue()) + " (reference " + reference + ") for " + planName + ". Your plan is paid until "
                        + PlanBilling.day(until.toInstant()) + ". " + (isPaused(id) ? "Your workspace is still paused. Talk to us" + support() + " to open it again." : billSentence(id, false)), by(adminName));
        done.put("noticeId", notice == null ? null : notice.toString());
        done.put("paidUntil", until.toInstant().toString());
        return done;
    }

    private boolean isPaused(UUID id) {
        return "SUSPENDED".equals(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, id));
    }

    /** The billing queue: the operator saw the money for a waiting request. Same activation as an M-Pesa payment, with who and why kept. */
    public Map<String, Object> approveRequest(UUID requestId, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        String ref0 = new Body(raw).str("reference", "").trim();
        String reference = ref0.isEmpty() ? "" : code(ref0);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT tenant_id, status, plan, amount FROM subscription_requests WHERE id = ?", requestId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Request");
        }
        Map<String, Object> r = rows.get(0);
        UUID id = (UUID) r.get("tenant_id");
        if (!"PENDING".equals(r.get("status")) && !"EXPIRED".equals(r.get("status"))) {
            throw ApiException.conflict("This request was already settled");
        }
        if (!reference.isEmpty() && jdbc.queryForObject("SELECT count(*) FROM subscription_requests WHERE status = 'PAID' AND upper(mpesa_receipt) = ?", Long.class, reference) > 0) {
            throw ApiException.conflict("That code or reference was already recorded for a payment");
        }
        try {
            ov.asTenant(id, () -> activation.activate(requestId, "MANUAL", reference.isEmpty() ? null : reference, adminName, reason));
        } catch (DataIntegrityViolationException e) {
            throw sameCode();
        }
        admin.audit("billing.approve", id, Map.of("plan", String.valueOf(r.get("plan")), "amount", r.get("amount"), "reference", reference, "reason", reason, "request", requestId.toString()));
        Timestamp until = jdbc.queryForObject("SELECT paid_until FROM tenants WHERE id = ?", Timestamp.class, id);
        UUID notice = tell(id, "PAYMENT", "We recorded your payment",
                "LeziTech recorded your payment of " + PlanBilling.kes(((BigDecimal) r.get("amount")).longValue()) + (reference.isEmpty() ? "" : " (reference " + reference + ")")
                        + " for " + billing.nameOf(id, (String) r.get("plan")) + ". Your plan is paid until " + PlanBilling.day(until.toInstant()) + ". "
                        + (isPaused(id) ? "Your workspace is still paused. Talk to us" + support() + " to open it again." : billSentence(id, false)), by(adminName));
        return Map.of("ok", true, "tenantId", id.toString(), "noticeId", String.valueOf(notice));
    }

    public Map<String, Object> rejectRequest(UUID requestId, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT tenant_id, status, plan FROM subscription_requests WHERE id = ?", requestId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Request");
        }
        if (!"PENDING".equals(rows.get(0).get("status")) && !"EXPIRED".equals(rows.get(0).get("status"))) {
            throw ApiException.conflict("This request was already settled");
        }
        UUID id = (UUID) rows.get(0).get("tenant_id");
        jdbc.update("UPDATE subscription_requests SET status = 'REJECTED', note = ? WHERE id = ?", reason, requestId);
        admin.audit("billing.reject", id, Map.of("reason", reason, "request", requestId.toString()));
        UUID notice = tell(id, "REQUEST_REFUSED", "We could not confirm your payment request",
                "LeziTech could not confirm your payment request for " + billing.nameOf(id, (String) rows.get(0).get("plan")) + ". The reason we noted: " + reason
                        + ". Talk to us" + support() + " and we will sort it out.", by(adminName));
        return Map.of("ok", true, "noticeId", String.valueOf(notice));
    }

    // ---------- free days ----------

    /** Free days: more days on the free trial, or more days before a paying business's next bill. */
    public Map<String, Object> freeDays(UUID id, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        Integer days = new Body(raw).integer("days", null);
        if (days == null || days < 1 || days > 90) {
            throw ApiException.bad("Choose the number of free days (1 to 90)");
        }
        Map<String, Object> t = tenant(id);
        String status = (String) t.get("status");
        if ("SUSPENDED".equals(status) || Boolean.TRUE.equals(t.get("in_setup"))) {
            throw ApiException.bad("Free days can't be given while the business is paused or being set up");
        }
        Instant from, to;
        if ("ACTIVE".equals(status)) {
            from = t.get("paid_until") == null ? Instant.now() : ((Timestamp) t.get("paid_until")).toInstant();
            // One statement: the row is locked, a paused business is left alone, and a price the business was promised moves with the bill date.
            List<Timestamp> moved = jdbc.queryForList("""
                    WITH t AS (SELECT id, paid_until AS old_end, greatest(coalesce(paid_until, now()), now()) + make_interval(days => ?) AS new_end
                               FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE),
                         h AS (UPDATE tenant_price_holds h SET until_bill = h.until_bill + (t.new_end - t.old_end)
                               FROM t WHERE h.tenant_id = t.id AND t.old_end IS NOT NULL AND h.until_bill >= t.old_end),
                         u AS (UPDATE tenants SET paid_until = t.new_end FROM t WHERE tenants.id = t.id RETURNING tenants.paid_until)
                    SELECT paid_until FROM u""", Timestamp.class, days, id);
            if (moved.isEmpty()) {
                throw ApiException.conflict("This business changed while you were working. Look at it again and try again");
            }
            to = moved.get(0).toInstant();
        } else {
            from = ((Timestamp) t.get("trial_ends_at")).toInstant();
            List<Timestamp> moved = jdbc.queryForList("""
                    UPDATE tenants SET status = 'TRIAL', trial_ends_at = greatest(trial_ends_at, now()) + make_interval(days => ?)
                    WHERE id = ? AND status = ? RETURNING trial_ends_at""", Timestamp.class, days, id, status);
            if (moved.isEmpty()) {
                throw ApiException.conflict("This business changed while you were working. Look at it again and try again");
            }
            to = moved.get(0).toInstant();
        }
        admin.audit("workspace.free_days", id, Map.of("days", days, "reason", reason, "status", status, "from", from.toString(), "to", to.toString()));
        boolean paying = "ACTIVE".equals(status);
        UUID notice = tell(id, "FREE_DAYS", days + (days == 1 ? " free day" : " free days") + " from LeziTech",
                "LeziTech gave you " + days + (days == 1 ? " free day. " : " free days. ")
                        + (paying ? "Your next bill moves to " + PlanBilling.day(to) + ". " : "Your free days now end on " + PlanBilling.day(to) + ". ") + billSentence(id, false),
                by(adminName));
        return Map.of("ok", true, "days", days, "endsAt", to.toString(), "noticeId", String.valueOf(notice));
    }

    // ---------- offers for one business ----------

    public Map<String, Object> applyOffer(UUID id, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        tenant(id);
        Map<String, Object> o = offers.createFor(id, raw, adminName);
        admin.audit("workspace.offer_apply", id, Map.of("offer", o.get("id").toString(), "title", o.get("title"), "kind", o.get("kind"), "reason", reason));
        String until = PlanBilling.day(((Timestamp) o.get("ends_at")).toInstant().minusSeconds(60));
        String what = "PERCENT".equals(o.get("kind"))
                ? o.get("percent") + "% off your monthly payments for " + o.get("months") + (((Number) o.get("months")).intValue() == 1 ? " month" : " months")
                : billing.nameOf(id, (String) o.get("plan")) + " at " + PlanBilling.kes(((Number) o.get("price")).longValue()) + " a month";
        UUID notice = tell(id, "OFFER", "Good news: " + o.get("title"),
                "Good news: LeziTech gave you " + what + ", until " + until + ". " + billSentence(id, true), by(adminName));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("offerId", o.get("id").toString());
        out.put("noticeId", String.valueOf(notice));
        return out;
    }

    public Map<String, Object> endOffer(UUID id, UUID offerId, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        tenant(id);
        String title = offers.endFor(id, offerId);
        admin.audit("workspace.offer_end", id, Map.of("offer", offerId.toString(), "title", title, "reason", reason));
        UUID notice = tell(id, "OFFER_END", "Your offer has ended",
                "LeziTech ended your offer \"" + title + "\". " + billSentence(id, true), by(adminName));
        return Map.of("ok", true, "noticeId", String.valueOf(notice));
    }

    // ---------- plan ----------

    /** What the owner would see before the move (the same sheet wording). */
    public Map<String, Object> planPreview(UUID id, String plan) {
        tenant(id);
        return ov.asTenant(id, () -> billing.preview(id, null, null, 0, plan));
    }

    public Map<String, Object> changePlan(UUID id, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        String to = new Body(raw).str("plan", "");
        tenant(id);
        Map<String, Object> r = ov.asTenant(id, () -> billing.changeBy(id, to, "LeziTech: " + reason, by(adminName)));
        admin.audit("workspace.plan_change", id, Map.of("plan", to.toUpperCase(), "kind", String.valueOf(r.get("kind")), "reason", reason));
        UUID notice = tell(id, "PLAN", "LeziTech changed your plan",
                "LeziTech changed your plan. " + r.get("message") + " " + billSentence(id, false), by(adminName));
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("noticeId", String.valueOf(notice));
        return out;
    }

    // ---------- the operator console (X-Operator-Key): the same rules as the control panel ----------

    /** Activate a plan for 1 or 12 months for the operator console: a payment at the next bill's amount, with a reason, audit and notice. */
    public Map<String, Object> operatorActivate(UUID id, Map<String, Object> raw, String name) {
        reason(raw);
        Body b = new Body(raw);
        String plan = b.str("plan", "").toUpperCase();
        if (platform.plan(plan) == null) {
            throw ApiException.bad("Choose the plan");
        }
        billing.onlyKindPlan(id, plan);
        int months = b.integer("months", 1);
        if (months != 1 && months != 12) {
            throw ApiException.bad("Months must be 1 or 12");
        }
        String cycle = months == 12 ? "ANNUAL" : "MONTHLY";
        tenant(id);
        long total = ov.asTenant(id, () -> billing.quote(id, plan, cycle).total());
        Map<String, Object> m = new LinkedHashMap<>(raw);
        m.put("plan", plan);
        m.put("cycle", cycle);
        m.put("amount", total);
        if (b.str("reference", "").isBlank()) {
            m.put("reference", "OP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        return recordPayment(id, m, null, name);
    }

    // ---------- pause ----------

    public Map<String, Object> suspend(UUID id, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        Map<String, Object> t = tenant(id);
        if ("SUSPENDED".equals(t.get("status"))) {
            throw ApiException.bad("This business is already suspended");
        }
        // One statement: the status it was is taken from the row itself, and a second pause at the same time finds nothing to change.
        List<String> was = jdbc.queryForList("""
                UPDATE tenants SET suspended_from = status, status = 'SUSPENDED', suspended_reason = ?, suspended_at = now()
                WHERE id = ? AND status <> 'SUSPENDED' RETURNING suspended_from""", String.class, reason, id);
        if (was.isEmpty()) {
            throw ApiException.conflict("This business was just paused by someone else");
        }
        admin.audit("workspace.suspend", id, Map.of("reason", reason, "was", was.get(0)));
        UUID notice = tell(id, "SUSPENDED", "Your workspace is paused",
                "LeziTech paused your workspace. The reason we noted: " + reason + ". You can still look at everything and download your reports; your data is safe. "
                        + "Talk to us" + support() + " and we will sort it out together.", by(adminName));
        return Map.of("ok", true, "noticeId", String.valueOf(notice));
    }

    public Map<String, Object> unsuspend(UUID id, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        Map<String, Object> t = tenant(id);
        if (!"SUSPENDED".equals(t.get("status"))) {
            throw ApiException.bad("This business is not suspended");
        }
        // The status to go back to is taken inside the same statement that locks the row, never from the earlier read.
        List<String> back = jdbc.queryForList("""
                UPDATE tenants SET status = p.prev, suspended_from = NULL, suspended_reason = NULL, suspended_at = NULL,
                       trial_ends_at = CASE WHEN p.prev = 'TRIAL' THEN greatest(tenants.trial_ends_at, now() + interval '3 days') ELSE tenants.trial_ends_at END
                FROM (SELECT id, coalesce(suspended_from, CASE WHEN paid_until > now() THEN 'ACTIVE' ELSE 'TRIAL' END) AS prev
                      FROM tenants WHERE id = ? AND status = 'SUSPENDED' FOR UPDATE) p
                WHERE tenants.id = p.id RETURNING p.prev""", String.class, id);
        if (back.isEmpty()) {
            throw ApiException.conflict("This business changed while you were working. Look at it again and try again");
        }
        String prev = back.get(0);
        admin.audit("workspace.reactivate", id, Map.of("reason", reason, "backTo", prev));
        UUID notice = tell(id, "UNSUSPENDED", "Your workspace is back",
                "Your workspace is back on. Thank you for sorting it out with us. " + billSentence(id, false), by(adminName));
        return Map.of("ok", true, "noticeId", String.valueOf(notice));
    }

    // ---------- reminder now ----------

    /** Send the bill reminder (or the free-days reminder) now, from the same next-bill function the business sees. */
    public Map<String, Object> reminder(UUID id, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        Map<String, Object> t = tenant(id);
        if ("SUSPENDED".equals(t.get("status")) || Boolean.TRUE.equals(t.get("in_setup"))) {
            throw ApiException.bad("There is nothing to remind this business of while it is paused or being set up");
        }
        Map<String, Object> bill = ov.asTenant(id, () -> billing.nextBill(id, cycleFor(id)));
        boolean trial = Boolean.TRUE.equals(bill.get("trial"));
        StringBuilder body = new StringBuilder();
        body.append(trial ? "Your free days end on " + bill.get("dateText") + ". After that the plan that fits your setup is " + bill.get("planName") + ". "
                : "Your next bill is on " + bill.get("dateText") + ". ");
        body.append("It is made of: ");
        boolean firstLine = true;
        for (Object o : (List<?>) bill.get("lines")) {
            Map<?, ?> l = (Map<?, ?>) o;
            long a = ((Number) l.get("amount")).longValue();
            body.append(firstLine ? "" : "; ").append(l.get("label")).append(" ").append(a < 0 ? "minus " : "").append(PlanBilling.kes(Math.abs(a)));
            firstLine = false;
        }
        body.append(". Total: ").append(PlanBilling.kes(((Number) bill.get("total")).longValue())).append(". Open Billing and pay by M-Pesa when you are ready.");
        admin.audit("workspace.reminder", id, Map.of("kind", trial ? "TRIAL" : "BILL", "reason", reason));
        UUID notice = tell(id, "REMINDER", trial ? "Your free days end on " + bill.get("dateText") : "Your next bill: " + PlanBilling.kes(((Number) bill.get("total")).longValue()) + " on " + bill.get("dateText"),
                body.toString(), by(adminName));
        return Map.of("ok", true, "kind", trial ? "TRIAL" : "BILL", "noticeId", String.valueOf(notice));
    }

    // ---------- the owner's sign-in ----------

    private Map<String, Object> owner(UUID id) {
        List<Map<String, Object>> owners = jdbc.queryForList("""
                SELECT u.id, u.name, u.email, u.job_title, t.name AS business, t.slug FROM users u JOIN tenants t ON t.id = u.tenant_id
                WHERE u.tenant_id = ? AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1""", id);
        if (owners.isEmpty()) {
            throw ApiException.conflict("This workspace has no active owner");
        }
        return owners.get(0);
    }

    /** Change the owner's email. The old and the new address are both told. */
    public Map<String, Object> changeOwnerEmail(UUID id, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        String email = new Body(raw).str("email", "").trim().toLowerCase();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.bad("That email doesn't look right");
        }
        Map<String, Object> o = owner(id);
        Object person = jdbc.queryForList("SELECT person_id FROM users WHERE id = ?", UUID.class, o.get("id")).stream().findFirst().orElse(null);
        if (person != null && !person.equals(o.get("id"))) {
            throw ApiException.conflict("This owner signs in with the email of their main business. Change it on that business instead.");
        }
        String old = (String) o.get("email");
        if (email.equals(old)) {
            throw ApiException.bad("That is already the owner's email");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT app_email_taken(cast(? as text), cast(? as uuid))", Boolean.class, email, o.get("id")))) {
            throw ApiException.conflict("That email is already in use");
        }
        jdbc.update("UPDATE users SET email = ?, email_confirmed_at = NULL WHERE id = ?", email, o.get("id"));
        // Whoever is signed in as the owner is signed out, in every business linked to this sign-in (V47: a second business has its own row).
        for (UUID linked : jdbc.queryForList("SELECT id FROM users WHERE id = ? OR person_id = ?", UUID.class, o.get("id"), o.get("id"))) {
            tokens.bump(linked, false);
            jdbc.update("UPDATE login_codes SET used_at = now() WHERE user_id = ? AND used_at IS NULL", linked);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", old);
        detail.put("to", email);
        detail.put("reason", reason);
        admin.audit("workspace.owner_email", id, detail);
        String business = (String) o.get("business");
        String first = ((String) o.get("name")).trim().split("\\s+")[0];
        String text = "Hi " + first + ",\n\nLeziTech changed the email you sign in to " + business + " with, from " + old + " to " + email
                + ". If you did not ask for this, talk to us right away" + support() + ".\n\nThank you,\nLeziTech";
        String html = com.lezitech.suite.notify.EmailLayout.h("Your sign-in email was changed", null)
                + com.lezitech.suite.notify.EmailLayout.p("Hi " + Mailer.esc(first) + ",")
                + com.lezitech.suite.notify.EmailLayout.p("LeziTech changed the email you sign in to <b>" + Mailer.esc(business) + "</b> with, from <b>" + Mailer.esc(old)
                + "</b> to <b>" + Mailer.esc(email) + "</b>.")
                + com.lezitech.suite.notify.EmailLayout.p("If you did not ask for this, talk to us right away" + Mailer.esc(support()) + ".");
        String oldStatus = "NO_OLD_ADDRESS";
        if (old != null && !old.isBlank()) {
            oldStatus = mailer.send(old, (String) o.get("name"), "Busynez: your sign-in email was changed", text, html);
            if ("FAILED".equals(oldStatus)) {
                oldStatus = mailer.send(old, (String) o.get("name"), "Busynez: your sign-in email was changed", text, html); // one more try
            }
        }
        if (old != null && !old.isBlank() && !"SENT".equals(oldStatus)) {
            // The old address was not told: flag it for the team, who tell the owner another way.
            jdbc.update("INSERT INTO tenant_notes (tenant_id, admin_name, note) VALUES (?, 'System', ?)", id,
                    "The owner email was changed from " + old + " to " + email + ", but the email to the old address did not go (" + oldStatus.toLowerCase().replace('_', ' ')
                            + "). Tell the owner another way, for example on the phone.");
            Map<String, Object> notTold = new LinkedHashMap<>();
            notTold.put("old", old);
            notTold.put("status", oldStatus);
            admin.audit("workspace.owner_email_old_not_told", id, notTold);
        }
        UUID notice = tell(id, "OWNER_EMAIL", "Your sign-in email was changed",
                "LeziTech changed the email you sign in with, from " + old + " to " + email + ". If you did not ask for this, talk to us right away" + support() + ".", by(adminName));
        String newStatus = notice == null ? "FAILED"
                : ov.asTenant(id, () -> jdbc.queryForList("SELECT email_status FROM tenant_notices WHERE id = ?", String.class, notice)).stream().findFirst().orElse("FAILED");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("oldAddressEmail", oldStatus);
        out.put("newAddressEmail", newStatus);
        out.put("bothTold", "SENT".equals(oldStatus) && "SENT".equals(newStatus));
        out.put("noticeId", String.valueOf(notice));
        return out;
    }

    /** A fresh sign-in (set password) code and link for the owner. */
    public Map<String, Object> signInCode(UUID id, Map<String, Object> raw) {
        String reason = reason(raw);
        Map<String, Object> o = owner(id);
        String email = (String) o.get("email");
        if (email == null || email.endsWith(".suite.local")) {
            throw ApiException.bad("The owner has no email yet. Change the owner email first");
        }
        String status = loginCodes.sendInvite((UUID) o.get("id"), email, (String) o.get("name"), (String) o.get("business"), (String) o.get("slug"),
                (String) o.get("job_title"), "LeziTech", mailer.appUrl());
        admin.audit("workspace.signin_code", id, Map.of("email", status, "reason", reason));
        return Map.of("ok", true, "emailStatus", status, "message", "Code and link sent to " + email);
    }

    // ---------- when LeziTech changes a plan price or an offer for everyone ----------

    /** What each plan costs a month right now: its normal price, or the plan offer's price while one is live. */
    public Map<String, Long> effectivePrices() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> p : platform.plans()) {
            String code = (String) p.get("code");
            Map<String, Object> live = platform.liveOffer(code);
            out.put(code, live != null ? ((Number) live.get("price")).longValue() : ((Number) p.get("monthly")).longValue());
        }
        return out;
    }

    /** The businesses a change to this plan's price reaches: with a kind id only that kind's, else every business without a plan of its own for its kind. */
    private List<UUID> onPlan(String code, String kind) {
        List<UUID> ids = new java.util.ArrayList<>(payingOn(code, kind));
        for (Map<String, Object> trial : jdbc.queryForList("SELECT id, sector, sub_sector FROM tenants WHERE NOT is_demo AND status = 'TRIAL' AND NOT in_setup")) {
            UUID id = (UUID) trial.get("id");
            if (!platform.inScope(platform.kindOf((String) trial.get("sector"), (String) trial.get("sub_sector")), kind, code)) {
                continue;
            }
            try {
                if (code.equals(ov.asTenant(id, () -> billing.billPlan(id)))) {
                    ids.add(id);
                }
            } catch (RuntimeException e) {
                log.debug("No plan for {}: {}", id, e.toString());
            }
        }
        return ids;
    }

    /** Paying (or paused paying) businesses on this plan, now or at their next bill, in scope as for {@link #onPlan}. */
    private List<UUID> payingOn(String code, String kind) {
        List<UUID> ids = new java.util.ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT t.id, t.sector, t.sub_sector FROM tenants t WHERE NOT t.is_demo AND""" + " " + com.lezitech.suite.platform.Offers.PAYING + """
                 AND t.paid_until IS NOT NULL AND (t.plan = ? OR t.next_plan = ?)""", code, code)) {
            if (platform.inScope(platform.kindOf((String) r.get("sector"), (String) r.get("sub_sector")), kind, code)) {
                ids.add((UUID) r.get("id"));
            }
        }
        return ids;
    }

    /**
     * LeziTech changed what plans cost (a price, or a plan offer switched on or off). A price that went up is kept at the old price for every
     * paying business until its bill after next (Offers.holdOldPrice), and the business is told now, weeks ahead (never less than 7 days).
     * A price that went down is good news and shows on the next bill at once. Returns how many businesses were told.
     */
    public int priceChanged(Map<String, Long> before, String by) {
        Map<String, Long> after = effectivePrices();
        int queued = 0;
        boolean any = false;
        for (String code : after.keySet()) {
            long was = before.getOrDefault(code, after.get(code)), now = after.get(code);
            if (was == now) {
                continue;
            }
            if (now > was) {
                offers.holdOldPrice(code, was); // the hold is part of the change itself: it is never left to the background
            }
            jdbc.update("INSERT INTO notice_jobs (kind, plan, was, now_price, by_name) VALUES ('PRICE', ?, ?, ?, ?)", code, was, now, by);
            queued += payingOn(code, null).size();
            any = true;
        }
        if (any) {
            startNoticeJobs();
        }
        return queued;
    }

    /**
     * LeziTech saved the plans for kinds of business. A kind whose price went up keeps its paying businesses at the old price until their
     * bill after next, exactly as for a platform price change, and only that kind's businesses are told. Returns how many were told.
     */
    public int kindPriceChanged(Map<String, Long> before, String by) {
        Map<String, Long> after = platform.kindEffectivePrices();
        int queued = 0;
        boolean any = false;
        for (String kind : after.keySet()) {
            long was = before.getOrDefault(kind, after.get(kind)), now = after.get(kind);
            if (was == now) {
                continue;
            }
            String code = (String) platform.kindPlans().get(kind).get("plan");
            if (now > was) {
                offers.holdOldPriceForKind(code, was, kind);
            }
            jdbc.update("INSERT INTO notice_jobs (kind, plan, was, now_price, by_name) VALUES ('PRICE', ?, ?, ?, ?)", PlatformSettings.kindToken(kind), was, now, by);
            queued += payingOn(code, kind).size();
            any = true;
        }
        if (any) {
            startNoticeJobs();
        }
        return queued;
    }

    private static final java.util.concurrent.Executor NOTICE_THREAD = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notice-jobs");
        t.setDaemon(true);
        return t;
    });

    /** Start sending now, in the background; the timer below picks up anything left over. */
    private void startNoticeJobs() {
        NOTICE_THREAD.execute(this::runNoticeJobs);
    }

    @Scheduled(fixedDelayString = "${suite.notices.jobs-every-ms:60000}", initialDelay = 90_000)
    public void noticeJobsTimer() {
        try {
            warnAboutOffersEnding();
            runNoticeJobs();
        } catch (RuntimeException e) {
            log.warn("Notice jobs failed: {}", e.toString());
        }
    }

    /** A plan offer that ends on its own date: affected businesses are told 7 days before, once. */
    public void warnAboutOffersEnding() {
        java.time.LocalDate today = java.time.LocalDate.now(Oversight.NAIROBI);
        boolean any = false;
        for (Map.Entry<String, Map<String, Object>> e : platform.planOffers().entrySet()) {
            Map<String, Object> o = e.getValue();
            Map<String, Object> live = platform.liveOffer(e.getKey(), today);
            Map<String, Object> plan = platform.plan(e.getKey());
            if (live == null || plan == null || o.get("endsOn") == null || String.valueOf(o.get("endsOn")).isBlank()) {
                continue;
            }
            java.time.LocalDate end = java.time.LocalDate.parse(String.valueOf(o.get("endsOn")));
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, end);
            if (days < 0 || days > 7) {
                continue;
            }
            any |= jdbc.update("INSERT INTO notice_jobs (kind, plan, was, now_price, ends_on, by_name) VALUES ('OFFER_ENDING', ?, ?, ?, ?, 'LeziTech') ON CONFLICT DO NOTHING",
                    e.getKey(), ((Number) live.get("price")).longValue(), ((Number) plan.get("monthly")).longValue(), java.sql.Date.valueOf(end)) > 0;
        }
        for (Map.Entry<String, Map<String, Object>> e : platform.kindPlans().entrySet()) {
            Map<String, Object> live = platform.liveKindOffer(e.getValue(), today);
            Object endsOn = live == null ? null : live.get("endsOn");
            if (live == null || endsOn == null || String.valueOf(endsOn).isBlank()) {
                continue;
            }
            java.time.LocalDate end = java.time.LocalDate.parse(String.valueOf(endsOn));
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, end);
            if (days < 0 || days > 7) {
                continue;
            }
            any |= jdbc.update("INSERT INTO notice_jobs (kind, plan, was, now_price, ends_on, by_name) VALUES ('OFFER_ENDING', ?, ?, ?, ?, 'LeziTech') ON CONFLICT DO NOTHING",
                    PlatformSettings.kindToken(e.getKey()), ((Number) live.get("price")).longValue(), ((Number) e.getValue().get("monthly")).longValue(), java.sql.Date.valueOf(end)) > 0;
        }
        if (any) {
            startNoticeJobs();
        }
    }

    /** Works through the waiting notice jobs, one at a time (two runs never take the same job). */
    public void runNoticeJobs() {
        while (true) {
            List<Map<String, Object>> job = jdbc.queryForList("""
                    UPDATE notice_jobs SET started_at = now()
                    WHERE id = (SELECT id FROM notice_jobs WHERE done_at IS NULL AND (started_at IS NULL OR started_at < now() - interval '10 minutes')
                                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                    RETURNING id, kind, plan, was, now_price, ends_on, by_name, told""");
            if (job.isEmpty()) {
                return;
            }
            Map<String, Object> j = job.get(0);
            int told = ((Number) j.get("told")).intValue();
            try {
                String code = (String) j.get("plan");
                long was = ((Number) j.get("was")).longValue(), now = ((Number) j.get("now_price")).longValue();
                String by = (String) j.get("by_name");
                String kind = platform.kindForToken(code);
                if (code.startsWith("K:") && kind == null) {
                    continue; // that kind has no plan of its own any more: nothing to tell
                }
                if (kind != null) {
                    code = (String) platform.kindPlans().get(kind).get("plan");
                }
                for (UUID id : onPlan(code, kind)) {
                    // A job taken up again (after 10 minutes, or a crash) skips the businesses it already told.
                    if (jdbc.update("INSERT INTO notice_job_sent (job_id, tenant_id) VALUES (?, ?) ON CONFLICT DO NOTHING", j.get("id"), id) == 0) {
                        continue;
                    }
                    try {
                        if ("OFFER_ENDING".equals(j.get("kind"))) {
                            told += offerEndingNotice(id, code, was, now, ((java.sql.Date) j.get("ends_on")).toLocalDate(), by) ? 1 : 0;
                        } else {
                            told += priceNotice(id, code, was, now, by) ? 1 : 0;
                        }
                    } catch (RuntimeException e) {
                        log.warn("Notice for {} failed: {}", id, e.toString());
                        jdbc.update("DELETE FROM notice_job_sent WHERE job_id = ? AND tenant_id = ?", j.get("id"), id); // not told: a retry may try again
                    }
                    jdbc.update("UPDATE notice_jobs SET started_at = now(), told = ? WHERE id = ?", told, j.get("id"));
                }
            } finally {
                jdbc.update("UPDATE notice_jobs SET done_at = now(), told = ? WHERE id = ?", told, j.get("id"));
            }
        }
    }

    /** A paying business, or one that pays and is paused for now. */
    private static boolean payingOrPaused(Map<String, Object> t) {
        return t.get("paid_until") != null && ("ACTIVE".equals(t.get("status")) || "SUSPENDED".equals(t.get("status")) && "ACTIVE".equals(t.get("suspended_from")));
    }

    private boolean offerEndingNotice(UUID id, String code, long special, long normal, java.time.LocalDate end, String by) {
        Map<String, Object> t = tenant(id);
        if (!payingOrPaused(t)) {
            return false; // free days: the first bill is at the price then
        }
        String planName = billing.nameOf(id, code);
        String last = PlanBilling.day(end.atStartOfDay(Oversight.NAIROBI).toInstant());
        tell(id, "OFFER_ENDING", "The special price for " + planName + " ends on " + last,
                "The special price for " + planName + " (" + PlanBilling.kes(special) + " a month) ends on " + last + ". After that " + planName + " is " + PlanBilling.kes(normal)
                        + " a month. " + billSentence(id, false), by);
        return true;
    }

    private boolean priceNotice(UUID id, String code, long was, long now, String by) {
        Map<String, Object> t = tenant(id);
        String planName = billing.nameOf(id, code);
        boolean paying = payingOrPaused(t);
        if (now > was) {
            if (!paying) {
                return false; // free days: the first bill is at the price then
            }
            Instant next = ((Timestamp) t.get("paid_until")).toInstant();
            int months = "ANNUAL".equals(cycleFor(id)) ? 12 : 1;
            Instant after2 = ZonedDateTime.ofInstant(next, Oversight.NAIROBI).plusMonths(months).toInstant();
            tell(id, "PRICE_UP", planName + " will cost more from " + PlanBilling.day(after2),
                    "From your bill on " + PlanBilling.day(after2) + ", " + planName + " moves from " + PlanBilling.kes(was) + " to " + PlanBilling.kes(now)
                            + " a month. Your next bill on " + PlanBilling.day(next) + " stays at the old price. " + billSentence(id, false)
                            + " We are telling you well ahead so you can plan.", by);
        } else {
            Map<String, Object> own = platform.kindPlan(id);
            Map<String, Object> live = own != null && code.equals(own.get("plan")) ? platform.liveKindOffer(own) : platform.liveOffer(code);
            String until = live != null && live.get("endsOn") != null && !String.valueOf(live.get("endsOn")).isBlank()
                    ? ", until " + PlanBilling.day(java.time.LocalDate.parse(String.valueOf(live.get("endsOn"))).plusDays(1).atStartOfDay(Oversight.NAIROBI).toInstant().minusSeconds(60)) : "";
            tell(id, "PRICE_DOWN", "Good news: " + planName + " costs less",
                    "Good news: " + planName + " is now " + PlanBilling.kes(now) + " a month (it was " + PlanBilling.kes(was) + ")" + until + ". " + billSentence(id, true), by);
        }
        return true;
    }

    /** A new offer for everyone (or a kind of business): every business it reaches is told. Many businesses are told in the background. */
    public int offerMade(Map<String, Object> offer, String by) {
        List<UUID> ids = offers.reached(offer);
        Runnable work = () -> ids.forEach(id -> {
            try {
                tellAboutOffer(id, offer, by);
            } catch (RuntimeException e) {
                log.warn("Offer notice for {} failed: {}", id, e.toString());
            }
        });
        if (ids.size() > 200) {
            Thread t = new Thread(work, "offer-notices");
            t.setDaemon(true);
            t.start();
        } else {
            work.run();
        }
        return ids.size();
    }

    private void tellAboutOffer(UUID id, Map<String, Object> o, String by) {
        String until = PlanBilling.day(((Timestamp) o.get("ends_at")).toInstant().minusSeconds(60));
        String kind = (String) o.get("kind");
        String what;
        if ("DAYS".equals(kind)) {
            Map<String, Object> t = tenant(id);
            tell(id, "FREE_DAYS", "Good news: " + o.get("title"),
                    "Good news: LeziTech added " + o.get("days") + " free days to your free days. They now end on "
                            + PlanBilling.day(((Timestamp) t.get("trial_ends_at")).toInstant()) + ". " + billSentence(id, false), by);
            return;
        }
        what = "PERCENT".equals(kind) ? o.get("percent") + "% off your monthly payments for " + o.get("months") + (((Number) o.get("months")).intValue() == 1 ? " month" : " months")
                : billing.nameOf(id, (String) o.get("plan")) + " at " + PlanBilling.kes(((Number) o.get("price")).longValue()) + " a month";
        tell(id, "OFFER", "Good news: " + o.get("title"), "Good news: LeziTech is giving you " + what + ", until " + until + ". " + billSentence(id, true), by);
    }
}
