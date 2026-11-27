package com.lezitech.suite.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.notify.TenantNotices;
import com.lezitech.suite.platform.Offers;
import com.lezitech.suite.platform.PlanFeatures;
import com.lezitech.suite.platform.PlatformSettings;
import com.lezitech.suite.settings.PlanBilling;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What the control panel shows about one business, or all of them: money, payments, requests, activity, usage, notes, and the
 * Workspaces list with its totals. Every number comes from the same functions the business's own pages use (PlanBilling's next
 * bill and Offers' price), so both sides always agree.
 */
@Service
public class Oversight {

    static final ZoneId NAIROBI = PlanBilling.NAIROBI;
    private static final int GRACE_DAYS = PlanBilling.GRACE_DAYS;

    private final JdbcTemplate jdbc;
    private final PlanBilling billing;
    private final Offers offers;
    private final PlatformSettings platform;
    private final PlanFeatures features;
    private final TenantNotices notices;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public Oversight(JdbcTemplate jdbc, PlanBilling billing, Offers offers, PlatformSettings platform, PlanFeatures features,
                     TenantNotices notices, ObjectMapper json, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.offers = offers;
        this.platform = platform;
        this.features = features;
        this.notices = notices;
        this.json = json;
        this.tx = new TransactionTemplate(txm);
    }

    /** Run as this business inside one transaction (business tables are fenced by business). */
    public <T> T asTenant(UUID tenant, Supplier<T> work) {
        return TenantContext.runAs(tenant, null, () -> tx.execute(s -> work.get()));
    }

    // ---------- helpers ----------

