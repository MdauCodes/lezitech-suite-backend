package com.lezitech.suite.growth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

/** The ad and page a visitor came from, as sent by the website (first touch), cleaned before it is stored. */
public final class Acquisition {

    static final List<String> KEYS = List.of("utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term",
            "fbclid", "fbc", "fbp", "gclid", "ref", "landing", "referrer", "page", "first_seen");

    private Acquisition() {
    }

    /** Only known keys, each clipped; null when nothing useful was sent. */
    public static ObjectNode clean(ObjectMapper json, Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        ObjectNode out = json.createObjectNode();
        for (String k : KEYS) {
            Object v = m.get(k);
            if (v != null && !v.toString().isBlank()) {
                String s = v.toString().trim();
                out.put(k, s.length() > 300 ? s.substring(0, 300) : s);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** What Meta needs about the browser that converted: its cookies, address and the page. */
    public static MetaConversions.Visitor visitor(JsonNode acq, HttpServletRequest req) {
        String ip = null;
        String ua = null;
        if (req != null) {
            String fwd = req.getHeader("X-Forwarded-For");
            ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
            ua = req.getHeader("User-Agent");
        }
        return new MetaConversions.Visitor(text(acq, "fbc"), text(acq, "fbp"), ip, ua, text(acq, "page"));
    }

    public static String text(JsonNode n, String key) {
        if (n == null || !n.hasNonNull(key)) {
            return null;
        }
        String s = n.get(key).asText();
        return s.isBlank() ? null : s;
    }
}
