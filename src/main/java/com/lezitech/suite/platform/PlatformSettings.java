package com.lezitech.suite.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Things LeziTech changes from the control panel without a new release: how many free days a new business gets, and
 * the plans (name, monthly price, people, branches, products, how many parts). Read often, so kept for 30 seconds.
 */
@Component
public class PlatformSettings {

    /** The plans until LeziTech changes them. parts: how many of selling / customers & leads / buying from suppliers. */
    public static final List<Map<String, Object>> DEFAULT_PLANS = List.of(
            plan("LITE", "Lite", 2650, 3, 1, 500, 1, "Selling with stock counts, or one other part. 1 branch, 3 people signing in"),
            plan("GROWTH", "Growth", 4500, 8, 3, 5000, 2, "Selling plus customers & leads, or plus buying from suppliers. 3 branches, 8 people"),
            plan("PRO", "Pro", 9000, 30, 10, 0, 3, "Everything: selling, customers & leads, and buying from suppliers. 10 branches, 30 people"));

    public static final int MIN_TRIAL = 3;
    public static final int MAX_TRIAL = 60;

    private static Map<String, Object> plan(String code, String name, int monthly, int users, int locations, int items, int parts, String blurb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("monthly", monthly);
        m.put("users", users);
        m.put("locations", locations);
        m.put("items", items);
        m.put("parts", parts);
        m.put("blurb", blurb);
        return m;
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final int defaultTrialDays;
    private volatile Map<String, String> cache;
    private volatile long cachedAt;

    public PlatformSettings(JdbcTemplate jdbc, ObjectMapper json, @Value("${suite.trial-days}") int defaultTrialDays) {
        this.jdbc = jdbc;
        this.json = json;
        this.defaultTrialDays = defaultTrialDays;
    }

    private Map<String, String> all() {
        Map<String, String> c = cache;
        if (c == null || System.currentTimeMillis() - cachedAt > 30_000) {
            Map<String, String> fresh = new LinkedHashMap<>();
            jdbc.queryForList("SELECT key, value::text AS value FROM platform_settings WHERE key IN ('trialDays', 'plans')").forEach(r -> fresh.put((String) r.get("key"), (String) r.get("value")));
            cache = c = fresh;
            cachedAt = System.currentTimeMillis();
        }
        return c;
    }

    /** Free days for a business that signs up (or is handed over) from now on. */
    public int trialDays() {
        try {
            String v = all().get("trialDays");
            return v == null ? defaultTrialDays : Math.max(MIN_TRIAL, Math.min(MAX_TRIAL, Integer.parseInt(v)));
        } catch (RuntimeException e) {
            return defaultTrialDays;
        }
    }

    /** The plans, cheapest first. */
    public List<Map<String, Object>> plans() {
        String v = all().get("plans");
        if (v == null) {
            return DEFAULT_PLANS;
        }
        try {
            List<Map<String, Object>> p = json.readValue(v, new TypeReference<>() { });
            p.sort(Comparator.comparingInt(x -> ((Number) x.get("monthly")).intValue()));
            return p;
        } catch (Exception e) {
            return DEFAULT_PLANS;
        }
    }

    public Map<String, Object> plan(String code) {
        return plans().stream().filter(p -> code.equals(p.get("code"))).findFirst().orElse(null);
    }

    public void setTrialDays(int days, String by) {
        if (days < MIN_TRIAL || days > MAX_TRIAL) {
            throw ApiException.bad("Free days must be between " + MIN_TRIAL + " and " + MAX_TRIAL);
        }
        put("trialDays", String.valueOf(days), by);
    }

    /** Change the plans' names, prices and limits. The three plan codes stay (businesses are on them). */
    public void setPlans(List<Map<String, Object>> plans, String by) {
        if (plans == null || plans.size() != 3) {
            throw ApiException.bad("Send the three plans");
        }
        List<Map<String, Object>> clean = new ArrayList<>();
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (Map<String, Object> p : plans) {
            String code = String.valueOf(p.get("code"));
            if (!Set.of("LITE", "GROWTH", "PRO").contains(code) || !codes.add(code)) {
                throw ApiException.bad("Plans are LITE, GROWTH and PRO, once each");
            }
            String name = String.valueOf(p.getOrDefault("name", "")).trim();
            if (name.isEmpty() || name.length() > 30) {
                throw ApiException.bad("Give each plan a short name");
            }
            int monthly = num(p.get("monthly"), "price"), users = num(p.get("users"), "people"), locations = num(p.get("locations"), "branches"),
                    items = num(p.get("items"), "products"), parts = num(p.get("parts"), "parts");
            if (monthly < 0 || users < 0 || locations < 0 || items < 0 || parts < 1 || parts > 3) {
                throw ApiException.bad("Check the numbers for " + name + " (parts are 1 to 3; 0 people, branches or products means no limit)");
            }
            String blurb = String.valueOf(p.getOrDefault("blurb", "")).trim();
            clean.add(plan(code, name, monthly, users, locations, items, parts, blurb.length() > 200 ? blurb.substring(0, 200) : blurb));
        }
        try {
            put("plans", json.writeValueAsString(clean), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int num(Object v, String what) {
        try {
            return new java.math.BigDecimal(String.valueOf(v)).intValueExact();
        } catch (RuntimeException e) {
            throw ApiException.bad("Type a whole number for " + what);
        }
    }

    private void put(String key, String value, String by) {
        jdbc.update("""
                INSERT INTO platform_settings (key, value, updated_at) VALUES (?, cast(? as jsonb), now())
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", key, value);
        cache = null;
    }
}
