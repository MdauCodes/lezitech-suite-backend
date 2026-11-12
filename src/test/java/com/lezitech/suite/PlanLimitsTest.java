package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Each plan's limits on people, branches and products are enforced, with an upgrade message. */
class PlanLimitsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String onPlan(String plan) {
        JsonNode s = signup("retail");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE', plan = ?, paid_until = now() + interval '30 days' WHERE id = ?::uuid",
                plan, s.at("/tenant/id").asText());
        return token(s);
    }

    private Res addStaff(String owner) {
        return post("/team", owner, Map.of("name", "Extra", "email", "x-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local",
                "password", "password123", "role", "CASHIER"));
    }

    @Test
    void litePlanStopsAtThreePeople() {
        String owner = onPlan("LITE");
        assertThat(addStaff(owner).status()).isEqualTo(200);
        assertThat(addStaff(owner).status()).isEqualTo(200);
        Res fourth = addStaff(owner);
        assertThat(fourth.status()).isEqualTo(403);
        assertThat(fourth.at("/code").asText()).isEqualTo("PLAN_LIMIT");
        assertThat(fourth.at("/message").asText()).contains("Lite plan includes 3").contains("Growth allows up to 8");

        // The price list says so too.
        JsonNode lite = null;
        for (JsonNode p : get("/billing/plans", owner).body().get("plans")) {
            if ("LITE".equals(p.path("code").asText())) {
                lite = p;
            }
        }
        assertThat(lite).isNotNull();
        assertThat(lite.get("monthly").asInt()).isEqualTo(2650);
        assertThat(lite.get("users").asInt()).isEqualTo(3);

        // Switching someone off frees the place; switching them back on is checked again.
        String extra = null;
        for (JsonNode u : get("/team", owner).body()) {
            if (!"OWNER".equals(u.get("role").asText())) {
                extra = u.get("id").asText();
            }
        }
        assertThat(call("PUT", "/team/" + extra, owner, Map.of("active", false)).status()).isEqualTo(200);
        assertThat(addStaff(owner).status()).isEqualTo(200);
        assertThat(call("PUT", "/team/" + extra, owner, Map.of("active", true)).status()).isEqualTo(403);
    }

    @Test
    void litePlanHasOneBranchButStoresInsideItAreFine() {
        String owner = onPlan("LITE");
        JsonNode usage = get("/billing/plans", owner).at("/usage");
        assertThat(usage.at("/plan").asText()).isEqualTo("LITE");
        long branches = usage.at("/locations/used").asLong();
        Res branch = post("/locations", owner, Map.of("name", "Second shop", "kind", "SHOP"));
        assertThat(branch.status()).isEqualTo(branches >= 1 ? 403 : 200);
        String first = get("/locations", owner).body().get(0).get("id").asText();
        Res store = post("/locations", owner, Map.of("name", "Back store", "kind", "STORE", "parentId", first));
        assertThat(store.status()).as(store.body().toString()).isEqualTo(200);
    }

    @Test
    void litePlanCapsProductsIncludingImports() {
        String owner = onPlan("LITE");
        long have = get("/billing/plans", owner).at("/usage/items/used").asLong();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 500 - have; i++) {
            rows.add(Map.of("name", "Bulk item " + i, "price", 10));
        }
        Res imp = post("/items/import", owner, Map.of("rows", rows));
        assertThat(imp.status()).as(imp.body().toString()).isEqualTo(200);
        Res over = post("/items", owner, Map.of("name", "One too many", "price", 10));
        assertThat(over.status()).isEqualTo(403);
        assertThat(over.at("/message").asText()).contains("500 products");
    }

    @Test
    void trialsGetProLimits() {
        String owner = token(signup("retail"));
        JsonNode usage = get("/billing/plans", owner).at("/usage");
        assertThat(usage.at("/plan").asText()).isEqualTo("PRO");
        assertThat(usage.at("/users/limit").asInt()).isEqualTo(30);
        assertThat(addStaff(owner).status()).isEqualTo(200);
        assertThat(addStaff(owner).status()).isEqualTo(200);
    }
}
