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
 * the plans (name, monthly price, people, branches, products) and which tools each includes. Read often, so kept for 30 seconds.
 */
@Component
public class PlatformSettings {

    /** The plans until LeziTech changes them. Every plan has the screens for the business's own kind; the tools differ (PlanFeatures). */
    public static final List<Map<String, Object>> DEFAULT_PLANS = List.of(
            plan("LITE", "Lite", 2650, 3, 1, 0, "Everything to run one shop, kiosk or café day to day. 1 branch, 3 staff"),
            plan("GROWTH", "Growth", 4500, 8, 3, 0, "Adds suppliers, back-office reports, sales leads, quotes, loyalty and phone ordering. 3 branches, 8 staff"),
            plan("PRO", "Pro", 9000, 30, 10, 0, "Adds reports across branches, full history, owner powers for managers and priority support. 10 branches, 30 staff"));

    private static final List<String> PLAN_ORDER = List.of("LITE", "GROWTH", "PRO");

    public static final int MIN_TRIAL = 3;
    public static final int MAX_TRIAL = 60;

    private static Map<String, Object> plan(String code, String name, int monthly, int users, int locations, int items, String blurb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("monthly", monthly);
        m.put("users", users);
        m.put("locations", locations);
        m.put("items", items);
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
            jdbc.queryForList("SELECT key, value::text AS value FROM platform_settings WHERE key IN ('trialDays', 'plans', 'planFeatures', 'minClientRelease', 'latestClientRelease', 'reloadMessage')").forEach(r -> fresh.put((String) r.get("key"), (String) r.get("value")));
            cache = c = fresh;
            cachedAt = System.currentTimeMillis();
        }
        return c;
    }

    /** Read the settings again on next use (after a change made straight in the database). */
    public void forget() {
        cache = null;
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
            int monthly = num(p.get("monthly"), "price"), users = num(p.get("users"), "staff"), locations = num(p.get("locations"), "branches"),
                    items = 0; // products are never limited
            if (monthly < 0 || users < 0 || locations < 0 || items < 0) {
                throw ApiException.bad("Check the numbers for " + name + " (0 staff or branches means no limit)");
            }
            String blurb = String.valueOf(p.getOrDefault("blurb", "")).trim();
            clean.add(plan(code, name, monthly, users, locations, items, blurb.length() > 200 ? blurb.substring(0, 200) : blurb));
        }
        try {
            put("plans", json.writeValueAsString(clean), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The tools a plan includes: LeziTech's choice, or the defaults (each tool from its starting plan upwards). */
    public Set<String> planFeatures(String code) {
        String v = all().get("planFeatures");
        if (v != null) {
            try {
                Map<String, List<String>> m = json.readValue(v, new TypeReference<>() { });
                if (m.containsKey(code)) {
                    Set<String> out = new java.util.LinkedHashSet<>(m.get(code));
                    out.retainAll(PlanFeatures.KEYS);
                    return out;
                }
            } catch (Exception e) {
                // unreadable: defaults
            }
        }
        return defaultFeatures(code);
    }

    public static Set<String> defaultFeatures(String code) {
        int rank = PLAN_ORDER.indexOf(code);
        Set<String> out = new java.util.LinkedHashSet<>();
        for (PlanFeatures.Feature f : PlanFeatures.CATALOG) {
            if (rank >= PLAN_ORDER.indexOf(f.plan())) {
                out.add(f.key());
            }
        }
        return out;
    }

    /** Which tools each plan includes, from the control panel's tick grid. */
    public void setPlanFeatures(Map<String, List<String>> byPlan, String by) {
        Map<String, List<String>> clean = new LinkedHashMap<>();
        for (String code : PLAN_ORDER) {
            List<String> keys = byPlan == null ? null : byPlan.get(code);
            if (keys == null) {
                throw ApiException.bad("Send the tools for Lite, Growth and Pro");
            }
            for (String k : keys) {
                if (!PlanFeatures.KEYS.contains(k)) {
                    throw ApiException.bad("Unknown tool: " + k);
                }
            }
            clean.put(code, PlanFeatures.CATALOG.stream().map(PlanFeatures.Feature::key).filter(keys::contains).toList());
        }
        try {
            put("planFeatures", json.writeValueAsString(clean), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final java.time.format.DateTimeFormatter STAMP = java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT);

    /** True only for exactly 14 digits that are a real date and time, as yyyyMMddHHmmss (UTC). */
    public static boolean isStamp(String s) {
        if (s == null || s.length() != 14) {
            return false;
        }
        for (int i = 0; i < 14; i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        try {
            java.time.LocalDateTime.parse(s, STAMP);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private String unquoted(String key) {
        String v = all().get(key);
        if (v == null || v.equals("null")) {
            return null;
        }
        try {
            Object o = json.readValue(v, Object.class);
            return o == null ? null : String.valueOf(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** The oldest app version still allowed to run; anything older is told to reload. Null when unset. */
    public String minClientRelease() {
        return unquoted("minClientRelease");
    }

    /** The newest app version known to the platform. Null when unset. */
    public String latestClientRelease() {
        return unquoted("latestClientRelease");
    }

    /** The plain-words note shown with a reload request. Null when unset. */
    public String reloadMessage() {
        return unquoted("reloadMessage");
    }

    /** Operator path. A null argument leaves that setting alone; "clear" removes it. */
    public void setClientRelease(String latest, String min, String message, String by) {
        if (latest != null && !"clear".equals(latest) && !isStamp(latest)) {
            throw ApiException.bad("The newest version must be 14 digits like 20261121093000");
        }
        if (min != null && !"clear".equals(min) && !isStamp(min)) {
            throw ApiException.bad("The oldest allowed version must be 14 digits like 20261121093000");
        }
        if (message != null && message.length() > 200) {
            throw ApiException.bad("Keep the reload note to 200 characters or fewer");
        }
        putOrClear("latestClientRelease", latest, by);
        putOrClear("minClientRelease", min, by);
        putOrClear("reloadMessage", message, by);
    }

    private void putOrClear(String key, String value, String by) {
        if (value == null) {
            return;
        }
        try {
            put(key, "clear".equals(value) ? "null" : json.writeValueAsString(value), by);
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
