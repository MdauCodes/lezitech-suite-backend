package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.WhatsAppCloud;
import com.lezitech.suite.support.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The optional WhatsApp Business (Cloud API) number: set up like the payment keys, then receipts go out as the approved template. */
class WhatsAppReceiptTest extends IntegrationTest {

    @Autowired
    WhatsAppCloud whatsApp;

    private HttpServer graph;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void fakeGraph() throws IOException {
        graph = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        graph.createContext("/", ex -> {
            lastPath.set(ex.getRequestURI().toString());
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean ok = "Bearer good-token".equals(ex.getRequestHeaders().getFirst("Authorization"));
            String reply;
            int code = 200;
            if (!ok) {
                code = 401;
                reply = "{\"error\":{\"message\":\"Invalid OAuth access token.\",\"code\":190}}";
            } else if ("GET".equals(ex.getRequestMethod())) {
                reply = "{\"display_phone_number\":\"+254 700 111222\",\"verified_name\":\"Test Shop\",\"id\":\"1098765432\"}";
            } else {
                reply = "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"wa_id\":\"254712345678\"}],\"messages\":[{\"id\":\"wamid.TEST1\"}]}";
            }
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        graph.start();
        whatsApp.useBaseUrl("http://127.0.0.1:" + graph.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        whatsApp.useBaseUrl(WhatsAppCloud.GRAPH);
        graph.stop(0);
    }

    @Test
    void ownerConnectsTheNumberAndReceiptsGoAsTheTemplate() throws Exception {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        post("/shifts/open", cashier, Map.of("openingCash", 0)); // cashiers open their till before taking money
        String sugar = item(owner, "Sugar").get("id").asText();
        String id = post("/orders", cashier, Map.of("lines", line(sugar, 2),
                "payments", List.of(Map.of("method", "MPESA", "amount", 380, "reference", "TKL1ABC2DE")))).at("/id").asText();

        // Not set up: the till uses wa.me links and the API says so.
        assertThat(get("/whatsapp/status", cashier).at("/api").asBoolean()).isFalse();
        assertThat(post("/orders/" + id + "/whatsapp-receipt", cashier, Map.of("phone", "0712345678")).status()).isEqualTo(409);

        // The phone number itself is a common mistake for the phone number ID.
        assertThat(call("PUT", "/settings/payments/whatsapp", owner, Map.of("settings", Map.of("phoneNumberId", "+254 700 111222"))).status())
                .isEqualTo(400);
        // Switching on needs everything.
        assertThat(call("PUT", "/settings/payments/whatsapp", owner, Map.of("enabled", true, "settings", Map.of("phoneNumberId", "1098765432")))
                .status()).isEqualTo(400);

        Res wrong = call("PUT", "/settings/payments/whatsapp", owner, Map.of("enabled", true,
                "settings", Map.of("phoneNumberId", "1098765432", "receiptTemplate", "lezi_receipt"),
                "secrets", Map.of("accessToken", "bad-token")));
        assertThat(wrong.status()).as(wrong.body().toString()).isEqualTo(200);
        assertThat(wrong.at("/secrets/accessToken").asText()).endsWith("oken").doesNotContain("bad");
        JsonNode failed = post("/settings/payments/whatsapp/test", owner, Map.of()).body();
        assertThat(failed.at("/status").asText()).isEqualTo("FAILED");
        assertThat(failed.at("/statusMessage").asText()).contains("access token");

        call("PUT", "/settings/payments/whatsapp", owner, Map.of("secrets", Map.of("accessToken", "good-token")));
        JsonNode ok = post("/settings/payments/whatsapp/test", owner, Map.of()).body();
        assertThat(ok.at("/status").asText()).as(ok.toString()).isEqualTo("OK");
        assertThat(ok.at("/statusMessage").asText()).contains("+254 700 111222").contains("Test Shop");
        // WhatsApp being on doesn't touch the M-Pesa account choice.
        assertThat(get("/whatsapp/status", cashier).at("/api").asBoolean()).isTrue();

        assertThat(post("/orders/" + id + "/whatsapp-receipt", cashier, Map.of("phone", "12")).status()).isEqualTo(400);
        Res sent = post("/orders/" + id + "/whatsapp-receipt", cashier, Map.of("phone", "0712 345 678"));
        assertThat(sent.status()).as(sent.body().toString()).isEqualTo(200);
        assertThat(sent.at("/to").asText()).isEqualTo("+254712345678");
        assertThat(sent.at("/messageId").asText()).isEqualTo("wamid.TEST1");
        assertThat(lastPath.get()).isEqualTo("/1098765432/messages");
        JsonNode body = json.readTree(lastBody.get());
        assertThat(body.at("/to").asText()).isEqualTo("254712345678");
        assertThat(body.at("/type").asText()).isEqualTo("template");
        assertThat(body.at("/template/name").asText()).isEqualTo("lezi_receipt");
        assertThat(body.at("/template/language/code").asText()).isEqualTo("en");
        JsonNode params = body.at("/template/components/0/parameters");
        assertThat(params.size()).isEqualTo(4);
        assertThat(params.get(0).get("text").asText()).startsWith("Test retail");
        assertThat(params.get(1).get("text").asText()).startsWith("Receipt #");
        assertThat(params.get(2).get("text").asText()).matches("2 x Sugar.* KES 380\\.00");
        assertThat(params.get(3).get("text").asText()).isEqualTo("KES 380.00, paid by M-Pesa TKL1ABC2DE");

        // Switched off again: back to wa.me links.
        call("PUT", "/settings/payments/whatsapp", owner, Map.of("enabled", false));
        assertThat(get("/whatsapp/status", cashier).at("/api").asBoolean()).isFalse();
    }
}
