package com.lezitech.suite.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Typed reads from a JSON request body, with clear 400s for missing fields. */
public final class Body {

    private final Map<String, Object> m;

    public Body(Map<String, Object> m) {
        this.m = m == null ? Map.of() : m;
    }

    public boolean has(String k) {
        return m.get(k) != null && !"".equals(m.get(k));
    }

    public String str(String k) {
        Object v = m.get(k);
        if (v == null || v.toString().isBlank()) {
            throw ApiException.bad(k + " is required");
        }
        return v.toString().trim();
    }

    public String str(String k, String fallback) {
        return has(k) ? m.get(k).toString().trim() : fallback;
    }

    public BigDecimal num(String k) {
        if (!has(k)) {
            throw ApiException.bad(k + " is required");
        }
        return new BigDecimal(m.get(k).toString());
    }

    public BigDecimal num(String k, BigDecimal fallback) {
        return has(k) ? new BigDecimal(m.get(k).toString()) : fallback;
    }

    public Integer integer(String k, Integer fallback) {
        return has(k) ? Integer.valueOf(new BigDecimal(m.get(k).toString()).intValue()) : fallback;
    }

    public boolean bool(String k, boolean fallback) {
        return has(k) ? Boolean.parseBoolean(m.get(k).toString()) : fallback;
    }

    public Instant instant(String k) {
        return Instant.parse(str(k));
    }

    public LocalDate date(String k) {
        return has(k) ? LocalDate.parse(m.get(k).toString().substring(0, 10)) : null;
    }

    public Object raw(String k) {
        return m.get(k);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(String k) {
        Object v = m.get(k);
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String k) {
        Object v = m.get(k);
        return v instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
    }
}
