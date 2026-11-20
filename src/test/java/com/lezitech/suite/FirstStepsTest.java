package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        assertThat(done(start, "till")).isFalse();

        post("/items", owner, Map.of("name", "Royco 200g", "kind", "PRODUCT", "price", 120));
        staff(owner, "CASHIER");
        post("/shifts/open", owner, Map.of("openingCash", 500));
        JsonNode later = get("/first-steps", owner).body();
        assertThat(done(later, "items")).isTrue();
        assertThat(done(later, "staff")).isTrue();
        assertThat(done(later, "till")).isTrue();
        assertThat(later.at("/done").asInt()).isGreaterThan(start.at("/done").asInt());

        // A landlord's steps are about units and tenants, not a till.
        JsonNode landlord = get("/first-steps", token(signup("property"))).body();
        assertThat(landlord.toString()).contains("units").contains("tenants").doesNotContain("\"sale\"").doesNotContain("\"till\"");
    }

    @Test
    void aSalonIsToldToTakeABookingAndAShopIsNot() {
        String salon = token(signup("beauty"));
        JsonNode start = get("/first-steps", salon).body();
        assertThat(done(start, "booking")).isFalse();
        assertThat(start.toString()).contains("\"till\"").contains("\"sale\"");
        assertThat(start.toString().indexOf("\"booking\"")).isLessThan(start.toString().indexOf("\"sale\""));

        assertThat(get("/first-steps", token(signup("retail"))).body().toString()).doesNotContain("\"booking\"").doesNotContain("\"quote\"");
    }

    @Test
    void theBookingStepTicksItselfOnceAnAppointmentIsAdded() {
        String salon = token(signup("beauty"));
        assertThat(done(get("/first-steps", salon).body(), "booking")).isFalse();
        Instant at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        assertThat(post("/appointments", salon, Map.of("startsAt", at.toString(), "customerName", "Wanjiku")).status()).isEqualTo(200);
        assertThat(done(get("/first-steps", salon).body(), "booking")).isTrue();
    }

    @Test
    void aServicesBusinessIsToldToSendAQuoteAndItTicksWhenOneIsMade() {
        String owner = token(signup("services"));
        JsonNode start = get("/first-steps", owner).body();
        assertThat(done(start, "quote")).isFalse();
        assertThat(start.toString()).doesNotContain("\"booking\"");
        assertThat(post("/orders", owner, Map.of("kind", "QUOTE")).status()).isEqualTo(200);
        assertThat(done(get("/first-steps", owner).body(), "quote")).isTrue();
    }
}
