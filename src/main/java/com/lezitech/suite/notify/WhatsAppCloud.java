package com.lezitech.suite.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Meta's WhatsApp Cloud API with a business's own WhatsApp Business number: the phone number ID from
 * Meta's WhatsApp Manager and a permanent access token (a system user token).
 *
 * WhatsApp only lets a business start a conversation with an approved message template. Plain text works
 * only within 24 hours of the customer's last message to the business, so receipts go as a template and
 * free text is for replies. A business that hasn't set this up uses wa.me links, which open WhatsApp on
 * the cashier's phone instead.
 */
@Component
public class WhatsAppCloud {

    public static final String GRAPH = "https://graph.facebook.com/v23.0";

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String base;

    public WhatsAppCloud(ObjectMapper json, @Value("${whatsapp.graph-url:" + GRAPH + "}") String base) {
        this.json = json;
        this.base = base.replaceAll("/+$", "");
    }

    /** Tests point this at a fake Graph API. */
    public void useBaseUrl(String url) {
        this.base = url.replaceAll("/+$", "");
    }

    /** Reads the number's details; returns e.g. "+254 712 345678 (Mama Mboga Shop)" when the ID and token work. */
    public String test(String phoneNumberId, String token) {
        JsonNode n = call("GET", "/" + phoneNumberId + "?fields=display_phone_number,verified_name", token, null);
        String number = n.path("display_phone_number").asText("");
        String name = n.path("verified_name").asText("");
        return (number + (name.isBlank() ? "" : " (" + name + ")")).trim();
    }

    /** Sends an approved template with body parameters {{1}}, {{2}}, ... Returns WhatsApp's message ID. */
    public String sendTemplate(String phoneNumberId, String token, String to, String template, String language, List<String> params) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (String p : params) {
            parameters.add(Map.of("type", "text", "text", templateSafe(p)));
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", template);
        t.put("language", Map.of("code", language == null || language.isBlank() ? "en" : language));
        if (!parameters.isEmpty()) {
            t.put("components", List.of(Map.of("type", "body", "parameters", parameters)));
        }
        return send(phoneNumberId, token, Map.of("messaging_product", "whatsapp", "to", to, "type", "template", "template", t));
    }

    /** Sends plain text: only delivered within 24 hours of the customer's last message to the business. */
    public String sendText(String phoneNumberId, String token, String to, String text) {
        return send(phoneNumberId, token, Map.of("messaging_product", "whatsapp", "to", to, "type", "text",
                "text", Map.of("body", text, "preview_url", true)));
    }

    private String send(String phoneNumberId, String token, Map<String, Object> body) {
        JsonNode n = call("POST", "/" + phoneNumberId + "/messages", token, body);
        String id = n.path("messages").path(0).path("id").asText("");
        if (id.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WHATSAPP_ERROR", "WhatsApp didn't accept the message.");
        }
        return id;
    }

    /** Template parameters may not contain new lines, tabs or more than four spaces in a row. */
    static String templateSafe(String s) {
        String v = s == null ? "" : s.replaceAll("[\\r\\n\\t]+", " · ").replaceAll(" {4,}", "   ").trim();
        return v.isEmpty() ? "-" : v.length() > 1000 ? v.substring(0, 997) + "..." : v;
    }

    private JsonNode call(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json");
            if (body == null) {
                b.GET();
            } else {
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
            if (res.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "WHATSAPP_ERROR", explain(res.statusCode(), n.path("error")));
            }
            return n;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WHATSAPP_ERROR", "Couldn't reach WhatsApp. Check the internet and try again.");
        }
    }

    /** Meta's error codes in words a business owner can act on. */
    static String explain(int status, JsonNode error) {
        int code = error.path("code").asInt(0);
        String meta = error.path("error_user_msg").asText(error.path("message").asText(""));
        return switch (code) {
            case 190 -> "WhatsApp refused the access token: it is wrong or has expired. Create a permanent (system user) token in Meta Business settings.";
            case 100 -> meta.toLowerCase().contains("phone") || meta.toLowerCase().contains("object")
                    ? "WhatsApp doesn't know that phone number ID, or the token can't use it. Copy the phone number ID from WhatsApp Manager."
                    : "WhatsApp rejected the request: " + meta;
            case 10, 200 -> "The token doesn't have permission to send WhatsApp messages (it needs whatsapp_business_messaging).";
            case 132000, 132001 -> "WhatsApp doesn't have that message template in that language, or it isn't approved yet.";
            case 131026 -> "That number can't receive WhatsApp messages.";
            case 131047 -> "More than 24 hours since the customer last wrote, so only a template can be sent.";
            case 131030 -> "That number isn't on the allowed list of this test WhatsApp number.";
            default -> status == 401 ? "WhatsApp refused the access token."
                    : "WhatsApp answered with error " + (code == 0 ? status : code) + (meta.isBlank() ? "." : ": " + meta);
        };
    }
}
