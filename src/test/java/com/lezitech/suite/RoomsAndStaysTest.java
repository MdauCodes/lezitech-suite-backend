package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hotels: reserve a room, no double booking, check in, charge to the room, check out to the folio, housekeeping. */
class RoomsAndStaysTest extends IntegrationTest {

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Africa/Nairobi"));
    }

    private String freeRoom(String owner, LocalDate in, LocalDate out) {
        JsonNode free = get("/rooms/available?checkIn=" + in + "&checkOut=" + out, owner).body();
        assertThat(free.size()).as("the sample hotel has free rooms").isGreaterThan(0);
        return free.get(0).get("id").asText();
    }

    @Test
    void aGuestIsReservedCheckedInChargedAndCheckedOut() {
        String owner = token(signup("hotel"));
        LocalDate in = today().plusDays(20);
        LocalDate out = in.plusDays(2);
        String room = freeRoom(owner, in, out);

        Res reserved = post("/stays", owner, Map.of("spaceId", room, "checkIn", in.toString(), "checkOut", out.toString(),
                "guestName", "Achieng Otieno", "phone", "0712000111", "rate", 5000));
        assertThat(reserved.status()).as(reserved.body().toString()).isEqualTo(200);
        String stay = reserved.at("/id").asText();
        assertThat(reserved.at("/status").asText()).isEqualTo("RESERVED");

        // The same room can't be sold twice for overlapping nights; the room drops out of the free list.
        assertThat(post("/stays", owner, Map.of("spaceId", room, "checkIn", in.plusDays(1).toString(), "checkOut", out.plusDays(1).toString(),
                "guestName", "Someone else")).status()).isEqualTo(409);
        assertThat(get("/rooms/available?checkIn=" + in + "&checkOut=" + out, owner).body().toString()).doesNotContain(room);

        // Check in, put a drink on the room from the bar, check out: the nights go on the folio and the room needs cleaning.
        assertThat(post("/stays/" + stay + "/check-in", owner, Map.of()).status()).isEqualTo(200);
        String drink = item(owner, "").get("id").asText();
        String bar = post("/orders", owner, Map.of("lines", line(drink, 1))).at("/id").asText();
        assertThat(post("/orders/" + bar + "/charge-to-stay", owner, Map.of("stayId", stay)).status()).isEqualTo(200);

        JsonNode done = post("/stays/" + stay + "/check-out", owner, Map.of()).body();
        assertThat(done.get("status").asText()).isEqualTo("CHECKED_OUT");
        JsonNode folio = get("/orders/" + done.get("folioId").asText(), owner).body();
        assertThat(folio.get("lines").toString()).contains("Room ").contains("night");
        assertThat(folio.get("lines").size()).as("room nights and the bar charge").isGreaterThanOrEqualTo(2);
        JsonNode spaces = get("/spaces", owner).body();
        String hk = null;
        for (JsonNode s : spaces) {
            if (s.get("id").asText().equals(room)) {
                hk = s.get("housekeeping").asText();
            }
        }
        assertThat(hk).isEqualTo("DIRTY");
        assertThat(call("PATCH", "/spaces/" + room + "/housekeeping", owner, Map.of("status", "CLEAN")).status()).isEqualTo(200);
    }

    @Test
    void aReservationCanBeCancelledOrMarkedNoShowButNotAfterCheckIn() {
        String owner = token(signup("hotel"));
        LocalDate in = today().plusDays(30);
        String room = freeRoom(owner, in, in.plusDays(1));
        String stay = post("/stays", owner, Map.of("spaceId", room, "checkIn", in.toString(), "checkOut", in.plusDays(1).toString(),
                "guestName", "Brian")).at("/id").asText();
        assertThat(post("/stays/" + stay + "/cancel", owner, Map.of("noShow", true)).at("/status").asText()).isEqualTo("NO_SHOW");
        assertThat(post("/stays/" + stay + "/check-in", owner, Map.of()).status()).isEqualTo(409);
        // The room is free again for those dates.
        assertThat(get("/rooms/available?checkIn=" + in + "&checkOut=" + in.plusDays(1), owner).body().toString()).contains(room);
    }

    @Test
    void anotherBusinessCantSeeTheStay() {
        String owner = token(signup("hotel"));
        LocalDate in = today().plusDays(40);
        String room = freeRoom(owner, in, in.plusDays(1));
        String stay = post("/stays", owner, Map.of("spaceId", room, "checkIn", in.toString(), "checkOut", in.plusDays(1).toString(),
                "guestName", "Private Guest")).at("/id").asText();
        String other = token(signup("hotel"));
        assertThat(get("/stays/" + stay, other).status()).isEqualTo(404);
        assertThat(get("/stays/in-house", other).body().toString()).doesNotContain("Private Guest");
        assertThat(List.of(200)).contains(get("/rooms/chart", owner).status());
    }
}
