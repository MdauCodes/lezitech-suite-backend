package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The first-day checklist ticks itself as the owner does each step, and fits the kind of business. */
class FirstStepsTest extends IntegrationTest {

    private boolean done(JsonNode steps, String key) {
        for (JsonNode s : steps.get("steps")) {
            if (s.get("key").asText().equals(key)) {
                return s.get("done").asBoolean();
            }
        }
        throw new AssertionError("no step " + key);
    }

    @Test
    void stepsTickThemselves() {
        String owner = token(signup("retail"));
        JsonNode start = get("/first-steps", owner).body();
        assertThat(start.at("/finished").asBoolean()).isFalse();
        assertThat(done(start, "items")).isFalse();
        assertThat(done(start, "staff")).isFalse();
        assertThat(done(start, "samples")).isFalse();

        post("/items", owner, Map.of("name", "Royco 200g", "kind", "PRODUCT", "price", 120));
        staff(owner, "CASHIER");
        JsonNode later = get("/first-steps", owner).body();
        assertThat(done(later, "items")).isTrue();
        assertThat(done(later, "staff")).isTrue();
        assertThat(later.at("/done").asInt()).isGreaterThan(start.at("/done").asInt());

        // A landlord's steps are about units and tenants, not a till.
        JsonNode landlord = get("/first-steps", token(signup("property"))).body();
        assertThat(landlord.toString()).contains("units").contains("tenants").doesNotContain("\"sale\"");
    }
}
