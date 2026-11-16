package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** LeziTech changes the free days and the plans from the control panel; sign-up and plan pages follow at once. */
class PlatformSettingsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void freeDaysAndPlansAreSetFromTheControlPanel() throws Exception {
        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
        String support = post("/admin/auth/demo", null, Map.of("role", "SUPPORT")).at("/token").asText();
        try {
            assertThat(call("PUT", "/admin/platform-settings", support, Map.of("trialDays", 14)).status()).isEqualTo(403);
            assertThat(call("PUT", "/admin/platform-settings", admin, Map.of("trialDays", 100)).status()).isEqualTo(400);
            Res set = call("PUT", "/admin/platform-settings", admin, Map.of("trialDays", 14));
            assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
            assertThat(get("/public/plans", null).at("/trialDays").asInt()).isEqualTo(14);
            JsonNode s = signup("retail");
            assertThat(s.at("/tenant/trialDaysLeft").asInt()).isEqualTo(14);

            // New prices show on the plan page.
            List<Map<String, Object>> plans = new ArrayList<>();
            for (JsonNode p : get("/admin/platform-settings", admin).at("/plans")) {
                Map<String, Object> m = new LinkedHashMap<>();
                p.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().isNumber() ? f.getValue().numberValue() : f.getValue().asText()));
                if ("LITE".equals(m.get("code"))) m.put("monthly", 1999);
                plans.add(m);
            }
            assertThat(call("PUT", "/admin/platform-settings", admin, Map.of("plans", plans)).status()).isEqualTo(200);
            assertThat(get("/billing/plans", token(s)).at("/plans/0/monthly").asInt()).isEqualTo(1999);
            plans.get(0).put("parts", 5);
            assertThat(call("PUT", "/admin/platform-settings", admin, Map.of("plans", plans)).status()).isEqualTo(400);
        } finally {
            jdbc.update("DELETE FROM platform_settings WHERE key IN ('trialDays', 'plans')");
        }
    }
}
