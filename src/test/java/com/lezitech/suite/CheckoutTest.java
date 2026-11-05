package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Totals, VAT, split payments, change, the credit limit and the trial lock. */
class CheckoutTest extends IntegrationTest {

    @Test
    void totalsAndVatAreRight() {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();      // 190, 16% VAT inclusive
        String charging = item(owner, "Phone charging").get("id").asText(); // 30, service
        JsonNode o = post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", sugar, "qty", 3), Map.of("itemId", charging, "qty", 2)))).body();
        assertThat(o.get("subtotal").decimalValue()).isEqualByComparingTo("630");
        assertThat(o.get("total").decimalValue()).isEqualByComparingTo("630");
        // VAT is the inclusive portion per line: 570*16/116 = 78.62, 60*16/116 = 8.28
        assertThat(o.get("tax").decimalValue()).isEqualByComparingTo("86.90");
    }

    @Test
    void splitPaymentGivesChangeOnlyFromCash() {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        JsonNode sale = post("/orders", owner, Map.of("lines", line(sugar, 2),
                "payments", List.of(Map.of("method", "MPESA", "amount", 200, "reference", "QAZ"), Map.of("method", "CASH", "amount", 500)))).body();
        assertThat(sale.get("status").asText()).isEqualTo("PAID");
        assertThat(sale.get("change").decimalValue()).isEqualByComparingTo("320");
        // Cash recorded is what was kept, not what was handed over.
        BigDecimalSum s = new BigDecimalSum(sale.get("payments"));
        assertThat(s.of("MPESA")).isEqualByComparingTo("200");
        assertThat(s.of("CASH")).isEqualByComparingTo("180");

        // Paying too little leaves the bill open with the balance due.
        String open = post("/orders", owner, Map.of("lines", line(sugar, 1))).at("/id").asText();
        JsonNode part = post("/orders/" + open + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", 100)))).body();
        assertThat(part.get("status").asText()).isEqualTo("OPEN");
        assertThat(part.get("paid").decimalValue()).isEqualByComparingTo("100");
        JsonNode rest = post("/orders/" + open + "/pay", owner, Map.of("payments", List.of(Map.of("method", "MPESA", "amount", 90)))).body();
        assertThat(rest.get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void creditLimitAndCustomerNeeded() {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        // Pay later without choosing a customer: refused.
        assertThat(post("/orders", owner, Map.of("lines", line(sugar, 1),
                "payments", List.of(Map.of("method", "CREDIT", "amount", 190)))).status()).isEqualTo(400);
        JsonNode c = post("/customers", owner, Map.of("name", "Limit Wanjiru", "creditLimit", 300)).body();
        String cid = c.get("id").asText();
        assertThat(post("/orders", owner, Map.of("customerId", cid, "lines", line(sugar, 1),
                "payments", List.of(Map.of("method", "CREDIT", "amount", 190)))).status()).isEqualTo(200);
        Res over = post("/orders", owner, Map.of("customerId", cid, "lines", line(sugar, 1),
                "payments", List.of(Map.of("method", "CREDIT", "amount", 190))));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.at("/message").asText()).contains("credit limit");
        // Paying the debt brings the balance down and allows more credit.
        post("/customers/" + cid + "/settle", owner, Map.of("amount", 190, "method", "MPESA"));
        assertThat(get("/customers/" + cid, owner).at("/balance").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void endedTrialIsReadOnly() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String tenantId = s.at("/tenant/id").asText();
        String sugar = item(owner, "Sugar").get("id").asText();
        Res expire = call("POST", "/platform/tenants/" + tenantId + "/extend-trial", null, Map.of("days", -1));
        // Operator endpoint needs its key header; use the control-panel path instead via the helper below.
        assertThat(expire.status()).isEqualTo(403);
        expireTrial(tenantId);

        Res write = post("/orders", owner, Map.of("lines", line(sugar, 1)));
        assertThat(write.status()).isEqualTo(402);
        assertThat(write.at("/code").asText()).isEqualTo("TRIAL_EXPIRED");
        // Reading still works so nobody loses their data.
        assertThat(get("/items", owner).status()).isEqualTo(200);
        assertThat(get("/auth/me", owner).at("/tenant/locked").asBoolean()).isTrue();
    }

    private void expireTrial(String tenantId) {
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/platform/tenants/" + tenantId + "/extend-trial"))
                    .header("Content-Type", "application/json").header("X-Operator-Key", "local-operator-key")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"days\":-1}")).build();
            int status = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
            assertThat(status).isEqualTo(200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sums payment amounts per method. */
    private record BigDecimalSum(JsonNode payments) {
        java.math.BigDecimal of(String method) {
            java.math.BigDecimal t = java.math.BigDecimal.ZERO;
            for (JsonNode p : payments) {
                if (method.equals(p.get("method").asText())) {
                    t = t.add(p.get("amount").decimalValue());
                }
            }
            return t;
        }
    }
}
