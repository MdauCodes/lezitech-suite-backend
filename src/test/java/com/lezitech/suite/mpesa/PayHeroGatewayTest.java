package com.lezitech.suite.mpesa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PayHero calls against a fake PayHero: what we send, and how its answers are read. */
class PayHeroGatewayTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> statusReply = new AtomicReference<>("{}");
    private PayAccount.PayHero account;
    private PayAccount.PayHero wrongPassword;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String good = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
        server.createContext("/api/v2/", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = ex.getRequestURI().getPath();
            int code = 200;
            String reply;
            if (!good.equals(lastAuth.get())) {
                code = 401;
                reply = "{\"error_message\":\"unauthorized\"}";
            } else if (path.endsWith("/payments")) {
                reply = "{\"success\":true,\"status\":\"QUEUED\",\"reference\":\"PH-REF-1\",\"CheckoutRequestID\":\"ws_CO_1\"}";
            } else if (path.endsWith("/transaction-status")) {
                reply = statusReply.get();
            } else {
                reply = "{\"available_balance\":10}";
            }
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2";
        account = new PayAccount.PayHero(base, "user", "pass", 911);
        wrongPassword = new PayAccount.PayHero(base, "user", "nope", 911);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void stkPushSendsWholeShillingsToTheChannelAndKeepsPayHerosReference() throws Exception {
        StkResult r = new PayHeroGateway(json).stkPush(account, "254712345678", new BigDecimal("149.50"), "req-1", "https://x/cb/tok");
        assertThat(r.providerRef()).isEqualTo("PH-REF-1");
        assertThat(r.checkoutRequestId()).isEqualTo("ws_CO_1");
        JsonNode sent = json.readTree(lastBody.get());
        assertThat(sent.get("amount").asLong()).isEqualTo(150);
        assertThat(sent.get("channel_id").asLong()).isEqualTo(911);
        assertThat(sent.get("provider").asText()).isEqualTo("m-pesa");
        assertThat(sent.get("external_reference").asText()).isEqualTo("req-1");
        assertThat(sent.get("callback_url").asText()).isEqualTo("https://x/cb/tok");
    }

    @Test
    void statusIsReadIntoPaidCancelledFailedOrPending() {
        PayHeroGateway g = new PayHeroGateway(json);
        statusReply.set("{\"status\":\"SUCCESS\",\"provider_reference\":\"TKL1ABC2DE\",\"amount\":150}");
        PayHeroGateway.Status paid = g.status(account, "PH-REF-1");
        assertThat(paid.state()).isEqualTo("PAID");
        assertThat(paid.receipt()).isEqualTo("TKL1ABC2DE");
        assertThat(paid.amount()).isEqualByComparingTo("150");

        statusReply.set("{\"status\":\"CANCELLED\"}");
        assertThat(g.status(account, "PH-REF-1").state()).isEqualTo("CANCELLED");
        statusReply.set("{\"status\":\"FAILED\",\"error_message\":\"Insufficient balance\"}");
        assertThat(g.status(account, "PH-REF-1").message()).isEqualTo("Insufficient balance");
        statusReply.set("{\"status\":\"QUEUED\"}");
        assertThat(g.status(account, "PH-REF-1").state()).isEqualTo("PENDING");
    }

    @Test
    void wrongCredentialsGiveAMessageTheOwnerCanActOn() {
        PayHeroGateway g = new PayHeroGateway(json);
        g.test(account);
        assertThatThrownBy(() -> g.test(wrongPassword)).isInstanceOf(ApiException.class).hasMessageContaining("API username and password");
    }
}
