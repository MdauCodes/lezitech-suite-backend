package com.lezitech.suite.growth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells Meta (Conversions API) when an ad visitor becomes a lead, starts a trial and pays, so Meta can find more
 * people who pay rather than people who click. Events are queued in marketing_events and sent every few seconds;
 * the website's pixel sends the same events with the same event_id and Meta keeps one.
 *
 * Email and phone are SHA-256 hashed before they are stored or sent, as Meta requires. Without META_PIXEL_ID and
 * META_CAPI_TOKEN nothing is sent (events are kept as OFF so the control panel can still count them).
 */
@Component
public class MetaConversions {

    private static final Logger log = LoggerFactory.getLogger(MetaConversions.class);

    /** What the website knows about the visitor: browser cookies, address and the page they converted on. */
    public record Visitor(String fbc, String fbp, String ip, String userAgent, String sourceUrl) {
        public static final Visitor NONE = new Visitor(null, null, null, null, null);
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String pixelId;
    private final String token;
    private final String testCode;
    private volatile String graph;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public MetaConversions(JdbcTemplate jdbc, ObjectMapper json,
                           @Value("${suite.meta.pixel-id:}") String pixelId,
                           @Value("${suite.meta.capi-token:}") String token,
                           @Value("${suite.meta.test-event-code:}") String testCode,
                           @Value("${suite.meta.graph-url:https://graph.facebook.com/v23.0}") String graph) {
        this.jdbc = jdbc;
        this.json = json;
        this.pixelId = pixelId.trim();
        this.token = token.trim();
        this.testCode = testCode.trim();
        this.graph = graph.replaceAll("/+$", "");
    }

    public boolean on() {
        return !pixelId.isEmpty() && !token.isEmpty();
    }

    /** Tests point this at a fake Graph API. */
    public void useGraphUrl(String url) {
        this.graph = url.replaceAll("/+$", "");
    }

    /**
     * Queue one conversion. eventId comes from the browser when it fired the pixel too (so Meta de-duplicates);
     * otherwise a new one is made. value is in KES (0 for leads and trials).
     */
    public void track(String eventName, String eventId, UUID tenantId, String email, String phone, Visitor v, BigDecimal value) {
        try {
            Visitor visitor = v == null ? Visitor.NONE : v;
            ObjectNode e = json.createObjectNode();
            e.put("event_name", eventName);
            e.put("event_time", Instant.now().getEpochSecond());
            e.put("event_id", eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId.trim());
            e.put("action_source", "website");
            if (visitor.sourceUrl() != null) {
                e.put("event_source_url", visitor.sourceUrl());
            }
            ObjectNode user = e.putObject("user_data");
            if (email != null && !email.isBlank()) {
                user.putArray("em").add(sha256(email.trim().toLowerCase()));
            }
            String ph = phoneDigits(phone);
            if (ph != null) {
                user.putArray("ph").add(sha256(ph));
            }
            if (tenantId != null) {
                user.putArray("external_id").add(sha256(tenantId.toString()));
            }
            putIf(user, "fbc", visitor.fbc());
            putIf(user, "fbp", visitor.fbp());
            putIf(user, "client_ip_address", visitor.ip());
            putIf(user, "client_user_agent", visitor.userAgent());
            user.putArray("country").add(sha256("ke"));
            ObjectNode custom = e.putObject("custom_data");
            custom.put("currency", "KES");
            custom.put("value", value == null ? BigDecimal.ZERO : value);
            jdbc.update("INSERT INTO marketing_events (tenant_id, event_name, event_id, payload, status) VALUES (?, ?, ?, cast(? as jsonb), ?)",
                    tenantId, eventName, e.get("event_id").asText(), e.toString(), on() ? "PENDING" : "OFF");
        } catch (RuntimeException ex) {
            // Never let tracking break a signup or a payment.
            log.warn("Couldn't queue the {} conversion: {}", eventName, ex.getMessage());
        }
    }

    /** Sends queued events in batches. Meta accepts events up to 7 days old; older ones are given up. */
    @Scheduled(fixedDelayString = "${suite.meta.send-every-ms:15000}", initialDelay = 20000)
    public void sendPending() {
        if (!on()) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, payload::text AS payload, attempts, created_at FROM marketing_events
                WHERE status = 'PENDING' ORDER BY created_at LIMIT 200""");
        if (rows.isEmpty()) {
            return;
        }
        ArrayNode data = json.createArrayNode();
        for (Map<String, Object> r : rows) {
            try {
                data.add(json.readTree((String) r.get("payload")));
            } catch (Exception e) {
                data.addObject();
            }
        }
        ObjectNode body = json.createObjectNode();
        body.set("data", data);
        if (!testCode.isEmpty()) {
            body.put("test_event_code", testCode);
        }
        String error = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(graph + "/" + pixelId + "/events?access_token=" + token))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                JsonNode n = json.readTree(res.body());
                error = "Meta answered " + res.statusCode() + ": " + n.path("error").path("message").asText(res.body());
            }
        } catch (Exception e) {
            error = "Couldn't reach Meta: " + e.getMessage();
        }
        for (Map<String, Object> r : rows) {
            if (error == null) {
                jdbc.update("UPDATE marketing_events SET status = 'SENT', sent_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = ?", r.get("id"));
            } else {
                boolean giveUp = ((Number) r.get("attempts")).intValue() >= 9
                        || ((java.sql.Timestamp) r.get("created_at")).toInstant().isBefore(Instant.now().minus(Duration.ofDays(6)));
                jdbc.update("UPDATE marketing_events SET status = ?, attempts = attempts + 1, last_error = ? WHERE id = ?",
                        giveUp ? "FAILED" : "PENDING", error, r.get("id"));
            }
        }
        if (error != null) {
            log.warn("Meta conversions not sent ({} events): {}", rows.size(), error);
        }
    }

    /** Kenyan numbers as Meta wants them: country code, digits only (0712345678 -> 254712345678). */
    static String phoneDigits(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.replaceAll("[^0-9]", "");
        if (d.length() == 10 && d.startsWith("0")) {
            d = "254" + d.substring(1);
        } else if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) {
            d = "254" + d;
        }
        return d.length() >= 10 ? d : null;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putIf(ObjectNode o, String key, String v) {
        if (v != null && !v.isBlank()) {
            o.put(key, v.length() > 500 ? v.substring(0, 500) : v);
        }
    }
}
