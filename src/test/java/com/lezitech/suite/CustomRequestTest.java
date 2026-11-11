package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "Build something for me" requests, from the website and from inside the app, land in the team's list. */
class CustomRequestTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void websiteRequestIsStoredAsCustomWork() {
        Res r = post("/public/setup-requests", null, Map.of("kind", "CUSTOM", "businessName", "Mama Njeri Stores", "contactName", "Njeri",
                "phone", "0712000111", "wants", List.of("WEBSITE", "MOBILE_APP", "NOT_A_THING"), "budget", "KES 50k to 150k", "timeline", "Within a month"));
        assertThat(r.status()).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap("SELECT kind, array_to_string(wants, ',') AS wants, budget FROM setup_requests WHERE id = ?::uuid", r.at("/id").asText());
        assertThat(row.get("kind")).isEqualTo("CUSTOM");
        assertThat(row.get("wants")).isEqualTo("WEBSITE,MOBILE_APP");
        assertThat(row.get("budget")).isEqualTo("KES 50k to 150k");
        // Custom work needs to say what.
        assertThat(post("/public/setup-requests", null, Map.of("kind", "CUSTOM", "businessName", "X", "contactName", "Y", "phone", "0712000111")).status()).isEqualTo(400);
    }

    @Test
    void ownerCanAskFromTheAppButACashierCant() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        Res r = post("/custom-requests", owner, Map.of("wants", List.of("INTEGRATION"), "notes", "Connect our online shop", "phone", "0712000222"));
        assertThat(r.status()).isEqualTo(200);
        String from = jdbc.queryForObject("SELECT from_tenant_id::text FROM setup_requests WHERE id = ?::uuid", String.class, r.at("/id").asText());
        assertThat(from).isEqualTo(s.at("/tenant/id").asText());
        assertThat(post("/custom-requests", cashier, Map.of("wants", List.of("WEBSITE"), "phone", "0712000222")).status()).isEqualTo(403);
        assertThat(post("/custom-requests", owner, Map.of("phone", "0712000222")).status()).isEqualTo(400);
    }
}
