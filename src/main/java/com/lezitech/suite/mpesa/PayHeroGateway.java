package com.lezitech.suite.mpesa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PayHero (payherokenya.com): M-Pesa prompts into the business's own till or paybill, linked in PayHero as a
 * payment channel. Calls follow PayHero's v2 API as used by its official PHP package: Basic auth with the API
 * username and password, POST payments, GET transaction-status?reference=..., GET wallets.
 *
 * PayHero's callbacks are not signed, so a callback is only a nudge: the payment is recorded after asking
 * PayHero for the transaction status ourselves.
 */
@Component
public class PayHeroGateway {

    public static final String BASE_URL = "https://backend.payhero.co.ke/api/v2";

    private static final Logger log = LoggerFactory.getLogger(PayHeroGateway.class);

    /** PAID, FAILED (with CANCELLED when the customer said no) or PENDING. */
    public record Status(String state, String receipt, BigDecimal amount, String message) {
    }

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public PayHeroGateway(ObjectMapper json) {
        this.json = json;
    }

    public StkResult stkPush(PayAccount.PayHero a, String phone, BigDecimal amount, String externalRef, String callbackUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount.setScale(0, RoundingMode.CEILING).longValueExact()); // whole shillings
        body.put("phone_number", phone);
        body.put("channel_id", a.channelId());
        body.put("provider", "m-pesa");
        body.put("external_reference", externalRef);
        if (callbackUrl != null) {
            body.put("callback_url", callbackUrl);
        }
        JsonNode res = send(a, "POST", "/payments", body);
        String reference = res.path("reference").asText("");
        if (!res.path("success").asBoolean(false) || reference.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MPESA_REJECTED",
                    "PayHero didn't accept the request: " + firstText(res, "error_message", "message", "status"));
        }
        return new StkResult(res.path("CheckoutRequestID").asText(null), null, reference);
    }

    public Status status(PayAccount.PayHero a, String reference) {
        JsonNode r = send(a, "GET", "/transaction-status?reference=" + URLEncoder.encode(reference, StandardCharsets.UTF_8), null);
        String s = r.path("status").asText("").toUpperCase(Locale.ROOT);
        String receipt = firstText(r, "provider_reference", "MpesaReceiptNumber", "third_party_reference");
        BigDecimal amount = r.hasNonNull("amount") ? new BigDecimal(r.path("amount").asText("0")) : null;
        String message = firstText(r, "ResultDesc", "result_desc", "error_message", "message");
        return switch (s) {
            case "SUCCESS", "COMPLETED" -> new Status("PAID", receipt.isBlank() ? null : receipt, amount, message);
            case "CANCELLED", "CANCELED" -> new Status("CANCELLED", null, amount, message.isBlank() ? "Cancelled by the customer" : message);
            case "FAILED", "REVERSED", "EXPIRED" -> new Status("FAILED", null, amount, message.isBlank() ? s.toLowerCase(Locale.ROOT) : message);
            default -> new Status("PENDING", null, amount, message);
        };
    }

    /** One of the business's tills or paybills as PayHero knows it (a "payment channel"). */
    public record Channel(long id, String type, String shortCode, String accountNumber, String description, boolean active) {
    }

    /** The business's tills and paybills linked in PayHero, so the owner picks one instead of hunting for its number. */
    public java.util.List<Channel> channels(PayAccount.PayHero a) {
        JsonNode r = send(a, "GET", "/payment_channels", null);
        java.util.List<Channel> out = new java.util.ArrayList<>();
        for (JsonNode c : r.path("payment_channels")) {
            out.add(new Channel(c.path("id").asLong(), c.path("channel_type").asText(""), c.path("short_code").asText(""),
                    c.path("account_number").asText(""), c.path("description").asText(""), c.path("is_active").asBoolean(true)));
        }
        return out;
    }

    /** The PayHero service wallet: PayHero takes its charge per payment from here, so it needs topping up. */
    public record Wallet(long accountId, BigDecimal balance) {
    }

    public Wallet wallet(PayAccount.PayHero a) {
        JsonNode r = send(a, "GET", "/wallets?wallet_type=service_wallet", null);
        return new Wallet(r.path("account_id").asLong(), r.path("available_balance").decimalValue());
    }

    /** Top up the PayHero wallet: an M-Pesa prompt goes to {@code phone} for the owner's PIN. */
    public String topUp(PayAccount.PayHero a, long amount, String phone) {
        JsonNode r = send(a, "POST", "/topup", Map.of("amount", amount, "phone_number", phone));
        if (!r.path("success").asBoolean(false)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYHERO_ERROR", "PayHero didn't start the top-up: " + firstText(r, "error_message", "message", "status"));
        }
        return r.path("reference").asText("");
    }

    /** Link a till or paybill to the PayHero account from BizNez (type is "till" or "paybill"). */
    public Channel addChannel(PayAccount.PayHero a, long accountId, String type, String shortCode, String accountNumber, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_type", type);
        body.put("account_id", accountId);
        body.put("short_code", shortCode);
        body.put("account_number", accountNumber == null ? "" : accountNumber);
        body.put("description", description);
        JsonNode c = send(a, "POST", "/payment_channels", body);
        JsonNode n = c.has("payment_channel") ? c.path("payment_channel") : c;
        return new Channel(n.path("id").asLong(), n.path("channel_type").asText(type), n.path("short_code").asText(shortCode),
                n.path("account_number").asText(""), n.path("description").asText(description), n.path("is_active").asBoolean(true));
    }

    /** Checks the API username and password by reading the account's wallet. */
    public void test(PayAccount.PayHero a) {
        send(a, "GET", "/wallets?wallet_type=service_wallet", null);
    }

    private JsonNode send(PayAccount.PayHero a, String method, String path, Map<String, Object> body) {
        try {
            String basic = Base64.getEncoder().encodeToString((a.username() + ":" + a.password()).getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(a.baseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Basic " + basic)
                    .header("Accept", "application/json");
            if ("POST".equals(method)) {
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                b.GET();
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYHERO_AUTH",
                        "PayHero refused the API username and password. Copy them again from API Keys in PayHero (no spaces before or after).");
            }
            if (res.statusCode() >= 300) {
                log.warn("PayHero {} {} answered {}: {}", method, path, res.statusCode(), res.body());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYHERO_ERROR",
                        "PayHero answered with an error (" + firstText(node, "error_message", "message").replaceAll("^$", "error " + res.statusCode()) + ").");
            }
            return node;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("PayHero {} failed: {}", path, e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYHERO_ERROR", "Couldn't reach PayHero. Check the internet and try again.");
        }
    }

    private static String firstText(JsonNode n, String... keys) {
        for (String k : keys) {
            String v = n.path(k).asText("");
            if (!v.isBlank() && !"null".equals(v)) {
                return v;
            }
        }
        return "";
    }
}
