package com.lezitech.suite.admin;

import com.lezitech.suite.auth.LoginCodes;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

    public OversightActions(JdbcTemplate jdbc, Oversight ov, TenantNotices notices, PlanBilling billing, PlanActivation activation, Offers offers,
                            PlatformSettings platform, AdminService admin, LoginCodes loginCodes, Mailer mailer) {
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
        String reference = b.str("reference", "").trim().toUpperCase();
        if (reference.length() < 3 || reference.length() > 40) {
            throw ApiException.bad("Type the M-Pesa code or reference of the payment");
        }
        BigDecimal amount = b.num("amount", BigDecimal.ZERO);
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
            throw ApiException.bad("Type the amount received in whole shillings");
        }
        String cycle = "ANNUAL".equalsIgnoreCase(b.str("cycle", "MONTHLY")) ? "ANNUAL" : "MONTHLY";
        String difference = b.str("differenceReason", "").trim();
        Map<String, Object> t = tenant(id);
        if ("SUSPENDED".equals(t.get("status"))) {
            throw ApiException.bad("This business is paused. Unpause it first, then record the payment");
        }
        if (Boolean.TRUE.equals(t.get("in_setup"))) {
            throw ApiException.bad("This business is still being set up by LeziTech");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM subscription_requests WHERE status = 'PAID' AND upper(mpesa_receipt) = ?", Long.class, reference) > 0) {
            throw ApiException.conflict("That code or reference was already recorded for a payment");
        }
        Map<String, Object> done = ov.asTenant(id, () -> {
            String plan = billing.billPlan(id);
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
                        + PlanBilling.day(until.toInstant()) + ". " + billSentence(id, false), by(adminName));
        done.put("noticeId", notice == null ? null : notice.toString());
        done.put("paidUntil", until.toInstant().toString());
        return done;
    }

    /** The billing queue: the operator saw the money for a waiting request. Same activation as an M-Pesa payment, with who and why kept. */
    public Map<String, Object> approveRequest(UUID requestId, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        String reference = new Body(raw).str("reference", "").trim().toUpperCase();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT tenant_id, status, plan, amount FROM subscription_requests WHERE id = ?", requestId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Request");
        }
        Map<String, Object> r = rows.get(0);
        UUID id = (UUID) r.get("tenant_id");
        if (!"PENDING".equals(r.get("status")) && !"EXPIRED".equals(r.get("status"))) {
            throw ApiException.conflict("This request was already settled");
        }
        if ("SUSPENDED".equals(tenant(id).get("status"))) {
            throw ApiException.bad("This business is paused. Unpause it first");
        }
        if (!reference.isEmpty() && jdbc.queryForObject("SELECT count(*) FROM subscription_requests WHERE status = 'PAID' AND upper(mpesa_receipt) = ?", Long.class, reference) > 0) {
            throw ApiException.conflict("That code or reference was already recorded for a payment");
        }
        ov.asTenant(id, () -> activation.activate(requestId, "MANUAL", reference.isEmpty() ? null : reference, adminName, reason));
        admin.audit("billing.approve", id, Map.of("plan", String.valueOf(r.get("plan")), "amount", r.get("amount"), "reference", reference, "reason", reason, "request", requestId.toString()));
        Timestamp until = jdbc.queryForObject("SELECT paid_until FROM tenants WHERE id = ?", Timestamp.class, id);
        UUID notice = tell(id, "PAYMENT", "We recorded your payment",
                "LeziTech recorded your payment of " + PlanBilling.kes(((BigDecimal) r.get("amount")).longValue()) + (reference.isEmpty() ? "" : " (reference " + reference + ")")
                        + " for " + billing.nameOf(id, (String) r.get("plan")) + ". Your plan is paid until " + PlanBilling.day(until.toInstant()) + ". " + billSentence(id, false), by(adminName));
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
            jdbc.update("UPDATE tenants SET paid_until = greatest(coalesce(paid_until, now()), now()) + make_interval(days => ?) WHERE id = ?", days, id);
            to = jdbc.queryForObject("SELECT paid_until FROM tenants WHERE id = ?", Timestamp.class, id).toInstant();
        } else {
            from = ((Timestamp) t.get("trial_ends_at")).toInstant();
            jdbc.update("UPDATE tenants SET status = 'TRIAL', trial_ends_at = greatest(trial_ends_at, now()) + make_interval(days => ?) WHERE id = ?", days, id);
            to = jdbc.queryForObject("SELECT trial_ends_at FROM tenants WHERE id = ?", Timestamp.class, id).toInstant();
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

    // ---------- pause ----------

    public Map<String, Object> suspend(UUID id, Map<String, Object> raw, String adminName) {
        String reason = reason(raw);
        Map<String, Object> t = tenant(id);
        if ("SUSPENDED".equals(t.get("status"))) {
            throw ApiException.bad("This business is already suspended");
        }
        jdbc.update("UPDATE tenants SET status = 'SUSPENDED', suspended_from = ?, suspended_reason = ?, suspended_at = now() WHERE id = ?", t.get("status"), reason, id);
        admin.audit("workspace.suspend", id, Map.of("reason", reason, "was", String.valueOf(t.get("status"))));
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
        String prev = t.get("suspended_from") != null ? (String) t.get("suspended_from")
                : t.get("paid_until") != null && ((Timestamp) t.get("paid_until")).toInstant().isAfter(Instant.now()) ? "ACTIVE" : "TRIAL";
        jdbc.update("""
                UPDATE tenants SET status = ?, suspended_from = NULL, suspended_reason = NULL, suspended_at = NULL,
                       trial_ends_at = CASE WHEN ? = 'TRIAL' THEN greatest(trial_ends_at, now() + interval '3 days') ELSE trial_ends_at END
                WHERE id = ?""", prev, prev, id);
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
        String old = (String) o.get("email");
        if (email.equals(old)) {
            throw ApiException.bad("That is already the owner's email");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT app_email_taken(cast(? as text), cast(? as uuid))", Boolean.class, email, o.get("id")))) {
            throw ApiException.conflict("That email is already in use");
        }
        jdbc.update("UPDATE users SET email = ?, email_confirmed_at = NULL WHERE id = ?", email, o.get("id"));
        jdbc.update("UPDATE login_codes SET used_at = now() WHERE user_id = ? AND used_at IS NULL", o.get("id"));
        admin.audit("workspace.owner_email", id, Map.of("from", old, "to", email, "reason", reason));
        String business = (String) o.get("business");
        String first = ((String) o.get("name")).trim().split("\\s+")[0];
        String text = "Hi " + first + ",\n\nLeziTech changed the email you sign in to " + business + " with, from " + old + " to " + email
                + ". If you did not ask for this, talk to us right away" + support() + ".\n\nThank you,\nLeziTech";
        String html = com.lezitech.suite.notify.EmailLayout.h("Your sign-in email was changed", null)
                + com.lezitech.suite.notify.EmailLayout.p("Hi " + Mailer.esc(first) + ",")
                + com.lezitech.suite.notify.EmailLayout.p("LeziTech changed the email you sign in to <b>" + Mailer.esc(business) + "</b> with, from <b>" + Mailer.esc(old)
                + "</b> to <b>" + Mailer.esc(email) + "</b>.")
                + com.lezitech.suite.notify.EmailLayout.p("If you did not ask for this, talk to us right away" + Mailer.esc(support()) + ".");
        String oldStatus = mailer.send(old, (String) o.get("name"), "Busynez: your sign-in email was changed", text, html);
        UUID notice = tell(id, "OWNER_EMAIL", "Your sign-in email was changed",
                "LeziTech changed the email you sign in with, from " + old + " to " + email + ". If you did not ask for this, talk to us right away" + support() + ".", by(adminName));
        return Map.of("ok", true, "oldAddressEmail", oldStatus, "noticeId", String.valueOf(notice));
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

    private List<UUID> onPlan(String code) {
        List<UUID> ids = new java.util.ArrayList<>(jdbc.queryForList("""
                SELECT id FROM tenants WHERE NOT is_demo AND status = 'ACTIVE' AND paid_until IS NOT NULL AND (plan = ? OR next_plan = ?)""", UUID.class, code, code));
        for (UUID trial : jdbc.queryForList("SELECT id FROM tenants WHERE NOT is_demo AND status = 'TRIAL' AND NOT in_setup", UUID.class)) {
            try {
                if (code.equals(ov.asTenant(trial, () -> billing.billPlan(trial)))) {
                    ids.add(trial);
                }
            } catch (RuntimeException e) {
                log.debug("No plan for {}: {}", trial, e.toString());
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
        int told = 0;
        for (String code : after.keySet()) {
            long was = before.getOrDefault(code, after.get(code)), now = after.get(code);
            if (was == now) {
                continue;
            }
            if (now > was) {
                offers.holdOldPrice(code, was);
            }
            for (UUID id : onPlan(code)) {
                try {
                    Map<String, Object> t = tenant(id);
                    String planName = billing.nameOf(id, code);
                    boolean paying = "ACTIVE".equals(t.get("status")) && t.get("paid_until") != null;
                    if (now > was) {
                        if (!paying) {
                            continue; // free days: the first bill is at the price then
                        }
                        Instant next = ((Timestamp) t.get("paid_until")).toInstant();
                        int months = "ANNUAL".equals(cycleFor(id)) ? 12 : 1;
                        Instant after2 = ZonedDateTime.ofInstant(next, Oversight.NAIROBI).plusMonths(months).toInstant();
                        tell(id, "PRICE_UP", planName + " will cost more from " + PlanBilling.day(after2),
                                "From your bill on " + PlanBilling.day(after2) + ", " + planName + " moves from " + PlanBilling.kes(was) + " to " + PlanBilling.kes(now)
                                        + " a month. Your next bill on " + PlanBilling.day(next) + " stays at the old price. " + billSentence(id, false)
                                        + " We are telling you well ahead so you can plan.", by);
                    } else {
                        Map<String, Object> live = platform.liveOffer(code);
                        String until = live != null && live.get("endsOn") != null && !String.valueOf(live.get("endsOn")).isBlank()
                                ? ", until " + PlanBilling.day(java.time.LocalDate.parse(String.valueOf(live.get("endsOn"))).plusDays(1).atStartOfDay(Oversight.NAIROBI).toInstant().minusSeconds(60)) : "";
                        tell(id, "PRICE_DOWN", "Good news: " + planName + " costs less",
                                "Good news: " + planName + " is now " + PlanBilling.kes(now) + " a month (it was " + PlanBilling.kes(was) + ")" + until + ". " + billSentence(id, true), by);
                    }
                    told++;
                } catch (RuntimeException e) {
                    log.warn("Price change notice for {} failed: {}", id, e.toString());
                }
            }
        }
        return told;
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
