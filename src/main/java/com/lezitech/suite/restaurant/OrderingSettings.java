package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ApiException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A restaurant's choices for ordering from the table, kept in {@code settings.ordering}:
 * <ul>
 *   <li>{@code selfOrder}: diners can order from the table QR (default on for restaurants and hotels)</li>
 *   <li>{@code payWhen}: READY (pay when the food is ready, default) or FIRST (pay before it goes to the kitchen)</li>
 *   <li>{@code tipPresets}: amounts offered after the custom box (default 300, 500, 1,000, 3,000)</li>
 *   <li>{@code tipShares}: how tips are shared, in percent: kitchen, business, server (default 45 / 10 / 45)</li>
 * </ul>
 * and loyalty points in {@code settings.loyalty} (off by default; the owner sets every rule).
 */
public final class OrderingSettings {

    public static final List<Integer> DEFAULT_TIPS = List.of(300, 500, 1000, 3000);
    public static final Map<String, Integer> DEFAULT_SHARES = Map.of("kitchen", 45, "business", 10, "server", 45);

    private OrderingSettings() {
    }

    /** The saved choices with defaults filled in, for the screens. */
    public static Map<String, Object> withDefaults(Object saved, String sector) {
        Map<String, Object> in = saved instanceof Map<?, ?> m ? cast(m) : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean dining = "restaurant".equals(sector) || "hotel".equals(sector);
        out.put("selfOrder", in.get("selfOrder") instanceof Boolean b ? b : dining);
        out.put("payWhen", "FIRST".equals(in.get("payWhen")) ? "FIRST" : "READY");
        out.put("tipPresets", in.get("tipPresets") instanceof List<?> l && !l.isEmpty() ? l : DEFAULT_TIPS);
        out.put("tipShares", in.get("tipShares") instanceof Map<?, ?> s ? s : DEFAULT_SHARES);
        return out;
    }

    /** Check what the owner sends; anything else is dropped. */
    public static Map<String, Object> clean(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw ApiException.bad("Ordering settings are missing");
        }
        Map<String, Object> in = cast(m);
        Map<String, Object> out = new LinkedHashMap<>();
        if (in.get("selfOrder") instanceof Boolean b) {
            out.put("selfOrder", b);
        }
        if (in.containsKey("payWhen")) {
            String w = String.valueOf(in.get("payWhen")).toUpperCase();
            if (!w.equals("READY") && !w.equals("FIRST")) {
                throw ApiException.bad("Choose when diners pay: when the food is ready, or before it goes to the kitchen");
            }
            out.put("payWhen", w);
        }
        if (in.get("tipPresets") instanceof List<?> l) {
            List<Integer> tips = new ArrayList<>();
            for (Object o : l) {
                int v = number(o, "A tip amount");
                if (v <= 0 || v > 100_000) {
                    throw ApiException.bad("Tip amounts must be between 1 and 100,000");
                }
                if (!tips.contains(v)) {
                    tips.add(v);
                }
            }
            if (tips.size() > 6) {
                throw ApiException.bad("Offer at most 6 tip amounts");
            }
            out.put("tipPresets", tips);
        }
        if (in.get("tipShares") instanceof Map<?, ?> s) {
            Map<String, Object> shares = cast(s);
            int kitchen = number(shares.getOrDefault("kitchen", 0), "The kitchen's share");
            int business = number(shares.getOrDefault("business", 0), "The business's share");
            int server = number(shares.getOrDefault("server", 0), "The server's share");
            if (kitchen < 0 || business < 0 || server < 0 || kitchen + business + server != 100) {
                throw ApiException.bad("Tip shares must add up to 100%");
            }
            out.put("tipShares", Map.of("kitchen", kitchen, "business", business, "server", server));
        }
        return out;
    }

    /** Loyalty rules: points per 100 spent, what a point is worth when used, the minimum to use, expiry in months (0 = never). */
    public static Map<String, Object> cleanLoyalty(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw ApiException.bad("Loyalty settings are missing");
        }
        Map<String, Object> in = cast(m);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("on", Boolean.TRUE.equals(in.get("on")));
        BigDecimal per100 = decimal(in.getOrDefault("pointsPer100", 1), "Points per 100 spent");
        BigDecimal value = decimal(in.getOrDefault("pointValue", 1), "The value of a point");
        int min = number(in.getOrDefault("minToRedeem", 100), "The minimum points to use");
        int expiry = number(in.getOrDefault("expiryMonths", 0), "Expiry");
        if (per100.signum() <= 0 || per100.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.bad("Points per 100 spent must be between 0.01 and 100");
        }
        if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw ApiException.bad("A point must be worth between 0.01 and 1,000");
        }
        if (min < 0 || expiry < 0 || expiry > 60) {
            throw ApiException.bad("Check the minimum points and the expiry (0 to 60 months)");
        }
        out.put("pointsPer100", per100);
        out.put("pointValue", value);
        out.put("minToRedeem", min);
        out.put("expiryMonths", expiry);
        return out;
    }

    private static int number(Object o, String what) {
        try {
            return new BigDecimal(String.valueOf(o)).intValueExact();
        } catch (RuntimeException e) {
            throw ApiException.bad(what + " must be a whole number");
        }
    }

    private static BigDecimal decimal(Object o, String what) {
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (RuntimeException e) {
            throw ApiException.bad(what + " must be a number");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
