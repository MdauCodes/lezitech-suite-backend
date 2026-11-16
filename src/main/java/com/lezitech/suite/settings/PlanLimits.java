package com.lezitech.suite.settings;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each plan includes: people who can sign in, branches (top-level locations; a shop's back
 * store doesn't count) and products. A free trial gets everything Pro has, so owners can try it all.
 * Going over the limit is refused with a message that says which plan allows more.
 */
@Component
public class PlanLimits {

    /** 0 means no limit. */
    public record Limits(int users, int locations, int items) {
        int get(String key) {
            return switch (key) {
                case "users" -> users;
                case "locations" -> locations;
                default -> items;
            };
        }
    }

    private Limits limitsOf(String code) {
        Map<String, Object> p = platform.plan(code);
        if (p == null) {
            return new Limits(0, 0, 0);
        }
        return new Limits(((Number) p.get("users")).intValue(), ((Number) p.get("locations")).intValue(), ((Number) p.get("items")).intValue());
    }

    private String nameOf(String code) {
        Map<String, Object> p = platform.plan(code);
        return p == null ? code : (String) p.get("name");
    }

    /** The next plan up (by price), or null for the top one. */
    private String nextOf(String code) {
        List<Map<String, Object>> plans = platform.plans();
        for (int i = 0; i < plans.size() - 1; i++) {
            if (code.equals(plans.get(i).get("code"))) {
                return (String) plans.get(i + 1).get("code");
            }
        }
        return null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlatformSettings platform;

    private final JdbcTemplate jdbc;

    public PlanLimits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The plan whose limits apply now: the paid plan, or Pro while on trial. */
    public String effectivePlan() {
        Map<String, Object> t = jdbc.queryForMap("SELECT plan, status FROM tenants WHERE id = ?", TenantContext.tenantId());
        String plan = (String) t.get("plan");
        return "ACTIVE".equals(t.get("status")) && platform.plan(String.valueOf(plan)) != null ? plan : "PRO";
    }

    public long users() {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE tenant_id = ? AND active", Long.class, TenantContext.tenantId());
    }

    public long locations() {
        return jdbc.queryForObject("SELECT count(*) FROM locations WHERE tenant_id = ? AND parent_id IS NULL", Long.class, TenantContext.tenantId());
    }

    public long items() {
        return jdbc.queryForObject("SELECT count(*) FROM items WHERE tenant_id = ? AND active", Long.class, TenantContext.tenantId());
    }

    public void checkUsers(int adding) {
        check("people who can sign in", "users", users(), adding, limitsOf(effectivePlan()).users());
    }

    public void checkLocations(int adding) {
        check("branches", "locations", locations(), adding, limitsOf(effectivePlan()).locations());
    }

    public void checkItems(long current, int adding) {
        check("products and services", "items", current, adding, limitsOf(effectivePlan()).items());
    }

    public void checkItems(int adding) {
        checkItems(items(), adding);
    }

    private void check(String what, String key, long used, int adding, int limit) {
        if (limit == 0 || used + adding <= limit) {
            return;
        }
        String plan = effectivePlan();
        String next = nextOf(plan);
        String more = next == null ? " Talk to LeziTech about a bigger package."
                : " " + nameOf(next) + " allows " + (limitsOf(next).get(key) == 0 ? "unlimited " + what : "up to " + limitsOf(next).get(key) + " " + what)
                + ". The owner can upgrade under Your plan.";
        throw new ApiException(HttpStatus.FORBIDDEN, "PLAN_LIMIT",
                "Your " + nameOf(plan) + " plan includes " + limit + " " + what + " and you have " + used + "." + more);
    }

    public Map<String, Object> usage() {
        Limits l = limitsOf(effectivePlan());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", effectivePlan());
        m.put("users", Map.of("used", users(), "limit", l.users()));
        m.put("locations", Map.of("used", locations(), "limit", l.locations()));
        m.put("items", Map.of("used", items(), "limit", l.items()));
        return m;
    }
}
