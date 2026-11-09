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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safaricom Daraja with each business's own app: OAuth token, STK push (Lipa na M-Pesa Online) for
 * paybills and Buy Goods tills, and C2B URL registration. Field names follow the Daraja documentation.
 */
@Component
public class DarajaGateway {

    public static final String SANDBOX = "https://sandbox.safaricom.co.ke";
    public static final String PRODUCTION = "https://api.safaricom.co.ke";

    private static final Logger log = LoggerFactory.getLogger(DarajaGateway.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private record Token(String value, long expiresAt) {
    }

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** One cached token per Daraja app (base URL + consumer key). */
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    public DarajaGateway(ObjectMapper json) {
        this.json = json;
    }

    public StkResult stkPush(PayAccount.Daraja a, String phone, BigDecimal amount, String accountRef, String description, String callbackUrl) {
        String timestamp = LocalDateTime.now(ZoneId.of("Africa/Nairobi")).format(TS);
        String password = Base64.getEncoder().encodeToString((a.shortcode() + a.passkey() + timestamp).getBytes(StandardCharsets.UTF_8));
        String ref = a.accountReference() != null && !a.accountReference().isBlank() ? a.accountReference() : accountRef;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", a.shortcode());
        body.put("Password", password);
        body.put("Timestamp", timestamp);
        body.put("TransactionType", a.buyGoods() ? "CustomerBuyGoodsOnline" : "CustomerPayBillOnline");
        body.put("Amount", amount.setScale(0, RoundingMode.CEILING).toPlainString()); // Daraja takes whole shillings
        body.put("PartyA", phone);
        body.put("PartyB", a.partyB());
        body.put("PhoneNumber", phone);
        body.put("CallBackURL", callbackUrl);
        body.put("AccountReference", ref.length() > 12 ? ref.substring(0, 12) : ref);
        body.put("TransactionDesc", description.length() > 13 ? description.substring(0, 13) : description);
        JsonNode res = postJson(a, "/mpesa/stkpush/v1/processrequest", body);
        if (!"0".equals(res.path("ResponseCode").asText())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MPESA_REJECTED",
                    "M-Pesa didn't accept the request: " + res.path("errorMessage").asText(res.path("ResponseDescription").asText("unknown error")));
        }
        return new StkResult(res.path("CheckoutRequestID").asText(), res.path("MerchantRequestID").asText(), null);
    }

    public void registerC2bUrls(PayAccount.Daraja a, String confirmationUrl, String validationUrl) {
        postJson(a, "/mpesa/c2b/v1/registerurl", Map.of("ShortCode", a.partyB(), "ResponseType", "Completed",
                "ConfirmationURL", confirmationUrl, "ValidationURL", validationUrl));
    }

    /** Signs in with the consumer key and secret. The passkey can only be checked by a real prompt. */
    public void test(PayAccount.Daraja a) {
        tokens.remove(a.baseUrl() + "|" + a.consumerKey());
        try {
            accessToken(a);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MPESA_ERROR", "Couldn't reach Safaricom: " + e.getMessage());
        }
    }

    private JsonNode postJson(PayAccount.Daraja a, String path, Map<String, Object> body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(a.baseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken(a))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.readTree(res.body().isBlank() ? "{}" : res.body());
            if (res.statusCode() >= 300) {
                log.warn("Daraja {} answered {}: {}", path, res.statusCode(), res.body());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "MPESA_ERROR",
                        "M-Pesa is not answering properly right now (" + node.path("errorMessage").asText("error " + res.statusCode()) + "). Try again or take cash.");
            }
            return node;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Daraja {} failed: {}", path, e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MPESA_ERROR", "Couldn't reach M-Pesa. Check the internet and try again.");
        }
    }

    private String accessToken(PayAccount.Daraja a) throws Exception {
        String key = a.baseUrl() + "|" + a.consumerKey();
        Token t = tokens.get(key);
        if (t != null && System.currentTimeMillis() < t.expiresAt()) {
            return t.value();
        }
        String basic = Base64.getEncoder().encodeToString((a.consumerKey() + ":" + a.consumerSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(a.baseUrl() + "/oauth/v1/generate?grant_type=client_credentials"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Basic " + basic).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MPESA_AUTH",
                    "Safaricom refused the consumer key and secret. Check them on the Daraja portal, and that the app is for "
                            + (a.baseUrl().contains("sandbox") ? "the sandbox" : "production (live)") + ".");
        }
        JsonNode n = json.readTree(res.body());
        Token fresh = new Token(n.path("access_token").asText(), System.currentTimeMillis() + (n.path("expires_in").asLong(3599) - 60) * 1000);
        tokens.put(key, fresh);
        return fresh.value();
    }
}
