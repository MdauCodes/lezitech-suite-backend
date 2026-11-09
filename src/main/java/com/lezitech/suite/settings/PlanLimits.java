package com.lezitech.suite.settings;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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

    public static final Map<String, Limits> BY_PLAN = Map.of(
            "LITE", new Limits(2, 1, 500),
            "GROWTH", new Limits(8, 3, 5000),
            "PRO", new Limits(30, 10, 0));

    private static final Map<String, String> NAME = Map.of("LITE", "Lite", "GROWTH", "Growth", "PRO", "Pro");
    private static final Map<String, String> NEXT = Map.of("LITE", "GROWTH", "GROWTH", "PRO");

    private final JdbcTemplate jdbc;

    public PlanLimits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The plan whose limits apply now: the paid plan, or Pro while on trial. */
    public String effectivePlan() {
        Map<String, Object> t = jdbc.queryForMap("SELECT plan, status FROM tenants WHERE id = ?", TenantContext.tenantId());
        String plan = (String) t.get("plan");
        return "ACTIVE".equals(t.get("status")) && BY_PLAN.containsKey(plan) ? plan : "PRO";
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
        check("people who can sign in", "users", users(), adding, BY_PLAN.get(effectivePlan()).users());
    }

    public void checkLocations(int adding) {
        check("branches", "locations", locations(), adding, BY_PLAN.get(effectivePlan()).locations());
    }

    public void checkItems(long current, int adding) {
        check("products and services", "items", current, adding, BY_PLAN.get(effectivePlan()).items());
    }

    public void checkItems(int adding) {
        checkItems(items(), adding);
    }

    private void check(String what, String key, long used, int adding, int limit) {
        if (limit == 0 || used + adding <= limit) {
            return;
        }
        String plan = effectivePlan();
        String next = NEXT.get(plan);
        String more = next == null ? " Talk to LeziTech about a bigger package."
                : " " + NAME.get(next) + " allows " + (BY_PLAN.get(next).get(key) == 0 ? "unlimited " + what : "up to " + BY_PLAN.get(next).get(key) + " " + what)
                + ". The owner can upgrade under Your plan.";
        throw new ApiException(HttpStatus.FORBIDDEN, "PLAN_LIMIT",
                "Your " + NAME.get(plan) + " plan includes " + limit + " " + what + " and you have " + used + "." + more);
    }

    public Map<String, Object> usage() {
        Limits l = BY_PLAN.get(effectivePlan());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", effectivePlan());
        m.put("users", Map.of("used", users(), "limit", l.users()));
        m.put("locations", Map.of("used", locations(), "limit", l.locations()));
        m.put("items", Map.of("used", items(), "limit", l.items()));
        return m;
    }
}
