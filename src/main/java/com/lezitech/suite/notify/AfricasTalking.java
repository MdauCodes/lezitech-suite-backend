package com.lezitech.suite.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Africa's Talking SMS with a business's own account (username + API key). For now: checking the account works. */
@Component
public class AfricasTalking {

    public static final String LIVE = "https://api.africastalking.com";
    public static final String SANDBOX = "https://api.sandbox.africastalking.com";

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AfricasTalking(ObjectMapper json) {
        this.json = json;
    }

    /** Reads the account balance; returns it (e.g. "KES 120.0000") when the username and API key work. */
    public String test(String baseUrl, String username, String apiKey) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/version1/user?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(20)).header("apiKey", apiKey).header("Accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SMS_AUTH", "Africa's Talking refused the username and API key.");
            }
            if (res.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SMS_ERROR", "Africa's Talking answered with error " + res.statusCode() + ".");
            }
            JsonNode n = json.readTree(res.body());
            return n.path("UserData").path("balance").asText("");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SMS_ERROR", "Couldn't reach Africa's Talking. Check the internet and try again.");
        }
    }
}