    static String camel(String k) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : k.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }

    /** A database row for the screen: camelCase names, times as text, ids as text, JSON columns parsed. */
    Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        r.forEach((k, v) -> {
            Object val = v instanceof Timestamp ts ? ts.toInstant().toString() : v instanceof UUID u ? u.toString() : v;
            if (("detail".equals(k) || "lines".equals(k)) && v instanceof String s) {
                try {
                    val = json.readTree(s);
                } catch (Exception ignored) {
                    // keep as text
                }
            }
            out.put(camel(k), val);
        });
        return out;
    }

    private static Instant at(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant();
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    static String clip(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private Map<String, Object> tenantRow(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, sector, status, plan, next_plan, in_setup, trial_ends_at, paid_until, suspended_reason, suspended_at, phone, is_demo, last_active_at
                FROM tenants WHERE id = ?""", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Workspace");
        }
        return rows.get(0);
    }

    /**
     * The status in plain words, from one business's dates: Free trial (N days left), Paid until a date, In grace (N days left),
     * Locked since a date, Suspended (reason). The same words in the Workspaces list and on the workspace page.
     */
    public static Map<String, Object> statusOf(Map<String, Object> t) {
        String status = (String) t.get("status");
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(NAIROBI);
        String key, text;
        Instant until = null;
        if ("SUSPENDED".equals(status)) {
            key = "SUSPENDED";
            String why = (String) t.get("suspended_reason");
            text = "Suspended" + (why == null || why.isBlank() ? "" : " (" + why + ")");
        } else if (Boolean.TRUE.equals(t.get("in_setup"))) {
            key = "SETUP";
            text = "Being set up by LeziTech";
        } else if ("ACTIVE".equals(status)) {
            Instant paid = at(t.get("paid_until"));
            until = paid;
            if (paid == null || paid.isAfter(now)) {
                key = "PAID";
                text = paid == null ? "Paid" : "Paid until " + PlanBilling.day(paid);
            } else {
                Instant graceEnd = paid.plus(GRACE_DAYS, ChronoUnit.DAYS);
                if (graceEnd.isAfter(now)) {
                    long left = Math.max(1, ChronoUnit.DAYS.between(today, graceEnd.atZone(NAIROBI).toLocalDate()));
                    key = "GRACE";
                    text = "In grace (" + left + (left == 1 ? " day" : " days") + " left)";
                } else {
                    key = "LOCKED";
                    text = "Locked since " + PlanBilling.day(graceEnd);
                }
            }
        } else {
            Instant end = at(t.get("trial_ends_at"));
            until = end;
            if (end != null && end.isAfter(now)) {
                long left = ChronoUnit.DAYS.between(today, end.atZone(NAIROBI).toLocalDate());
                key = "TRIAL";
                text = "Free trial (" + (left <= 0 ? "ends today" : left + (left == 1 ? " day left" : " days left")) + ")";
            } else {
                key = "LOCKED";
                text = "Locked since " + (end == null ? "" : PlanBilling.day(end));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("text", text);
        m.put("until", until == null ? null : until.toString());
        return m;
    }

    private static String cycleOf(Object lastCycle) {
        return "ANNUAL".equals(lastCycle) ? "ANNUAL" : "MONTHLY";
    }

    // ---------- Money (top of the workspace page) ----------

    /** Plan, price now, status, next bill and what has been paid. The next bill is the very same one the business sees on its Billing page. */
    public Map<String, Object> money(UUID id) {
        Map<String, Object> t = tenantRow(id);
        Map<String, Object> pay = jdbc.queryForMap("""
                SELECT coalesce(sum(amount) FILTER (WHERE paid_via IS DISTINCT FROM 'CREDIT'), 0) AS total_paid,
                       (array_agg(billing_cycle ORDER BY paid_at DESC NULLS LAST, created_at DESC))[1] AS last_cycle
                FROM subscription_requests WHERE tenant_id = ? AND status = 'PAID'""", id);
        String cycle = cycleOf(pay.get("last_cycle"));
        return asTenant(id, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            String plan = billing.billPlan(id);
            Offers.Price price = offers.price(id, plan, "MONTHLY");
            Map<String, Object> platformPlan = platform.plan(plan);
            out.put("plan", plan);
            out.put("planName", billing.nameOf(id, plan));
            out.put("planLabel", billing.nameOf(id, plan) + " (" + platformPlan.get("name") + ")");
            out.put("priceNow", price.amount() + price.credit());
            out.put("priceWithoutOffers", price.full());
            String offerTitle = price.offerTitle();
            if (offerTitle != null && price.credit() > 0) {
                int i = offerTitle.lastIndexOf(" · KSh");
                offerTitle = i > 0 ? offerTitle.substring(0, i) : null;
            }
            out.put("offer", offerTitle);
            out.put("cycle", cycle);
            out.put("status", statusOf(t));
            out.put("suspendedReason", t.get("suspended_reason"));
            out.put("nextBill", billing.nextBill(id, cycle));
            out.put("totalPaid", ((BigDecimal) pay.get("total_paid")).longValue());
            List<Map<String, Object>> last = jdbc.queryForList("""
                    SELECT paid_at, amount, paid_via, mpesa_receipt FROM subscription_requests
                    WHERE tenant_id = ? AND status = 'PAID' AND paid_via IS DISTINCT FROM 'CREDIT' ORDER BY paid_at DESC NULLS LAST, created_at DESC LIMIT 1""", id);
            out.put("lastPayment", last.isEmpty() ? null : Map.of("at", String.valueOf(at(last.get(0).get("paid_at"))),
                    "amount", ((BigDecimal) last.get(0).get("amount")).longValue(), "method", methodLabel((String) last.get(0).get("paid_via")),
                    "reference", String.valueOf(last.get(0).get("mpesa_receipt") == null ? "" : last.get(0).get("mpesa_receipt"))));
            out.put("noticesUnseen", jdbc.queryForObject("SELECT count(*) FROM tenant_notices WHERE tenant_id = ? AND seen_at IS NULL", Long.class, id));
            return out;
        });
    }

    static String methodLabel(String via) {
        return via == null ? "M-Pesa" : switch (via) {
            case "MANUAL" -> "Recorded by hand";
            case "CREDIT" -> "Thank-you credit";
            case "MPESA" -> "M-Pesa";
            default -> via;
        };
    }

    // ---------- Payments ----------

    /** Every plan payment: date, amount, method, code, phone, who activated it and the period it covered. Totals per year. */
    public Map<String, Object> payments(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, plan, billing_cycle, amount, mpesa_phone, paid_via, mpesa_receipt, activated_by, activated_reason, note,
                       coalesce(paid_at, created_at) AS paid_at, period_from, period_to, lines::text AS lines
                FROM subscription_requests WHERE tenant_id = ? AND status = 'PAID' ORDER BY coalesce(paid_at, created_at) DESC""", id);
        String sector = jdbc.queryForObject("SELECT sector FROM tenants WHERE id = ?", String.class, id);
        String subSector = jdbc.queryForObject("SELECT sub_sector FROM tenants WHERE id = ?", String.class, id);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<Integer, Long> byYear = new TreeMap<>(Comparator.reverseOrder());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = row(r);
            m.put("planName", platform.planName(sector, subSector, (String) r.get("plan")));
            m.put("method", methodLabel((String) r.get("paid_via")));
            m.put("activatedBy", r.get("activated_by") != null ? r.get("activated_by") : "MANUAL".equals(r.get("paid_via")) ? "LeziTech" : "Automatic (M-Pesa)");
            out.add(m);
            if (!"CREDIT".equals(r.get("paid_via"))) {
                byYear.merge(at(r.get("paid_at")).atZone(NAIROBI).getYear(), num(r.get("amount")), Long::sum);
            }
        }
        return Map.of("payments", out, "byYear", byYear.entrySet().stream().map(e -> Map.<String, Object>of("year", e.getKey(), "total", e.getValue())).toList());
    }

    public String paymentsCsv(UUID id) {
        StringBuilder sb = new StringBuilder("Date,Amount (KES),Method,M-Pesa code or reference,Phone,Plan,Activated by,Period from,Period to,Reason\n");
        for (Object o : (List<?>) payments(id).get("payments")) {
            Map<?, ?> p = (Map<?, ?>) o;
            sb.append(csv(date(p.get("paidAt")), p.get("amount"), p.get("method"), p.get("mpesaReceipt"), p.get("mpesaPhone"), p.get("planName"),
                    p.get("activatedBy"), date(p.get("periodFrom")), date(p.get("periodTo")), p.get("activatedReason"))).append('\n');
        }
        return sb.toString();
    }

    /** One receipt for the receipt view (the screen prints or saves it). */
    public Map<String, Object> receipt(UUID id, UUID requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id, r.plan, r.billing_cycle, r.amount, r.mpesa_phone, r.paid_via, r.mpesa_receipt, r.activated_by, coalesce(r.paid_at, r.created_at) AS paid_at,
                       r.period_from, r.period_to, r.lines::text AS lines, t.name AS business, t.sector, t.sub_sector
                FROM subscription_requests r JOIN tenants t ON t.id = r.tenant_id WHERE r.id = ? AND r.tenant_id = ? AND r.status = 'PAID'""", requestId, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Payment");
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> m = row(r);
        m.put("receiptNo", "BZ-" + r.get("id").toString().substring(0, 8).toUpperCase());
        m.put("planName", platform.planName((String) r.get("sector"), (String) r.get("sub_sector"), (String) r.get("plan")));
        m.put("method", methodLabel((String) r.get("paid_via")));
        return m;
    }

    private static String date(Object iso) {
        return iso == null ? "" : String.valueOf(iso).length() >= 10 ? String.valueOf(iso).substring(0, 10) : String.valueOf(iso);
    }

    static String csv(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = cells[i] == null ? "" : String.valueOf(cells[i]);
            // A cell that starts like a formula is made harmless in a spreadsheet.
            if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9.,]+")) {
                v = "'" + v;
            }
            sb.append('"').append(v.replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    // ---------- Requests ----------

    /** Payment requests (waiting, paid, expired, refused), plan changes, offers given and used, free days and trial extensions. */
    public Map<String, Object> requests(UUID id) {
        String sector = jdbc.queryForObject("SELECT sector FROM tenants WHERE id = ?", String.class, id);
        String subSector = jdbc.queryForObject("SELECT sub_sector FROM tenants WHERE id = ?", String.class, id);
        List<Map<String, Object>> reqs = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT r.id, r.plan, r.billing_cycle, r.amount, r.mpesa_phone, r.status, r.created_at, r.note, r.lines::text AS lines, o.title AS offer_title
                FROM subscription_requests r LEFT JOIN platform_offers o ON o.id = r.offer_id
                WHERE r.tenant_id = ? ORDER BY r.created_at DESC LIMIT 200""", id)) {
            Map<String, Object> m = row(r);
            m.put("planName", platform.planName(sector, subSector, (String) r.get("plan")));
            reqs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", reqs);
        out.put("planChanges", billing.changes(id));
        out.put("offers", jdbc.queryForList("""
                SELECT o.id, o.kind, o.title, o.percent, o.months, o.plan, o.price, o.ends_at, o.active, o.created_by, o.created_at, (o.active AND o.ends_at > now()) AS live,
                       (SELECT count(*) FROM subscription_requests s WHERE s.offer_id = o.id AND s.tenant_id = ? AND s.status = 'PAID') AS used,
                       (o.tenant_id IS NOT NULL) AS only_for_them
                FROM platform_offers o JOIN tenants t ON t.id = ?
                WHERE (o.tenant_id = t.id OR (o.tenant_id IS NULL AND (o.sector IS NULL OR o.sector = t.sector)
                       AND (o.created_at >= t.created_at - interval '1 day' OR EXISTS (SELECT 1 FROM subscription_requests s WHERE s.offer_id = o.id AND s.tenant_id = t.id))))
                  AND o.kind <> 'DAYS'
                ORDER BY o.created_at DESC LIMIT 50""", id, id).stream().map(this::row).toList());
        out.put("freeDays", jdbc.queryForList("""
                SELECT action, admin_name, detail::text AS detail, created_at FROM platform_audit
                WHERE tenant_id = ? AND action IN ('workspace.free_days', 'trial.extend') ORDER BY created_at DESC LIMIT 50""", id).stream().map(this::row).toList());
        return out;
    }

    // ---------- Notes ----------

    public List<Map<String, Object>> notes(UUID id) {
        return jdbc.queryForList("SELECT id, admin_name, note, created_at FROM tenant_notes WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 200", id)
                .stream().map(this::row).toList();
    }

    public void addNote(UUID id, UUID adminId, String adminName, String note) {
        tenantRow(id);
        jdbc.update("INSERT INTO tenant_notes (tenant_id, admin_id, admin_name, note) VALUES (?, ?, ?, ?)", id, adminId, adminName, note);
        jdbc.update("UPDATE tenants SET admin_note = ? WHERE id = ?", note, id);
    }

    // ---------- TenantNotices sent ----------

    public List<Map<String, Object>> noticesSent(UUID id) {
        tenantRow(id);
        return notices.sentTo(id).stream().map(this::row).toList();
    }

    // ---------- Activity ----------

    private static Map<String, Object> event(Object at, String kind, String title, String detail, String by) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at instanceof Timestamp ts ? ts.toInstant().toString() : String.valueOf(at));
        m.put("kind", kind);
        m.put("title", title);
        m.put("detail", detail);
        m.put("by", by);
        return m;
    }

    /** One timeline of everything LeziTech did with this business, and what the business sent LeziTech. Newest first. */
    public List<Map<String, Object>> timeline(UUID id) {
        tenantRow(id);
        List<Map<String, Object>> ev = new ArrayList<>();
        for (Map<String, Object> a : jdbc.queryForList("""
                SELECT action, admin_name, detail::text AS detail, created_at FROM platform_audit WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 200""", id)) {
            String action = (String) a.get("action");
            String kind = action.startsWith("workspace.impersonate") || action.contains("open_code") || action.contains("ask_help") ? "SUPPORT"
                    : action.startsWith("workspace.note") ? "NOTE"
                    : action.startsWith("workspace.owner_email") || action.contains("user_code") || action.contains("signin_code") || action.contains("welcome_code") ? "SIGNIN"
                    : "MONEY";
            ev.add(event(a.get("created_at"), kind, describe(action), (String) a.get("detail"), (String) a.get("admin_name")));
        }
        for (Map<String, Object> c : jdbc.queryForList("""
                SELECT p.from_plan, p.to_plan, p.kind, p.reason, p.proration, p.created_at, p.changed_by_name FROM plan_changes p WHERE p.tenant_id = ? ORDER BY p.created_at DESC LIMIT 100""", id)) {
            ev.add(event(c.get("created_at"), "MONEY", "Plan " + String.valueOf(c.get("kind")).toLowerCase().replace('_', ' ') + ": "
                    + c.get("from_plan") + " to " + c.get("to_plan"), c.get("reason") == null ? null : (String) c.get("reason"), (String) c.get("changed_by_name")));
        }
        for (Map<String, Object> n : jdbc.queryForList("SELECT admin_name, note, created_at FROM tenant_notes WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 100", id)) {
            ev.add(event(n.get("created_at"), "NOTE", "Note", (String) n.get("note"), (String) n.get("admin_name")));
        }
        for (Map<String, Object> m : jdbc.queryForList("""
                SELECT kind, message, contact_name, created_at, status FROM team_messages WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 100""", id)) {
            ev.add(event(m.get("created_at"), "MESSAGE", "Message from the business (" + String.valueOf(m.get("kind")).toLowerCase() + ")", (String) m.get("message"), (String) m.get("contact_name")));
        }
        for (Map<String, Object> s : jdbc.queryForList("SELECT contact_name, status, created_at, notes FROM setup_requests WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 20", id)) {
            ev.add(event(s.get("created_at"), "SETUP", "Setup request (" + String.valueOf(s.get("status")).toLowerCase().replace('_', ' ') + ")", (String) s.get("notes"), (String) s.get("contact_name")));
        }
        for (Map<String, Object> n : jdbc.queryForList("""
                SELECT kind, email_status, created_at FROM trial_nudges WHERE tenant_id = ? AND email_status <> 'SKIPPED' ORDER BY created_at DESC LIMIT 50""", id)) {
            ev.add(event(n.get("created_at"), "REMINDER", "Trial reminder sent (" + n.get("kind") + ")", "Email: " + String.valueOf(n.get("email_status")).toLowerCase(), "Automatic"));
        }
        for (Map<String, Object> n : jdbc.queryForList("""
                SELECT kind, bill_date, amount, email_status, created_at FROM bill_reminders WHERE tenant_id = ? AND email_status <> 'SKIPPED' ORDER BY created_at DESC LIMIT 50""", id)) {
            ev.add(event(n.get("created_at"), "REMINDER", "Bill reminder sent (" + n.get("kind") + ")", "Bill of " + PlanBilling.kes(num(n.get("amount"))) + ", email: "
                    + String.valueOf(n.get("email_status")).toLowerCase(), "Automatic"));
        }
        for (Map<String, Object> c : jdbc.queryForList("""
                SELECT u.name, c.purpose, c.created_at, c.used_at FROM login_codes c JOIN users u ON u.id = c.user_id
                WHERE u.tenant_id = ? ORDER BY c.created_at DESC LIMIT 50""", id)) {
            ev.add(event(c.get("created_at"), "SIGNIN", "Sign-in code sent to " + c.get("name") + " (" + String.valueOf(c.get("purpose")).toLowerCase() + ")",
                    c.get("used_at") == null ? "Not used yet" : "Used", null));
        }
        // Support openings and problems are in the business's own tables (fenced by business).
        for (Map<String, Object> p : asTenant(id, () -> jdbc.queryForList("""
                SELECT kind, title, detail, status, created_at FROM problems WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 50""", id))) {
            ev.add(event(p.get("created_at"), "PROBLEM", "Problem: " + p.get("title"), (String) p.get("detail"), null));
        }
        for (Map<String, Object> n : notices.sentTo(id)) {
            ev.add(event(n.get("created_at"), "NOTICE", "Told the business: " + n.get("title"),
                    (n.get("seen_at") == null ? "Not yet seen. " : "Seen. ") + "Email " + String.valueOf(n.get("email_status")).toLowerCase(), (String) n.get("created_by")));
        }
        ev.sort(Comparator.comparing((Map<String, Object> e) -> String.valueOf(e.get("at"))).reversed());
        return ev.size() > 300 ? ev.subList(0, 300) : ev;
    }

    private static String describe(String action) {
        return switch (action) {
            case "workspace.record_payment" -> "Payment recorded by hand";
            case "workspace.free_days", "trial.extend" -> "Free days given";
            case "workspace.offer_apply" -> "Offer given";
            case "workspace.offer_end" -> "Offer ended";
            case "workspace.plan_change" -> "Plan changed by LeziTech";
            case "workspace.suspend" -> "Suspended";
            case "workspace.reactivate" -> "Unsuspended";
            case "workspace.reminder" -> "Reminder sent now";
            case "workspace.owner_email" -> "Owner email changed";
            case "workspace.signin_code" -> "Sign-in code sent";
            case "workspace.impersonate" -> "Business opened";
            case "workspace.open_code" -> "Step-up code sent to the team member";
            case "workspace.ask_help" -> "Asked the owner for help access";
            case "plan.activate" -> "Plan activated by hand";
            case "billing.approve" -> "Payment request approved";
            case "billing.reject" -> "Payment request refused";
            default -> action;
        };
    }

    // ---------- Usage ----------

    public Map<String, Object> usage(UUID id) {
        Map<String, Object> t = tenantRow(id);
        return asTenant(id, () -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("staff", jdbc.queryForList("""
                    SELECT id, name, role, job_title, active, last_login_at FROM users WHERE tenant_id = ? ORDER BY role = 'OWNER' DESC, name""", id)
                    .stream().map(this::row).toList());
            out.put("branches", jdbc.queryForList("SELECT id, name FROM locations WHERE tenant_id = ? AND parent_id IS NULL ORDER BY name", id)
                    .stream().map(this::row).toList());
            Map<String, Object> sales = jdbc.queryForMap("""
                    SELECT count(*) AS n, coalesce(sum(total), 0) AS value FROM orders
                    WHERE tenant_id = ? AND status IN ('PAID','CREDIT') AND kind <> 'QUOTE' AND closed_at > now() - interval '30 days'""", id);
            out.put("sales30", Map.of("count", num(sales.get("n")), "value", ((BigDecimal) sales.get("value")).longValue()));
            out.put("lastActivity", at(t.get("last_active_at")) == null ? null : at(t.get("last_active_at")).toString());
            String current = jdbc.queryForObject("SELECT CASE WHEN status IN ('ACTIVE') THEN plan ELSE null END FROM tenants WHERE id = ?", String.class, id);
            List<Map<String, Object>> tools = new ArrayList<>();
            Set<String> need = features.needed(id);
            for (Map<String, Object> f : features.describe()) {
                if (!need.contains(f.get("key"))) {
                    continue;
                }
                List<?> plans = (List<?>) f.get("plans");
                String needs = plans.isEmpty() ? null : (String) plans.get(0);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", f.get("key"));
                m.put("label", f.get("label"));
                m.put("needsPlan", needs);
                m.put("needsPlanName", needs == null ? null : billing.nameOf(id, needs));
                m.put("onCurrentPlan", current == null || plans.contains(current));
                tools.add(m);
            }
            out.put("tools", tools);
            return out;
        });
    }

    // ---------- Workspaces list ----------

    public static final Set<String> FILTERS = Set.of("GRACE", "LOCKED", "TRIAL3", "PAID_MONTH", "NEVER_PAID", "SUSPENDED");

    private static Instant monthStart() {
        return ZonedDateTime.now(NAIROBI).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** The shared part of every list query: one pass over businesses with their owner and payment totals joined in (lateral, so no per-row subqueries). */
    private static final String BASE = """
            WITH base AS (
              SELECT t.id, t.slug, t.name, t.sector, t.sub_sector, t.status, t.plan, t.next_plan, t.in_setup, t.trial_ends_at, t.paid_until, t.created_at,
                     t.last_active_at, t.phone, t.is_demo, t.suspended_reason, t.brand_color, t.logo_url,
                     own.owner_email, pay.total_paid, pay.paid_count, pay.last_paid_at, pay.last_amount, pay.last_via, pay.last_cycle, pay.paid_this_month
              FROM tenants t
              LEFT JOIN LATERAL (SELECT email AS owner_email FROM users WHERE tenant_id = t.id AND role = 'OWNER' ORDER BY created_at LIMIT 1) own ON true
              LEFT JOIN LATERAL (
                  SELECT coalesce(sum(r.amount) FILTER (WHERE r.paid_via IS DISTINCT FROM 'CREDIT'), 0) AS total_paid,
                         count(*) FILTER (WHERE r.paid_via IS DISTINCT FROM 'CREDIT') AS paid_count,
                         max(coalesce(r.paid_at, r.created_at)) FILTER (WHERE r.paid_via IS DISTINCT FROM 'CREDIT') AS last_paid_at,
                         (array_agg(r.amount ORDER BY coalesce(r.paid_at, r.created_at) DESC) FILTER (WHERE r.paid_via IS DISTINCT FROM 'CREDIT'))[1] AS last_amount,
                         (array_agg(r.paid_via ORDER BY coalesce(r.paid_at, r.created_at) DESC) FILTER (WHERE r.paid_via IS DISTINCT FROM 'CREDIT'))[1] AS last_via,
                         (array_agg(r.billing_cycle ORDER BY coalesce(r.paid_at, r.created_at) DESC))[1] AS last_cycle,
                         coalesce(bool_or(coalesce(r.paid_at, r.created_at) >= ? AND r.paid_via IS DISTINCT FROM 'CREDIT'), false) AS paid_this_month
                  FROM subscription_requests r WHERE r.tenant_id = t.id AND r.status = 'PAID') pay ON true
              WHERE t.is_demo = ?
                AND (cast(? as text) IS NULL OR lower(t.name) LIKE lower(cast(? as text)) ESCAPE '\\' OR lower(t.slug) LIKE lower(cast(? as text)) ESCAPE '\\'
                     OR EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = t.id AND lower(u.email) LIKE lower(cast(? as text)) ESCAPE '\\'))
                AND (cast(? as text) IS NULL OR t.sector = cast(? as text))
            ), f AS (
              SELECT * FROM base WHERE
                CASE cast(? as text)
                  WHEN 'GRACE' THEN status = 'ACTIVE' AND paid_until < now() AND paid_until + interval '3 days' >= now()
                  WHEN 'LOCKED' THEN (status = 'ACTIVE' AND paid_until + interval '3 days' < now()) OR (status = 'TRIAL' AND NOT in_setup AND trial_ends_at < now())
                  WHEN 'TRIAL3' THEN status = 'TRIAL' AND NOT in_setup AND trial_ends_at BETWEEN now() AND now() + interval '3 days'
                  WHEN 'PAID_MONTH' THEN paid_this_month
                  WHEN 'NEVER_PAID' THEN coalesce(paid_count, 0) = 0
                  WHEN 'SUSPENDED' THEN status = 'SUSPENDED'
                  ELSE true END
            )
            """;

    /** A search term as a LIKE pattern with its wildcards escaped (the same rule as the plain Workspaces list); under 2 letters means no search. */
    private static String like(String q) {
        String term = q == null ? null : q.trim();
        return term == null || term.length() < 2 ? null : "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private List<Object> baseArgs(String q, String sector, boolean demo, String filter) {
        String f = filter == null || filter.isBlank() ? null : filter.toUpperCase();
        if (f != null && !FILTERS.contains(f)) {
            throw ApiException.bad("Unknown filter");
        }
        List<Object> a = new ArrayList<>();
        a.add(Timestamp.from(monthStart()));
        a.add(demo);
        String pattern = like(q);
        a.add(pattern);
        a.add(pattern);
        a.add(pattern);
        a.add(pattern);
        a.add(blank(sector));
        a.add(blank(sector));
        a.add(f);
        return a;
    }

    /**
     * The plan each business pays for now. Paying ones are read from their own row; businesses on free days get the plan that fits their
     * setup, counted for all of them inside ONE transaction (the tenant setting is switched per business) instead of one transaction each.
     */
    private Map<UUID, String> billPlansOf(List<Map<String, Object>> rows) {
        Map<UUID, String> out = new LinkedHashMap<>();
        List<UUID> trials = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            if ("ACTIVE".equals(r.get("status")) && r.get("plan") != null && platform.plan((String) r.get("plan")) != null) {
                String next = (String) r.get("next_plan");
                out.put(id, next != null && platform.plan(next) != null ? next : (String) r.get("plan"));
            } else {
                trials.add(id);
            }
        }
        if (!trials.isEmpty()) {
            Map<UUID, String> fit = tx.execute(s -> {
                Map<UUID, String> m = new LinkedHashMap<>();
                for (UUID id : trials) {
                    jdbc.queryForList("SELECT set_config('app.tenant', ?, true)", id.toString());
                    m.put(id, billing.billPlan(id));
                }
                return m;
            });
            out.putAll(fit);
        }
        Map<UUID, String> ordered = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            ordered.put((UUID) r.get("id"), out.get((UUID) r.get("id")));
        }
        return ordered;
    }

    /** The plan a business pays for now: the one its next bill is for. */
    private String billPlanOf(Map<String, Object> r) {
        UUID id = (UUID) r.get("id");
        if ("ACTIVE".equals(r.get("status")) && r.get("plan") != null && platform.plan((String) r.get("plan")) != null) {
            String next = (String) r.get("next_plan");
            return next != null && platform.plan(next) != null ? next : (String) r.get("plan");
        }
        return asTenant(id, () -> billing.billPlan(id));
    }

    /**
     * The list: one page of businesses with plan, price a month, status, next bill date and last payment, and totals for the whole
     * filtered list (businesses, money a month, money in this month).
     */
    public Map<String, Object> workspaces(String q, String sector, boolean demo, String filter, Integer limit, String before) {
        int sz = limit == null ? 50 : Math.max(1, Math.min(100, limit));
        List<Object> args = baseArgs(q, sector, demo, filter);
        List<Object> pageArgs = new ArrayList<>(args);
        String cursorSql = "";
        if (before != null && !before.isBlank()) {
            int tilde = before.indexOf('~');
            try {
                if (tilde < 0) {
                    throw new IllegalArgumentException("no separator");
                }
                pageArgs.add(Timestamp.from(Instant.parse(before.substring(0, tilde))));
                pageArgs.add(UUID.fromString(before.substring(tilde + 1)));
            } catch (RuntimeException e) {
                throw ApiException.bad("Invalid cursor");
            }
            cursorSql = " WHERE (f.created_at, f.id) < (cast(? as timestamptz), cast(? as uuid))";
        }
        pageArgs.add(sz + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(BASE + "SELECT f.* FROM f" + cursorSql + " ORDER BY f.created_at DESC, f.id DESC LIMIT ?", pageArgs.toArray());
        boolean more = rows.size() > sz;
        List<Map<String, Object>> page = more ? rows.subList(0, sz) : rows;
        String next = null;
        if (more) {
            Map<String, Object> last = page.get(page.size() - 1);
            next = MICROS.format(at(last.get("created_at"))) + "~" + last.get("id");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rowsFor(page));
        out.put("next", next);
        out.put("totals", totals(args));
        return out;
    }

    private static final java.time.format.DateTimeFormatter MICROS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(java.time.ZoneOffset.UTC);

    private long count(List<Object> args) {
        return jdbc.queryForObject(BASE + "SELECT count(*) FROM f", Long.class, args.toArray());
    }

    private List<Map<String, Object>> rowsFor(List<Map<String, Object>> rows) {
        Map<UUID, String> planOf = billPlansOf(rows);
        Map<UUID, Offers.Price> prices = offers.prices(planOf, "MONTHLY");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> status = statusOf(r);
            String plan = planOf.get(id);
            Offers.Price p = prices.get(id);
            m.put("id", id.toString());
            m.put("slug", r.get("slug"));
            m.put("name", r.get("name"));
            m.put("sector", r.get("sector"));
            m.put("subSector", r.get("sub_sector"));
            m.put("brandColor", r.get("brand_color"));
            m.put("logoUrl", r.get("logo_url"));
            m.put("isDemo", r.get("is_demo"));
            m.put("plan", plan);
            m.put("planName", platform.planName((String) r.get("sector"), (String) r.get("sub_sector"), plan));
            m.put("pricePerMonth", p.amount() + p.credit());
            m.put("status", status);
            boolean paying = "ACTIVE".equals(r.get("status"));
            Instant next = paying ? at(r.get("paid_until")) : at(r.get("trial_ends_at"));
            m.put("nextBill", "SUSPENDED".equals(r.get("status")) ? null : next == null ? null : next.toString());
            m.put("lastPaymentAt", at(r.get("last_paid_at")) == null ? null : at(r.get("last_paid_at")).toString());
            m.put("lastPaymentAmount", r.get("last_amount") == null ? null : ((BigDecimal) r.get("last_amount")).longValue());
            m.put("lastPaymentMethod", r.get("last_via") == null ? null : methodLabel((String) r.get("last_via")));
            m.put("totalPaid", ((BigDecimal) r.get("total_paid")).longValue());
            m.put("ownerEmail", r.get("owner_email"));
            m.put("phone", r.get("phone"));
            m.put("lastActiveAt", at(r.get("last_active_at")) == null ? null : at(r.get("last_active_at")).toString());
            m.put("createdAt", at(r.get("created_at")).toString());
            out.add(m);
        }
        return out;
    }

    /** Totals for the whole filtered list: how many businesses, what the paying ones pay a month, and what came in this month. */
    private Map<String, Object> totals(List<Object> args) {
        List<Map<String, Object>> paying = jdbc.queryForList(BASE + "SELECT id, plan, next_plan FROM f WHERE status = 'ACTIVE' AND plan IS NOT NULL", args.toArray());
        Map<UUID, String> planOf = new LinkedHashMap<>();
        for (Map<String, Object> r : paying) {
            String plan = r.get("next_plan") != null && platform.plan((String) r.get("next_plan")) != null ? (String) r.get("next_plan") : (String) r.get("plan");
            if (platform.plan(plan) != null) {
                planOf.put((UUID) r.get("id"), plan);
            }
        }
        long monthly = 0;
        for (Offers.Price p : offers.prices(planOf, "MONTHLY").values()) {
            monthly += p.amount() + p.credit();
        }
        List<Object> a = new ArrayList<>(args);
        a.add(Timestamp.from(monthStart()));
        long in = jdbc.queryForObject(BASE + """
                SELECT coalesce(sum(r.amount), 0) FROM subscription_requests r JOIN f ON f.id = r.tenant_id
                WHERE r.status = 'PAID' AND r.paid_via IS DISTINCT FROM 'CREDIT' AND coalesce(r.paid_at, r.created_at) >= ?""", Long.class, a.toArray());
        long businesses = count(args);
        return Map.of("businesses", businesses, "moneyAMonth", monthly, "moneyInThisMonth", in);
    }

    /** The whole filtered list as CSV (no paging). */
    public String workspacesCsv(String q, String sector, boolean demo, String filter) {
        List<Object> args = baseArgs(q, sector, demo, filter);
        List<Map<String, Object>> raw = jdbc.queryForList(BASE + "SELECT * FROM f ORDER BY created_at DESC LIMIT 5000", args.toArray());
        StringBuilder sb = new StringBuilder("Business,Sector,Plan,Price a month (KES),Status,Next bill date,Last payment date,Last payment (KES),Total paid (KES),Owner email,Phone,Joined\n");
        for (Map<String, Object> r : rowsFor(raw)) {
            Map<?, ?> s = (Map<?, ?>) r.get("status");
            sb.append(csv(r.get("name"), r.get("subSector") != null ? r.get("subSector") : r.get("sector"), r.get("planName"), r.get("pricePerMonth"), s.get("text"),
                    date(r.get("nextBill")), date(r.get("lastPaymentAt")), r.get("lastPaymentAmount"), r.get("totalPaid"), r.get("ownerEmail"), r.get("phone"),
                    date(r.get("createdAt")))).append('\n');
        }
        return sb.toString();
    }

    // ---------- Money overview ----------

    /** What each paying business's next bill is, for many at once: the plan price after offers plus the extra lines waiting on the bill. */
    Map<UUID, Long> nextBillAmounts(Collection<UUID> ids, Map<UUID, String> planOf, Map<UUID, String> cycleOf) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        Map<UUID, Offers.Price> monthly = offers.prices(planOf, "MONTHLY");
        Map<UUID, String> yearlyOnes = new LinkedHashMap<>();
        for (UUID id : ids) {
            if ("ANNUAL".equals(cycleOf.get(id))) {
                yearlyOnes.put(id, planOf.get(id));
            }
        }
        Map<UUID, Offers.Price> yearly = offers.prices(yearlyOnes, "ANNUAL");
        Map<UUID, Long> extra = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT tenant_id, sum(amount) AS s FROM bill_lines WHERE paid_at IS NULL AND tenant_id = ANY (?) GROUP BY tenant_id",
                (Object) ids.toArray(new UUID[0]))) {
            extra.put((UUID) r.get("tenant_id"), num(r.get("s")));
        }
        for (UUID id : ids) {
            Offers.Price p = yearly.containsKey(id) ? yearly.get(id) : monthly.get(id);
            out.put(id, p.amount() + extra.getOrDefault(id, 0L));
        }
        return out;
    }

    /** This month's money: expected, received, outstanding, in grace and locked; by plan and by kind of business; the last 30 payments. */
    public Map<String, Object> overview() {
        Instant monthStart = monthStart();
        Instant monthEnd = ZonedDateTime.ofInstant(monthStart, NAIROBI).plusMonths(1).toInstant();
        List<Map<String, Object>> active = jdbc.queryForList("""
                SELECT t.id, t.sector, t.plan, t.next_plan, t.paid_until, t.status, t.in_setup, t.trial_ends_at,
                       (SELECT r.billing_cycle FROM subscription_requests r WHERE r.tenant_id = t.id AND r.status = 'PAID'
                        ORDER BY coalesce(r.paid_at, r.created_at) DESC LIMIT 1) AS last_cycle
                FROM tenants t WHERE NOT t.is_demo AND t.status = 'ACTIVE' AND t.paid_until IS NOT NULL AND t.plan IS NOT NULL""");
        Map<UUID, String> planOf = new LinkedHashMap<>();
        Map<UUID, String> cycleOf = new LinkedHashMap<>();
        for (Map<String, Object> r : active) {
            String plan = r.get("next_plan") != null && platform.plan((String) r.get("next_plan")) != null ? (String) r.get("next_plan") : (String) r.get("plan");
            if (platform.plan(plan) != null) {
                planOf.put((UUID) r.get("id"), plan);
                cycleOf.put((UUID) r.get("id"), cycleOf(r.get("last_cycle")));
            }
        }
        Map<UUID, Long> amount = nextBillAmounts(planOf.keySet(), planOf, cycleOf);
        Instant now = Instant.now();
        long outstanding = 0, grace = 0, locked = 0;
        int outstandingN = 0, graceN = 0, lockedN = 0;
        Map<String, long[]> byPlan = new TreeMap<>();
        Map<String, long[]> bySector = new TreeMap<>();
        for (Map<String, Object> r : active) {
            UUID id = (UUID) r.get("id");
            if (!amount.containsKey(id)) {
                continue;
            }
            long a = amount.get(id);
            Instant paid = at(r.get("paid_until"));
            // How much each kind of plan and business brings in a month: count and money.
            long[] bp = byPlan.computeIfAbsent(planOf.get(id), k -> new long[2]);
            bp[0]++;
            bp[1] += a;
            long[] bs = bySector.computeIfAbsent((String) r.get("sector"), k -> new long[2]);
            bs[0]++;
            bs[1] += a;
            if (paid.isBefore(monthEnd)) {
                outstanding += a;
                outstandingN++;
                if (paid.isBefore(now)) {
                    if (paid.plus(GRACE_DAYS, ChronoUnit.DAYS).isAfter(now)) {
                        grace += a;
                        graceN++;
                    } else {
                        locked += a;
                        lockedN++;
                    }
                }
            }
        }
        long lockedTrials = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE NOT is_demo AND status = 'TRIAL' AND NOT in_setup AND trial_ends_at < now()", Long.class);
        long received = jdbc.queryForObject("""
                SELECT coalesce(sum(amount), 0) FROM subscription_requests WHERE status = 'PAID' AND paid_via IS DISTINCT FROM 'CREDIT'
                AND coalesce(paid_at, created_at) >= ?""", Long.class, Timestamp.from(monthStart));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", ZonedDateTime.ofInstant(monthStart, NAIROBI).getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH));
        out.put("expected", received + outstanding);
        out.put("received", received);
        out.put("outstanding", outstanding);
        out.put("outstandingCount", outstandingN);
        out.put("grace", Map.of("count", graceN, "amount", grace));
        out.put("locked", Map.of("count", lockedN + lockedTrials, "paying", lockedN, "amount", locked));
        out.put("byPlan", byPlan.entrySet().stream().map(e -> Map.<String, Object>of("plan", e.getKey(), "name", platform.plan(e.getKey()).get("name"),
                "businesses", e.getValue()[0], "perMonth", e.getValue()[1])).toList());
        List<Map<String, Object>> sectorRows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : bySector.entrySet()) {
            sectorRows.add(Map.of("sector", e.getKey(), "businesses", e.getValue()[0], "perMonth", e.getValue()[1]));
        }
        out.put("bySector", sectorRows);
        out.put("last30", jdbc.queryForList("""
                SELECT r.id, r.tenant_id, t.name AS business, t.sector, t.sub_sector, r.plan, r.amount, r.paid_via, r.mpesa_receipt, coalesce(r.paid_at, r.created_at) AS paid_at, r.activated_by
                FROM subscription_requests r JOIN tenants t ON t.id = r.tenant_id
                WHERE r.status = 'PAID' AND r.paid_via IS DISTINCT FROM 'CREDIT' ORDER BY coalesce(r.paid_at, r.created_at) DESC LIMIT 30""").stream().map(r -> {
            Map<String, Object> m = row(r);
            m.put("planName", platform.planName((String) r.get("sector"), (String) r.get("sub_sector"), (String) r.get("plan")));
            m.put("method", methodLabel((String) r.get("paid_via")));
            return m;
        }).toList());
        return out;
    }
}
