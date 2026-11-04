package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A token from one workspace must never see or touch another workspace's data. */
class TenantIsolationTest extends IntegrationTest {

    @Test
    void workspaceBCannotReadOrChangeWorkspaceAData() {
        JsonNode a = signup("retail");
        JsonNode b = signup("retail");
        String ta = token(a);
        String tb = token(b);

        JsonNode sugar = item(ta, "Sugar");
        JsonNode sale = post("/orders", ta, Map.of("lines", line(sugar.get("id").asText(), 1),
                "payments", java.util.List.of(Map.of("method", "CASH", "amount", 500)))).body();
        String orderId = sale.get("id").asText();
        String customerId = get("/customers", ta).body().get(0).get("id").asText();

        assertThat(get("/orders/" + orderId, tb).status()).isEqualTo(404);
        assertThat(get("/customers/" + customerId, tb).status()).isEqualTo(404);
        assertThat(call("PUT", "/items/" + sugar.get("id").asText(), tb, Map.of("name", "Hacked", "price", 1)).body().toString())
                .doesNotContain("Hacked");
        assertThat(get("/items", tb).body().toString()).doesNotContain(sugar.get("id").asText());
        assertThat(get("/stock", tb).body().toString()).doesNotContain(sugar.get("id").asText());
        assertThat(get("/orders", tb).body().toString()).doesNotContain(orderId);
        // B's reports only count B's sales.
        assertThat(get("/reports/sales", tb).at("/totals/orders").asInt()).isZero();
        assertThat(get("/reports/sales", ta).at("/totals/orders").asInt()).isEqualTo(1);
        // A's item is untouched.
        assertThat(item(ta, "Sugar").get("name").asText()).contains("Sugar");
    }
}
