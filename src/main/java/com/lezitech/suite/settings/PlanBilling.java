package com.lezitech.suite.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.platform.Offers;
import com.lezitech.suite.platform.PlanFeatures;
import com.lezitech.suite.platform.PlatformSettings;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The plain-words side of plans: what the next bill is made of, what moving to another plan does, and the record of it.
 * <ul>
 * <li>The next bill date is the paid-until date (for a free trial, its end). Its lines are the plan for the next period
 * at today's price ({@link Offers#price}: normal, plan offer, the business's own offers, lowest wins), minus any offer
 * and thank-you credit, plus the proration lines from moving up mid-period.</li>
 * <li>Moving up on a paid plan changes the plan now and adds (new monthly - old monthly) x days left / days in the
 * period, to the whole shilling, to the next bill. Nothing is charged at that moment. In the free days nothing is added.</li>
 * <li>Moving down starts at the next bill date. Nothing is refunded.</li>
 * <li>After the bill date there are {@value #GRACE_DAYS} days of grace before the workspace turns read-only.</li>
 * </ul>
 */
@Service
public class PlanBilling {

    public static final int GRACE_DAYS = com.lezitech.suite.config.TrialGuard.GRACE_DAYS;
    public static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(NAIROBI);

    /** One line of a bill: PLAN, OFFER, CREDIT or PRORATION; amounts are whole shillings (offers and credit negative). */
    public record Line(String kind, String label, long amount) {
        Map<String, Object> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("label", label);
            m.put("amount", amount);
            return m;
        }
    }

    /** What a plan payment costs: the price after offers, the extra lines, and the total charged. */
    public record Quote(Offers.Price price, String plan, String planName, String cycle, List<Line> lines, long extra, long total) {
    }

    private final JdbcTemplate jdbc;
    private final PlatformSettings platform;
    private final Offers offers;
    private final PlanFeatures features;
    private final ObjectMapper json;
    private final ActivityLog activity;

    public PlanBilling(JdbcTemplate jdbc, PlatformSettings platform, Offers offers, PlanFeatures features, ObjectMapper json, ActivityLog activity) {
        this.jdbc = jdbc;
        this.platform = platform;
        this.offers = offers;
        this.features = features;
        this.json = json;
        this.activity = activity;
    }

    // ---------- money and dates in words ----------

    public static String kes(long n) {
        return "KES " + String.format(Locale.ENGLISH, "%,d", n);
    }

    public static String day(Instant at) {
        return DAY.format(at);
    }

    /** The maths for moving up: the difference between the two monthly prices for the days left in the period, to the whole shilling. */
    public static long prorate(long oldMonthly, long newMonthly, long daysLeft, long daysInPeriod) {
        long diff = newMonthly - oldMonthly;
        if (diff <= 0 || daysLeft <= 0 || daysInPeriod <= 0) {
            return 0;
        }
        long left = Math.min(daysLeft, daysInPeriod);
        return BigDecimal.valueOf(diff).multiply(BigDecimal.valueOf(left)).divide(BigDecimal.valueOf(daysInPeriod), 0, RoundingMode.HALF_UP).longValue();
    }

    private List<String> order() {
        return platform.plans().stream().map(p -> (String) p.get("code")).toList();
    }

    private int rank(String code) {
        return order().indexOf(code);
    }

    /** What a plan costs this business a month now: after any offer, before any one-off thank-you credit. */
    public long monthlyNow(UUID tenant, String plan) {
        Offers.Price p = offers.price(tenant, plan, "MONTHLY");
        return p.amount() + p.credit();
    }

    public String nameOf(UUID tenant, String plan) {
        return platform.planName(tenant, plan);
    }

    private Map<String, Object> tenantRow(UUID tenant) {
        return tenantRow(tenant, false);
    }

    /** The business's billing row; locked when a change is about to be made, so two taps cannot both go through. */
    private Map<String, Object> tenantRow(UUID tenant, boolean lock) {
        return jdbc.queryForMap("""
                SELECT sector, CASE WHEN status = 'SUSPENDED' THEN coalesce(suspended_from, 'TRIAL') ELSE status END AS status,
                       (status = 'SUSPENDED') AS suspended, suspended_reason, plan, next_plan, plan_switch_at, trial_ends_at, paid_until, period_start
                FROM tenants WHERE id = ?""" + (lock ? " FOR UPDATE" : ""), tenant);
    }

    /**
     * A lower plan was paid for ahead (the next period is already paid at its price). Keeping or moving to a plan that
     * costs more means the difference for that period goes on the next bill. 0 when nothing was paid ahead.
     */
    private long prepaidGap(UUID tenant, Map<String, Object> t, String keep) {
        String next = (String) t.get("next_plan");
        if (next == null || t.get("plan_switch_at") == null || keep.equals(next)) {
            return 0;
        }
        List<String> cycle = jdbc.queryForList("SELECT billing_cycle FROM subscription_requests WHERE tenant_id = ? AND status = 'PAID' ORDER BY created_at DESC LIMIT 1", String.class, tenant);
        long k = !cycle.isEmpty() && "ANNUAL".equals(cycle.get(0)) ? 10 : 1;
        return Math.max(0, (monthlyNow(tenant, keep) - monthlyNow(tenant, next)) * k);
    }

    private String gapLabel(UUID tenant, Map<String, Object> t, String keep) {
        return nameOf(tenant, keep) + " instead of " + nameOf(tenant, (String) t.get("next_plan")) + " for the period you already paid for";
    }

    private static Instant at(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant();
    }

    /** The plan the next bill is for: the plan after a move down, else the current one; while on free days, the plan their setup fits. */
    public String billPlan(UUID tenant) {
        Map<String, Object> t = tenantRow(tenant);
        String plan = (String) t.get("plan");
        if ("ACTIVE".equals(t.get("status")) && plan != null && platform.plan(plan) != null) {
            String next = (String) t.get("next_plan");
            return next != null && platform.plan(next) != null ? next : plan;
        }
        return (String) features.fittingPlan(tenant).get("code");
    }

    // ---------- lines ----------

    public List<Line> unpaidProrations(UUID tenant) {
        List<Line> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT label, amount FROM bill_lines WHERE tenant_id = ? AND paid_at IS NULL ORDER BY created_at, id", tenant)) {
            out.add(new Line("PRORATION", (String) r.get("label"), ((Number) r.get("amount")).longValue()));
        }
        return out;
    }

    /**
     * What paying for this plan and cycle costs this business: the plan price after offers and credit, plus the
     * unpaid proration lines. Subscribe, the M-Pesa prompt and the Billing page all use this one total.
     */
    public Quote quote(UUID tenant, String plan, String cycle) {
        Offers.Price price = offers.price(tenant, plan, cycle);
        boolean annual = "ANNUAL".equals(cycle);
        String name = nameOf(tenant, plan);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("PLAN", name + (annual ? " for the next year" : " for the next month"), price.full()));
        long discount = price.full() - (price.amount() + price.credit());
        if (discount > 0) {
            String title = price.offerTitle() == null ? "Offer" : price.offerTitle();
            if (price.credit() > 0) {
                int i = title.lastIndexOf(" · KSh");
                title = i > 0 ? title.substring(0, i) : "Offer";
            }
            lines.add(new Line("OFFER", title, -discount));
        }
        if (price.credit() > 0) {
            lines.add(new Line("CREDIT", "Your thank-you credit", -price.credit()));
        }
        String status = (String) tenantRow(tenant).get("status");
        long extra = 0;
        if ("ACTIVE".equals(status)) {
            for (Line l : unpaidProrations(tenant)) {
                lines.add(l);
                extra += l.amount();
            }
        }
        return new Quote(price, plan, name, cycle, lines, extra, price.amount() + extra);
    }

    /** The next bill for the Billing page, the reminders and the control panel. */
    @Transactional(readOnly = true)
    public Map<String, Object> nextBill(UUID tenant, String cycle) {
        Map<String, Object> t = tenantRow(tenant);
        boolean trial = !"ACTIVE".equals(t.get("status"));
        String plan = billPlan(tenant);
        Quote q = quote(tenant, plan, cycle);
        Instant date = trial ? at(t.get("trial_ends_at")) : at(t.get("paid_until"));
        Instant now = Instant.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trial", trial);
        m.put("date", date == null ? null : date.toString());
        m.put("dateText", date == null ? null : day(date));
        m.put("plan", plan);
        m.put("planName", q.planName());
        m.put("cycle", cycle);
        m.put("lines", q.lines().stream().map(Line::map).toList());
        m.put("total", q.total());
        m.put("extra", q.extra());
        m.put("pay", q.price().amount());
        m.put("full", q.price().full());
        m.put("offer", q.price().offerTitle());
        boolean late = !trial && date != null && date.isBefore(now);
        Instant graceEnds = trial || date == null ? null : date.plus(GRACE_DAYS, ChronoUnit.DAYS);
        m.put("late", late);
        m.put("graceEndsAt", graceEnds == null ? null : graceEnds.toString());
        m.put("graceEndsText", graceEnds == null ? null : day(graceEnds));
        boolean inGrace = late && graceEnds.isAfter(now);
        m.put("inGrace", inGrace);
        boolean paused = Boolean.TRUE.equals(t.get("suspended"));
        m.put("suspended", paused);
        m.put("locked", paused || !trial && graceEnds != null && !graceEnds.isAfter(now));
        String next = (String) t.get("next_plan");
        if (!trial && next != null) {
            m.put("movingTo", next);
            m.put("movingToName", nameOf(tenant, next));
        }
        m.put("summary", paused ? "Your workspace is paused. Talk to us and we will sort it out together. When it is back, your next bill is " + kes(q.total()) + "."
                : trial
                ? "Nothing to pay today. When your free days end on " + (date == null ? "" : day(date)) + ", the next bill is " + kes(q.total()) + "."
                : late ? "Your bill date was " + day(date) + ". Pay " + kes(q.total()) + (inGrace ? " by " + day(graceEnds) + " to keep working." : " to keep working.")
                : "Nothing to pay today. Your next bill on " + (date == null ? "" : day(date)) + " is " + kes(q.total()) + ".");
        return m;
    }

    // ---------- the sheet and the change ----------

    /** What a business would see before moving: the words, the numbers and the buttons. needed=false when nothing changes. */
    public Map<String, Object> preview(UUID tenant, String feature, String limit, int adding, String toPlan) {
        Map<String, Object> t = tenantRow(tenant);
        boolean paid = "ACTIVE".equals(t.get("status")) && t.get("plan") != null && platform.plan((String) t.get("plan")) != null;
        String from = paid ? (String) t.get("plan") : (String) features.fittingPlan(tenant).get("code");
        String reason = null;
        String target = toPlan == null ? null : toPlan.toUpperCase(Locale.ROOT);
        if (target != null && platform.plan(target) == null) {
            throw ApiException.bad("Unknown plan");
        }
        if (feature != null && !feature.isBlank()) {
            PlanFeatures.Feature f = PlanFeatures.feature(feature);
            if (f == null) {
                throw ApiException.bad("Unknown tool");
            }
            reason = f.label();
            if (target == null) {
                target = paid ? cheapestWith(feature, from) : (String) features.fittingPlan(tenant, Set.of(feature), 0, 0).get("code");
            }
        } else if (limit != null && !limit.isBlank()) {
            int n = Math.max(1, adding);
            boolean staff = "users".equals(limit);
            reason = staff ? "Adding " + (n == 1 ? "one more person" : n + " more people") : "Adding " + (n == 1 ? "one more branch" : n + " more branches");
            if (target == null) {
                target = paid ? cheapestFitting(tenant, from, staff, n)
                        : (String) features.fittingPlan(tenant, Set.of(), staff ? n : 0, staff ? 0 : n).get("code");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("reason", reason);
        out.put("fromName", nameOf(tenant, from));
        out.put("trial", !paid);
        if (target == null || target.equals(from) && (paid ? t.get("next_plan") == null : true)) {
            out.put("needed", false);
            out.put("message", target == null ? "This is not on any plan yet. Talk to LeziTech about it." : "Nothing changes: you are already on " + nameOf(tenant, from) + ".");
            return out;
        }
        String toName = nameOf(tenant, target);
        long toMonthly = monthlyNow(tenant, target);
        long fromMonthly = monthlyNow(tenant, from);
        out.put("to", target);
        out.put("toName", toName);
        out.put("toMonthly", toMonthly);
        out.put("fromMonthly", fromMonthly);
        Instant date = paid ? at(t.get("paid_until")) : at(t.get("trial_ends_at"));
        out.put("billDate", date == null ? null : date.toString());
        String lead = reason == null ? "" : reason + (reason.startsWith("Adding ") ? " is more than " + nameOf(tenant, from) + " includes. It needs " + toName + ". "
                : " is part of " + toName + ". ");
        if (!paid) {
            out.put("needed", true);
            out.put("direction", "UP");
            out.put("proration", 0);
            out.put("message", lead + "When your free days end on " + (date == null ? "" : day(date)) + ", this setup is " + toName + ": "
                    + kes(toMonthly) + " a month. Nothing to pay today.");
            out.put("yes", "Yes, switch it on");
            out.put("no", "Not now");
            return out;
        }
        int dir = Integer.compare(rank(target), rank(from));
        String nextPlan = (String) t.get("next_plan");
        if (dir > 0) {
            long[] days = daysLeft(t);
            long extra = extraFor(fromMonthly, toMonthly, days);
            long others = 0;
            for (Line l : unpaidProrations(tenant)) {
                others += l.amount();
            }
            Offers.Price p = offers.price(tenant, target, "MONTHLY");
            long next = p.amount();
            StringBuilder msg = new StringBuilder(lead);
            msg.append(toName).append(" is ").append(kes(toMonthly)).append(" a month (you are on ").append(nameOf(tenant, from)).append(", ")
                    .append(kes(fromMonthly)).append("). Nothing to pay today. Your plan changes now. Your next bill on ")
                    .append(date == null ? "" : day(date)).append(" will be ").append(kes(next)).append(" for the next month");
            if (extra > 0) {
                msg.append(", plus ").append(kes(extra)).append(" for the ").append(days[0]).append(days[0] == 1 ? " day" : " days").append(" of ")
                        .append(toName).append(" this month");
            }
            msg.append(".");
            long gap = prepaidGap(tenant, t, target);
            if (gap > 0) {
                msg.append(" You already paid the next period at ").append(nameOf(tenant, nextPlan)).append("'s price, so ").append(kes(gap))
                        .append(" for the difference is added too.");
                others += gap;
            }
            if (others > 0) {
                msg.append(" Earlier extra amounts of ").append(kes(others)).append(" are also on it, ").append(kes(next + extra + others)).append(" in all.");
            }
            out.put("needed", true);
            out.put("direction", "UP");
            out.put("proration", extra);
            out.put("days", days[0]);
            out.put("nextBill", next + extra + others);
            out.put("message", msg.toString());
            out.put("yes", "Yes, move me to " + toName);
            out.put("no", "Not now");
        } else if (dir < 0) {
            long next = offers.price(tenant, target, "MONTHLY").amount();
            out.put("needed", true);
            out.put("direction", "DOWN");
            out.put("proration", 0);
            out.put("nextBill", next);
            out.put("message", lead + "Moving to " + toName + " (" + kes(toMonthly) + " a month) starts on your next bill date, "
                    + (date == null ? "" : day(date)) + ". Until then you keep " + nameOf(tenant, from) + " as it is. Nothing is refunded for the days you have already paid for. Your next bill will be "
                    + kes(next) + ".");
            out.put("yes", "Yes, move me to " + toName + " on " + (date == null ? "the bill date" : day(date)));
            out.put("no", "Not now");
        } else {
            // Same plan as now, with a move down waiting: staying on it cancels that move.
            out.put("needed", true);
            out.put("direction", "STAY");
            long gap = prepaidGap(tenant, t, target);
            out.put("proration", gap);
            out.put("message", "Stay on " + toName + "? Your move to " + nameOf(tenant, nextPlan) + " on " + (date == null ? "the bill date" : day(date)) + " is cancelled."
                    + (gap > 0 ? " You already paid the next period at " + nameOf(tenant, nextPlan) + "'s price, so " + kes(gap)
                    + " for the difference is added to your next bill." : ""));
            out.put("yes", "Yes, stay on " + toName);
            out.put("no", "Not now");
        }
        return out;
    }

    /** The extra for the days left; a yearly period (over 45 days) uses the yearly price, ten months. */
    private static long extraFor(long fromMonthly, long toMonthly, long[] days) {
        long k = days[1] > 45 ? 10 : 1;
        return prorate(fromMonthly * k, toMonthly * k, days[0], days[1]);
    }

    /** {days left, days in the period} by the calendar in Kenya, today counted and the bill date not. */
    private long[] daysLeft(Map<String, Object> t) {
        Instant end = at(t.get("paid_until"));
        if (end == null) {
            return new long[]{0, 30};
        }
        Instant start = at(t.get("period_start"));
        LocalDate endDay = end.atZone(NAIROBI).toLocalDate();
        LocalDate startDay = start != null ? start.atZone(NAIROBI).toLocalDate() : endDay.minusMonths(1);
        long total = Math.max(1, ChronoUnit.DAYS.between(startDay, endDay));
        long left = Math.max(0, Math.min(total, ChronoUnit.DAYS.between(LocalDate.now(NAIROBI), endDay)));
        return new long[]{left, total};
    }

    private String cheapestWith(String feature, String from) {
        for (String code : order()) {
            if (platform.planFeatures(code).contains(feature)) {
                return rank(code) < rank(from) ? from : code;
            }
        }
        return null;
    }

    private String cheapestFitting(UUID tenant, String from, boolean staff, int adding) {
        long used = staff
                ? jdbc.queryForObject("SELECT count(*) FROM users WHERE tenant_id = ? AND active", Long.class, tenant)
                : jdbc.queryForObject("SELECT count(*) FROM locations WHERE tenant_id = ? AND parent_id IS NULL", Long.class, tenant);
        for (String code : order()) {
            if (rank(code) < rank(from)) {
                continue;
            }
            Map<String, Object> p = platform.plan(code);
            int limit = ((Number) p.get(staff ? "users" : "locations")).intValue();
            if (limit == 0 || used + adding <= limit) {
                return code;
            }
        }
        return null;
    }

    /**
     * Move the business to another plan. Up on a paid plan: the plan changes now and a proration line goes on the next bill.
     * Down: starts at the next bill date. In the free days: only recorded, nothing is added. Only the owner may.
     */
    @Transactional
    public Map<String, Object> change(String toPlan, String reason) {
        TenantContext.requireRole("OWNER");
        return apply(TenantContext.tenantId(), toPlan, reason, null);
    }

    /** LeziTech changes the plan for a business (same rules, same sheet wording). Call inside TenantContext.runAs for that business. */
    @Transactional
    public Map<String, Object> changeBy(UUID tenant, String toPlan, String reason, String actor) {
        return apply(tenant, toPlan, reason, actor);
    }

    private Map<String, Object> apply(UUID tenant, String toPlan, String reason, String actor) {
        String to = toPlan == null ? "" : toPlan.toUpperCase(Locale.ROOT);
        if (platform.plan(to) == null) {
            throw ApiException.bad("Choose a plan");
        }
        Map<String, Object> t = tenantRow(tenant, true);
        boolean paid = "ACTIVE".equals(t.get("status")) && t.get("plan") != null && platform.plan((String) t.get("plan")) != null;
        String from = paid ? (String) t.get("plan") : (String) features.fittingPlan(tenant).get("code");
        String toName = nameOf(tenant, to), fromName = nameOf(tenant, from);
        Instant date = paid ? at(t.get("paid_until")) : at(t.get("trial_ends_at"));
        String why = reason == null || reason.isBlank() ? "Chosen on the plans page" : reason.length() > 300 ? reason.substring(0, 300) : reason;
        if (!paid) {
            if (!"TRIAL".equals(t.get("status"))) {
                throw ApiException.bad("Choose a plan and pay to switch your workspace back on");
            }
            record(tenant, from, to, "TRIAL_SETUP", why, 0, Instant.now(), actor);
            return Map.of("ok", true, "kind", "TRIAL_SETUP", "plan", to, "planName", toName, "proration", 0,
                    "message", "Noted. When your free days end on " + (date == null ? "" : day(date)) + ", this setup is " + toName + ". Nothing to pay today.");
        }
        int dir = Integer.compare(rank(to), rank(from));
        String nextPlan = (String) t.get("next_plan");
        if (dir == 0) {
            if (nextPlan == null) {
                throw ApiException.bad("You are already on " + toName);
            }
            long gap = prepaidGap(tenant, t, to);
            String gapText = gap > 0 ? gapLabel(tenant, t, to) : null;
            jdbc.update("UPDATE tenants SET next_plan = NULL, plan_switch_at = NULL WHERE id = ?", tenant);
            UUID id = record(tenant, from, to, "CANCEL_DOWNGRADE", "Stayed on " + toName, gap, Instant.now(), actor);
            supersedeRequests(tenant);
            if (gap > 0) {
                jdbc.update("INSERT INTO bill_lines (tenant_id, plan_change_id, kind, label, amount) VALUES (?, ?, 'DIFFERENCE', ?, ?)", tenant, id, gapText, gap);
            }
            return Map.of("ok", true, "kind", "CANCEL_DOWNGRADE", "plan", to, "planName", toName, "proration", gap,
                    "message", "You stay on " + toName + ". Your move to " + nameOf(tenant, nextPlan) + " is cancelled."
                            + (gap > 0 ? " " + kes(gap) + " for the difference on the period you already paid for is added to your next bill." : ""));
        }
        if (dir < 0) {
            if (to.equals(nextPlan)) {
                throw ApiException.bad("You are already moving to " + toName + " on " + (date == null ? "your next bill date" : day(date)));
            }
            jdbc.update("UPDATE tenants SET next_plan = ?, plan_switch_at = NULL WHERE id = ?", to, tenant);
            supersedeRequests(tenant);
            record(tenant, from, to, "DOWNGRADE", why, 0, date == null ? Instant.now() : date, actor);
            return Map.of("ok", true, "kind", "DOWNGRADE", "plan", from, "planName", fromName, "movingTo", to, "movingToName", toName, "proration", 0,
                    "message", "You move to " + toName + " on " + (date == null ? "your next bill date" : day(date)) + ". Until then you keep " + fromName
                            + ". Nothing is refunded for the days you have already paid for.");
        }
        long[] days = daysLeft(t);
        long extra = extraFor(monthlyNow(tenant, from), monthlyNow(tenant, to), days);
        long gap = prepaidGap(tenant, t, to);
        String gapText = gap > 0 ? gapLabel(tenant, t, to) : null;
        UUID changeId = record(tenant, from, to, "UPGRADE", why, extra + gap, Instant.now(), actor);
        jdbc.update("UPDATE tenants SET plan = ?, next_plan = NULL, plan_switch_at = NULL WHERE id = ?", to, tenant);
        supersedeRequests(tenant);
        if (gap > 0) {
            jdbc.update("INSERT INTO bill_lines (tenant_id, plan_change_id, kind, label, amount) VALUES (?, ?, 'DIFFERENCE', ?, ?)", tenant, changeId, gapText, gap);
        }
        if (extra > 0) {
            jdbc.update("INSERT INTO bill_lines (tenant_id, plan_change_id, kind, label, amount) VALUES (?, ?, 'PRORATION', ?, ?)",
                    tenant, changeId, days[0] + (days[0] == 1 ? " day" : " days") + " of " + toName + " this month", extra);
        }
        return Map.of("ok", true, "kind", "UPGRADE", "plan", to, "planName", toName, "proration", extra,
                "message", "You are on " + toName + " now. Nothing to pay today."
                        + (extra > 0 ? " " + kes(extra) + " for the " + days[0] + (days[0] == 1 ? " day" : " days") + " of " + toName + " this month is added to your next bill." : ""));
    }

    /** Payment requests made before a plan change were worked out for the old plan and bill: they are closed, and a new one is made from the new bill. */
    private void supersedeRequests(UUID tenant) {
        jdbc.update("""
                UPDATE subscription_requests SET status = 'SUPERSEDED', note = 'Your plan changed, so this request was closed. The next bill was worked out again.'
                WHERE tenant_id = ? AND status = 'PENDING'""", tenant);
    }

    private UUID record(UUID tenant, String from, String to, String kind, String why, long proration, Instant effective, String actor) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO plan_changes (tenant_id, changed_by, changed_by_name, from_plan, to_plan, kind, reason, proration, effective_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                tenant, actor == null ? TenantContext.userId() : null, actor == null ? TenantContext.caller().name() : actor, from, to, kind, why, proration, Timestamp.from(effective));
        try {
            activity.record("PLAN_CHANGE", ActivityLog.NOTICE,
                    ("TRIAL_SETUP".equals(kind) ? "Setup moved to " : "DOWNGRADE".equals(kind) ? "Plan will move to " : "Plan changed to ") + nameOf(tenant, to) + " (from " + nameOf(tenant, from) + ")");
        } catch (RuntimeException e) {
            // the record in plan_changes is the one that counts
        }
        return id;
    }

    /** The recorded changes for a business, newest first (control panel). */
    public List<Map<String, Object>> changes(UUID tenant) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT changed_by_name, from_plan, to_plan, kind, reason, proration, effective_at, created_at
                FROM plan_changes WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 30""", tenant)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("by", r.get("changed_by_name"));
            m.put("from", r.get("from_plan"));
            m.put("fromName", r.get("from_plan") == null ? null : nameOf(tenant, (String) r.get("from_plan")));
            m.put("to", r.get("to_plan"));
            m.put("toName", nameOf(tenant, (String) r.get("to_plan")));
            m.put("kind", r.get("kind"));
            m.put("reason", r.get("reason"));
            m.put("proration", ((Number) r.get("proration")).longValue());
            m.put("effectiveAt", at(r.get("effective_at")).toString());
            m.put("at", at(r.get("created_at")).toString());
            out.add(m);
        }
        return out;
    }

    // ---------- payment ----------

    /** The lines a payment request was made of, for the control panel (null for requests made before bills had lines). */
    public String linesJson(Quote q) {
        try {
            return json.writeValueAsString(q.lines().stream().map(Line::map).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A paid request covers the proration lines that existed when it was made. */
    public int markPaid(UUID tenant, UUID requestId, Timestamp requestMadeAt) {
        // Lock the lines by id first: a line two requests both included is settled by whichever is confirmed first, once.
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM bill_lines
                WHERE tenant_id = ? AND paid_at IS NULL AND (cast(? as timestamptz) IS NULL OR created_at <= ?)
                ORDER BY id FOR UPDATE""", UUID.class, tenant, requestMadeAt, requestMadeAt);
        int n = 0;
        for (UUID id : ids) {
            n += jdbc.update("UPDATE bill_lines SET paid_request_id = ?, paid_at = now() WHERE id = ? AND paid_at IS NULL", requestId, id);
        }
        return n;
    }

    /** A move down that was paid for ahead starts on the bill date: switch every business whose date has come. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 90_000)
    public int applyScheduledMoves() {
        return jdbc.update("""
                UPDATE tenants SET plan = next_plan, next_plan = NULL, plan_switch_at = NULL
                WHERE next_plan IS NOT NULL AND plan_switch_at IS NOT NULL AND plan_switch_at <= now()""");
    }
}
