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

    private java.util.List<String> keys(JsonNode steps) {
        java.util.List<String> k = new java.util.ArrayList<>();
        steps.get("steps").forEach(x -> k.add(x.get("key").asText()));
        return k;
    }

    @Test
    void aHotelAddsRoomsThenTakesABookingAndNeverEndsOnAShopSale() {
        String owner = token(signup("hotel"));
        JsonNode start = get("/first-steps", owner).body();
        assertThat(keys(start)).containsSubsequence("rooms", "details", "mpesa", "staff", "booking", "till").doesNotContain("sale");
        assertThat(start.toString()).contains("Add your rooms").contains("Take your first room booking");
        assertThat(done(start, "rooms")).isFalse();
        assertThat(done(start, "booking")).isFalse();

        assertThat(post("/spaces", owner, Map.of("name", "Room 901", "rate", 4000)).status()).isEqualTo(200);
        java.time.LocalDate in = java.time.LocalDate.now().plusDays(30);
        String room = get("/rooms/available?checkIn=" + in + "&checkOut=" + in.plusDays(2), owner).body().get(0).get("id").asText();
        assertThat(post("/stays", owner, Map.of("spaceId", room, "checkIn", in.toString(), "checkOut", in.plusDays(2).toString(),
                "guestName", "Achieng", "phone", "0712000111", "rate", 5000)).status()).isEqualTo(200);
        JsonNode later = get("/first-steps", owner).body();
        assertThat(done(later, "rooms")).isTrue();
        assertThat(done(later, "booking")).isTrue();
    }

    @Test
    void aTourOperatorAddsATripThenTakesABooking() {
        String owner = token(signup("tours"));
        JsonNode start = get("/first-steps", owner).body();
        assertThat(keys(start)).containsSubsequence("trip", "booking").doesNotContain("till", "sale");
        assertThat(start.toString()).contains("Add your first trip").contains("Take your first booking");
        assertThat(done(start, "trip")).isFalse();

        Res trip = post("/trips", owner, Map.of("itemId", item(owner, "").get("id").asText(), "startsOn", java.time.LocalDate.now().plusDays(21).toString(), "seats", 4));
        assertThat(done(get("/first-steps", owner).body(), "trip")).isTrue();
        assertThat(done(get("/first-steps", owner).body(), "booking")).isFalse();
        assertThat(post("/trips/" + trip.at("/id").asText() + "/bookings", owner, Map.of("leadName", "The Smiths", "pax", 2)).status()).isEqualTo(200);
        assertThat(done(get("/first-steps", owner).body(), "booking")).isTrue();
    }

    @Test
    void aServicesBusinessHasNoTillStepAndEndsOnItsFirstPayment() {
        JsonNode start = get("/first-steps", token(signup("services"))).body();
        assertThat(keys(start)).containsSubsequence("items", "details", "mpesa", "staff", "quote", "sale").doesNotContain("till");
        assertThat(start.toString()).contains("Send your first quote").contains("Get your first payment").doesNotContain("Open your till");
    }
}
