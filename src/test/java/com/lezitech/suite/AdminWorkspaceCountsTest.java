package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The workspace page in the control panel shows that workspace's own numbers, not the whole platform's. */
class AdminWorkspaceCountsTest extends IntegrationTest {

    @Test
    void twoWorkspacesSeeTheirOwnCounts() {
        JsonNode a = signup("retail");
        JsonNode b = signup("retail");
        String ownerA = token(a);
        String sugar = item(ownerA, "").get("id").asText();
        for (int i = 0; i < 3; i++) {
            assertThat(post("/orders", ownerA, Map.of("lines", line(sugar, 1))).status()).isEqualTo(200);
        }
        String admin = post("/admin/auth/demo", null, Map.of("role", "SUPERADMIN")).at("/token").asText();
        Res ra = get("/admin/tenants/" + a.at("/tenant/id").asText(), admin);
        Res rb = get("/admin/tenants/" + b.at("/tenant/id").asText(), admin);
        assertThat(ra.status()).isEqualTo(200);
        assertThat(rb.status()).isEqualTo(200);
        assertThat(ra.at("/usage/orders").asLong()).isEqualTo(3);
        assertThat(rb.at("/usage/orders").asLong()).isZero();
        assertThat(rb.at("/usage/lastOrderAt").isNull()).isTrue();
        assertThat(ra.at("/usage/items").asLong()).isEqualTo(rb.at("/usage/items").asLong());
        assertThat(ra.at("/usage/customers").asLong()).isLessThan(100);
    }
}
