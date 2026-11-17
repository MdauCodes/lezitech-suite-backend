package com.lezitech.suite.platform;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The extra tools a plan adds on top of what every business gets (the till, stock counts and the screens for its own
 * kind of business are on every plan). Which plan includes which tool is LeziTech's choice in the control panel;
 * the defaults are below. While on the free trial everything is open.
 *
 * Tools that hold records (suppliers, leads, quotes) are closed for changes only, so a business that moves to a
 * smaller plan can still read what it built. Reports are closed entirely.
 */
@Component
public class PlanFeatures {

    /** A request rule: these methods on these paths need the tool. */
    public record Rule(Set<String> methods, Pattern path) {
        boolean matches(String method, String uri) {
            return methods.contains(method) && path.matcher(uri).matches();
        }
    }

    public record Feature(String key, String label, String why, String plan, List<Rule> rules) {
    }

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> ALL = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private static Rule writes(String regex) {
        return new Rule(WRITES, Pattern.compile(regex));
    }

    private static Rule all(String regex) {
        return new Rule(ALL, Pattern.compile(regex));
    }

    /** In the order shown on plan pages. Rules: null where the check sits in the code (quotes, owner powers...). */
    public static final List<Feature> CATALOG = List.of(
            new Feature("purchasing", "Buying from suppliers", "Order stock, record deliveries and see what you owe each supplier.", "GROWTH",
                    List.of(writes("/api/(purchases|suppliers)(/.*)?"))),
            new Feature("transfers", "Moving stock between branches", "Send stock from the store to a shop, or between branches, with a record of who moved what.", "GROWTH",
                    List.of(writes("/api/stock/transfer"))),
            new Feature("backOffice", "Back-office reports", "Stock value, profit by category, purchases and written-off stock.", "GROWTH",
                    List.of(all("/api/reports/back-office.*"))),
            new Feature("profitLoss", "Profit & loss", "What came in, what it cost, what you spent and what you kept, for any dates.", "GROWTH",
                    List.of(all("/api/reports/profit-loss.*"))),
            new Feature("crm", "Customers & sales leads", "Follow up people who may buy, with reminders, so no sale is forgotten.", "GROWTH",
                    List.of(writes("/api/(pipeline|deals|activities)(/.*)?"))),
            new Feature("quotes", "Quotes and invoices", "Send prices before a sale, then turn the quote into the sale.", "GROWTH", List.of()),
            new Feature("statements", "Customer statements", "A customer's purchases, payments and balance for any dates, sent on WhatsApp or email.", "GROWTH",
                    List.of(all("/api/customers/[^/]+/statement(/.*)?"))),
            new Feature("loyalty", "Loyalty points", "Customers earn points as they buy and spend them at the till.", "GROWTH",
                    List.of(writes("/api/customers/[^/]+/loyalty/adjust"), writes("/api/orders/[^/]+/loyalty/redeem"))),
            new Feature("commissions", "Staff commissions", "What each staff member earned, what was paid and what is still owed.", "GROWTH",
                    List.of(all("/api/reports/commissions(/.*)?"))),
            new Feature("tips", "Tips report", "Tips per waiter for any day or week.", "GROWTH",
                    List.of(all("/api/reports/tips.*"))),
            new Feature("onlineOrders", "Ordering from a phone", "Guests order from the QR code at the table, or order delivery, without a waiter.", "GROWTH", List.of()),
            new Feature("export", "Download reports to Excel", "Take any report out as a spreadsheet for your accountant.", "GROWTH", List.of()),
            new Feature("branchReports", "Reports across all branches", "Every branch's sales side by side, so you see which one is doing well.", "PRO", List.of()),
            new Feature("history", "Full history of changes", "Who changed prices, stock, refunds and staff, and when.", "PRO",
                    List.of(all("/api/activity.*"))),
            new Feature("ownerPowers", "Owner powers for a manager", "Let an operations manager or accountant handle the plan, payments and staff rights.", "PRO", List.of()),
            new Feature("nightPdf", "End-of-day summary by email", "The day's sales, cash and stock as a PDF in the owner's inbox each night.", "PRO",
                    List.of(all("/api/night/[^/]+/(pdf|send)"), writes("/api/night/settings"))),
            new Feature("prioritySupport", "Priority WhatsApp support", "Your questions go to the front of the line, with help setting up.", "PRO", List.of()));

    public static final Set<String> KEYS;

    static {
        Set<String> k = new java.util.LinkedHashSet<>();
        CATALOG.forEach(f -> k.add(f.key()));
        KEYS = java.util.Collections.unmodifiableSet(k);
    }

    public static Feature feature(String key) {
        return CATALOG.stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
    }

    private final JdbcTemplate jdbc;
    private final PlatformSettings platform;

