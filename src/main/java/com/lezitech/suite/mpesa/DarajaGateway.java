package com.lezitech.suite.mpesa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

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

/**
 * Safaricom Daraja: OAuth token, STK push (Lipa na M-Pesa Online) and C2B URL registration.
 * Endpoints and field names follow the Daraja API documentation.
 */
public class DarajaGateway implements MpesaGateway {

    private static final Logger log = LoggerFactory.getLogger(DarajaGateway.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final MpesaConfig cfg;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String token;
    private long tokenExpiresAt;

    public DarajaGateway(MpesaConfig cfg, ObjectMapper json) {
        this.cfg = cfg;
        this.json = json;
    }

    @Override
    public StkResult stkPush(String shortcode, String phone, BigDecimal amount, String accountRef, String description, String callbackUrl) {
        String timestamp = LocalDateTime.now(ZoneId.of("Africa/Nairobi")).format(TS);
        String password = Base64.getEncoder().encodeToString((shortcode + cfg.passkey + timestamp).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", shortcode);
        body.put("Password", password);
        body.put("Timestamp", timestamp);
        body.put("TransactionType", "CustomerPayBillOnline");
        body.put("Amount", amount.setScale(0, RoundingMode.CEILING).toPlainString()); // Daraja takes whole shillings
        body.put("PartyA", phone);
        body.put("PartyB", shortcode);
        body.put("PhoneNumber", phone);
        body.put("CallBackURL", callbackUrl);
        body.put("AccountReference", accountRef.length() > 12 ? accountRef.substring(0, 12) : accountRef);
        body.put("TransactionDesc", description.length() > 13 ? description.substring(0, 13) : description);
        JsonNode res = postJson("/mpesa/stkpush/v1/processrequest", body);
        if (!"0".equals(res.path("ResponseCode").asText())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MPESA_REJECTED",
                    "M-Pesa didn't accept the request: " + res.path("errorMessage").asText(res.path("ResponseDescription").asText("unknown error")));
        }
        return new StkResult(res.path("CheckoutRequestID").asText(), res.path("MerchantRequestID").asText());
    }

    @Override
    public void registerC2bUrls(String shortcode, String confirmationUrl, String validationUrl) {
        postJson("/mpesa/c2b/v1/registerurl", Map.of("ShortCode", shortcode, "ResponseType", "Completed",
                "ConfirmationURL", confirmationUrl, "ValidationURL", validationUrl));
    }

    private JsonNode postJson(String path, Map<String, Object> body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken())
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

    private synchronized String accessToken() throws Exception {
        if (token != null && System.currentTimeMillis() < tokenExpiresAt) {
            return token;
        }
        String basic = Base64.getEncoder().encodeToString((cfg.consumerKey + ":" + cfg.consumerSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl + "/oauth/v1/generate?grant_type=client_credentials"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Basic " + basic).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MPESA_AUTH", "M-Pesa refused our login. Check MPESA_CONSUMER_KEY and MPESA_CONSUMER_SECRET.");
        }
        JsonNode n = json.readTree(res.body());
        token = n.path("access_token").asText();
        tokenExpiresAt = System.currentTimeMillis() + (n.path("expires_in").asLong(3599) - 60) * 1000;
        return token;
    }
}
