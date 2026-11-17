package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Salons: book a client with a stylist, no double booking for the stylist, check out to a bill credited to them. */
class AppointmentsTest extends IntegrationTest {

    private JsonNode service(String owner) {
        for (JsonNode i : get("/items", owner).body()) {
            if ("SERVICE".equals(i.get("kind").asText())) {
                return i;
            }
        }
        throw new AssertionError("The sample salon has a service");
    }

    @Test
    void aBookingBecomesABillCreditedToTheStylist() {
        String owner = token(signup("beauty"));
        staff(owner, "STYLIST");
        String stylist = userId(owner, "STYLIST");
        JsonNode svc = service(owner);
        Instant at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        Res booked = post("/appointments", owner, Map.of("startsAt", at.toString(), "itemId", svc.get("id").asText(),
                "staffId", stylist, "customerName", "Wanjiku", "phone", "0712555666"));
        assertThat(booked.status()).as(booked.body().toString()).isEqualTo(200);
        String id = booked.at("/id").asText();

        // The stylist can't be booked twice for overlapping times; a different time is fine.
        assertThat(post("/appointments", owner, Map.of("startsAt", at.plus(10, ChronoUnit.MINUTES).toString(),
                "itemId", svc.get("id").asText(), "staffId", stylist, "customerName", "Akinyi")).status()).isEqualTo(409);
        assertThat(post("/appointments", owner, Map.of("startsAt", at.plus(5, ChronoUnit.HOURS).toString(),
                "itemId", svc.get("id").asText(), "staffId", stylist, "customerName", "Akinyi")).status()).isEqualTo(200);
        // A booking needs a name.
        assertThat(post("/appointments", owner, Map.of("startsAt", at.plus(9, ChronoUnit.HOURS).toString())).status()).isEqualTo(400);

        assertThat(call("PATCH", "/appointments/" + id, owner, Map.of("status", "ARRIVED")).status()).isEqualTo(200);
        JsonNode bill = post("/appointments/" + id + "/checkout", owner, Map.of()).body();
        assertThat(bill.get("lines").toString()).contains(svc.get("name").asText());
        assertThat(bill.at("/lines/0/staffId").asText()).isEqualTo(stylist);
        // Checking out twice opens the same bill.
        assertThat(post("/appointments/" + id + "/checkout", owner, Map.of()).body().get("id").asText()).isEqualTo(bill.get("id").asText());
    }

    @Test
    void noShowsAreRecordedAndOtherBusinessesSeeNothing() {
        String owner = token(signup("beauty"));
        Instant at = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        String id = post("/appointments", owner, Map.of("startsAt", at.toString(), "customerName", "Private Client")).at("/id").asText();
        assertThat(call("PATCH", "/appointments/" + id, owner, Map.of("status", "NO_SHOW")).at("/status").asText()).isEqualTo("NO_SHOW");
        assertThat(call("PATCH", "/appointments/" + id, owner, Map.of("status", "MAYBE")).status()).isEqualTo(400);
        String other = token(signup("beauty"));
        String day = at.atZone(java.time.ZoneId.of("Africa/Nairobi")).toLocalDate().toString();
        assertThat(get("/appointments?date=" + day, other).body().toString()).doesNotContain("Private Client");
        assertThat(get("/appointments?date=" + day, owner).body().toString()).contains("Private Client");
    }
}