    public PlanFeatures(JdbcTemplate jdbc, PlatformSettings platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    /** The plan code whose tools apply to a business now: its paid plan, or null while it's on trial (everything open). */
    public String paidPlan(UUID tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT plan, status FROM tenants WHERE id = ?", tenant);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> t = rows.get(0);
        String plan = t.get("plan") == null ? null : String.valueOf(t.get("plan"));
        return "ACTIVE".equals(t.get("status")) && plan != null && platform.plan(plan) != null ? plan : null;
    }

    /** The tools a business may use now (all of them on trial). */
    public Set<String> allowed(UUID tenant) {
        String plan = paidPlan(tenant);
        return plan == null ? KEYS : platform.planFeatures(plan);
    }

    public boolean has(UUID tenant, String key) {
        return allowed(tenant).contains(key);
    }

    public boolean has(String key) {
        return has(TenantContext.tenantId(), key);
    }

    /** Refuse with a plain message naming the cheapest plan that has the tool. */
    public void require(UUID tenant, String key) {
        if (!has(tenant, key)) {
            throw refusal(tenant, key);
        }
    }

    public void require(String key) {
        require(TenantContext.tenantId(), key);
    }

    public ApiException refusal(UUID tenant, String key) {
        Feature f = feature(key);
        String plan = paidPlan(tenant);
        String label = f == null ? key : f.label();
        String where = platform.plans().stream().filter(p -> platform.planFeatures((String) p.get("code")).contains(key))
                .map(p -> (String) p.get("name")).findFirst().orElse(null);
        String yours = plan == null ? "your plan" : "your " + platform.plan(plan).get("name") + " plan";
        String more = where == null ? " Talk to LeziTech about it." : " It comes with the " + where + " plan. The owner can upgrade under Your plan.";
        return new ApiException(HttpStatus.FORBIDDEN, "PLAN_FEATURE", label + " is not on " + yours + "." + more);
    }

    /** For a request: the tool it needs and the business lacks, or null if it may go ahead. */
    public String missingFor(UUID tenant, String method, String uri) {
        String needs = null;
        for (Feature f : CATALOG) {
            for (Rule r : f.rules()) {
                if (r.matches(method, uri)) {
                    needs = f.key();
                    break;
                }
            }
            if (needs != null) {
                break;
            }
        }
        return needs == null || has(tenant, needs) ? null : needs;
    }

    /** Screens that are a tool: a business that has the screen needs the tool. */
    private static final Map<String, String> TOOL_OF_SCREEN = Map.of("crm", "crm", "quotes", "quotes", "purchasing", "purchasing");

    /**
     * The tools this business set out to use: what it chose at sign-up, plus the tool screens it has. This is its
     * "setup", which decides the plan that keeps everything running after the free days.
     */
    public Set<String> needed(UUID tenant) {
        Set<String> out = new java.util.LinkedHashSet<>();
        try {
            String raw = jdbc.queryForObject("SELECT settings::text FROM tenants WHERE id = ?", String.class, tenant);
            com.fasterxml.jackson.databind.JsonNode s = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            s.path("chosenTools").forEach(n -> {
                if (KEYS.contains(n.asText())) {
                    out.add(n.asText());
                }
            });
            s.path("modules").forEach(n -> {
                String t = TOOL_OF_SCREEN.get(n.asText());
                if (t != null) {
                    out.add(t);
                }
            });
        } catch (Exception e) {
            // unreadable settings: nothing extra
        }
        return out;
    }

    /** The cheapest plan that holds this business's setup: its tools, its staff and its branches. */
    public Map<String, Object> fittingPlan(UUID tenant) {
        Set<String> need = needed(tenant);
        long users = jdbc.queryForObject("SELECT count(*) FROM users WHERE tenant_id = ? AND active", Long.class, tenant);
        long branches = jdbc.queryForObject("SELECT count(*) FROM locations WHERE tenant_id = ? AND parent_id IS NULL", Long.class, tenant);
        List<Map<String, Object>> plans = platform.plans();
        for (Map<String, Object> p : plans) {
            int u = ((Number) p.get("users")).intValue(), l = ((Number) p.get("locations")).intValue();
            if (platform.planFeatures((String) p.get("code")).containsAll(need) && (u == 0 || users <= u) && (l == 0 || branches <= l)) {
                return p;
            }
        }
        return plans.get(plans.size() - 1);
    }

    /** The catalogue for screens: key, label, what it's for, and which plans include it. */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Feature f : CATALOG) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.key());
            m.put("label", f.label());
            m.put("why", f.why());
            m.put("plans", platform.plans().stream().map(p -> (String) p.get("code"))
                    .filter(c -> platform.planFeatures(c).contains(f.key())).toList());
            out.add(m);
        }
        return out;
    }
}
