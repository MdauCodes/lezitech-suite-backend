package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plans differ by tools, not only limits: every business keeps its own screens, a smaller plan closes the extra tools
 * (records stay readable), the free days open everything, and LeziTech moves tools between plans from the control panel.
 */
class PlanToolsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.lezitech.suite.platform.PlatformSettings platform;

    private void putOnPlan(JsonNode session, String plan) {
        jdbc.update("UPDATE tenants SET status = 'ACTIVE', plan = ?, paid_until = now() + interval '30 days' WHERE id = ?",
                plan, UUID.fromString(session.at("/tenant/id").asText()));
    }

    @Test
    void freeDaysOpenEverything() {
        JsonNode s = signup("retail");
        assertThat(s.at("/tenant/planTools").toString()).contains("purchasing", "history", "ownerPowers");
        assertThat(post("/suppliers", token(s), Map.of("name", "Trial supplier")).status()).isEqualTo(200);
    }

    @Test
    void liteClosesGrowthAndProToolsButKeepsRecordsReadable() {
        JsonNode s = signup("retail");
        String owner = token(s);
        assertThat(post("/suppliers", owner, Map.of("name", "Made in the trial")).status()).isEqualTo(200);
        putOnPlan(s, "LITE");

        Res refused = post("/suppliers", owner, Map.of("name", "After the trial"));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.at("/code").asText()).isEqualTo("PLAN_FEATURE");
        assertThat(refused.at("/message").asText()).contains("Buying from suppliers", "Growth plan");
        assertThat(get("/suppliers", owner).status()).isEqualTo(200);
        assertThat(get("/suppliers", owner).body().toString()).contains("Made in the trial");

        assertThat(get("/reports/back-office", owner).status()).isEqualTo(403);
        assertThat(get("/activity", owner).status()).isEqualTo(403);
        assertThat(post("/orders", owner, Map.of("kind", "QUOTE")).at("/code").asText()).isEqualTo("PLAN_FEATURE");
        assertThat(get("/reports/sales", owner).body().has("byBranch")).isFalse();

        // Who owes you stays; full statements are a Growth tool.
        String customer = post("/customers", owner, Map.of("name", "Statement Customer", "phone", "0700000001")).at("/id").asText();
        assertThat(get("/customers/owing", owner).status()).isEqualTo(200);
        assertThat(get("/customers/" + customer + "/statement", owner).at("/code").asText()).isEqualTo("PLAN_FEATURE");

        // Every plan keeps the till, products and stock counts.
        assertThat(get("/stock", owner).status()).isEqualTo(200);
        assertThat(get("/items", owner).status()).isEqualTo(200);
    }

    @Test
    void ownerPowersOnlyOnPro() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        putOnPlan(s, "GROWTH");
        assertThat(call("PUT", "/team/" + managerId, owner, Map.of("ownerPowers", true)).at("/code").asText()).isEqualTo("PLAN_FEATURE");
        assertThat(post("/suppliers", owner, Map.of("name", "Growth has suppliers")).status()).isEqualTo(200);
        putOnPlan(s, "PRO");
        assertThat(call("PUT", "/team/" + managerId, owner, Map.of("ownerPowers", true)).status()).isEqualTo(200);
    }

    @Test
    void leziTechMovesToolsBetweenPlans() {
        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
        JsonNode s = signup("retail");
        putOnPlan(s, "LITE");
        try {
            JsonNode tools = get("/admin/platform-settings", admin).at("/tools");
            assertThat(tools.toString()).contains("purchasing");
            Res set = call("PUT", "/admin/platform-settings", admin, Map.of("planTools", Map.of(
                    "LITE", List.of("purchasing"),
                    "GROWTH", List.of("purchasing", "backOffice"),
                    "PRO", List.of("purchasing", "backOffice", "history"))));
            assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
            assertThat(post("/suppliers", token(s), Map.of("name", "Lite now has suppliers")).status()).isEqualTo(200);
            assertThat(get("/public/plans", null).at("/plans/0/tools").toString()).isEqualTo("[\"purchasing\"]");
            assertThat(call("PUT", "/admin/platform-settings", admin, Map.of("planTools", Map.of("LITE", List.of("flying"),
                    "GROWTH", List.of(), "PRO", List.of()))).status()).isEqualTo(400);
        } finally {
            jdbc.update("DELETE FROM platform_settings WHERE key = 'planFeatures'");
            platform.forget();
        }
    }
}
