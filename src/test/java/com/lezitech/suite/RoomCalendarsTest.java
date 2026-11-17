package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.rooms.OnlineStays;
import com.lezitech.suite.rooms.RoomCalendars;
import com.lezitech.suite.rooms.StayPublicController;
import com.lezitech.suite.support.IntegrationTest;
import com.lezitech.suite.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3: hotels in step with Airbnb/Booking.com by iCal, seasonal prices, booking online with a deposit, the guest register. */
class RoomCalendarsTest extends IntegrationTest {

    @Autowired
    RoomCalendars calendars;
    @Autowired
    OnlineStays online;
    @Autowired
    Mailer mailer;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txm;

    EmailSender.LogSender outbox;
    String feed = "";

    @BeforeEach
    void setUp() {
        outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
        calendars.useFetcher(url -> feed);
        StayPublicController.BOOKINGS.clear();
        StayPublicController.DEPOSIT.clear();
    }

    @AfterEach
    void tearDown() {
        calendars.useFetcher(url -> {
            throw new IllegalStateException("no network in tests");
        });
    }

    record Hotel(String owner, String slug, String tenant, String room, String type) {
    }

    /** A hotel with its own room of a type nobody else has, so the tests don't depend on the sample rooms. */
    private Hotel hotel() {
        JsonNode s = signup("hotel");
        String owner = token(s);
        String type = "Garden " + UUID.randomUUID().toString().substring(0, 6);
        Res room = post("/spaces", owner, Map.of("name", "G1", "kind", "ROOM", "typeName", type, "capacity", 2, "rate", 4000));
        assertThat(room.status()).as(room.body().toString()).isEqualTo(200);
        return new Hotel(owner, s.at("/tenant/slug").asText(), s.at("/tenant/id").asText(), room.at("/id").asText(), type);
    }

    private <T> T as(Hotel h, Supplier<T> work) {
        return TenantContext.runAs(UUID.fromString(h.tenant()), null, () -> new TransactionTemplate(txm).execute(x -> work.get()));
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Africa/Nairobi"));
    }

    private static String ics(String... events) {
        StringBuilder b = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Airbnb Inc//Hosting Calendar//EN\r\n");
        for (int i = 0; i < events.length; i += 3) {
            b.append("BEGIN:VEVENT\r\nDTSTART;VALUE=DATE:").append(events[i].replace("-", "")).append("\r\nDTEND;VALUE=DATE:")
                    .append(events[i + 1].replace("-", "")).append("\r\nUID:").append(events[i + 2])
                    .append("\r\nSUMMARY:Reserved\r\nEND:VEVENT\r\n");
        }
        return b.append("END:VCALENDAR\r\n").toString();
    }

    private Res reserve(Hotel h, LocalDate in, LocalDate out, String guest) {
        return post("/stays", h.owner(), Map.of("spaceId", h.room(), "checkIn", in.toString(), "checkOut", out.toString(), "guestName", guest));
    }

    @Test
    void anotherSitesBookingBlocksTheRoomAndOurCalendarGoesOutWithoutNames() throws Exception {
        Hotel h = hotel();
        LocalDate in = today().plusDays(40);
        // Airbnb has nights 5-7; the feed also echoes one of our own bookings back, which must not block us.
        feed = ics(in.plusDays(5).toString(), in.plusDays(7).toString(), "abc123@airbnb.com",
                in.plusDays(20).toString(), in.plusDays(22).toString(), "whatever@biznez");
        Res cal = post("/rooms/calendars", h.owner(), Map.of("spaceId", h.room(), "source", "Airbnb", "url", "https://93.184.216.34/cal.ics"));
        assertThat(cal.status()).as(cal.body().toString()).isEqualTo(200);
        assertThat(cal.at("/lastError").isNull()).as(cal.body().toString()).isTrue();

        assertThat(reserve(h, in.plusDays(6), in.plusDays(8), "Clash").status()).isEqualTo(409);
        assertThat(get("/rooms/available?checkIn=" + in.plusDays(5) + "&checkOut=" + in.plusDays(6), h.owner()).body().toString()).doesNotContain(h.room());
        assertThat(get("/rooms/chart?from=" + in + "&days=10", h.owner()).at("/blocks").toString()).contains("Airbnb");
        assertThat(reserve(h, in.plusDays(20), in.plusDays(22), "Echo is fine").status()).isEqualTo(200);
        assertThat(reserve(h, in.plusDays(10), in.plusDays(12), "Secret Guest").status()).isEqualTo(200);

        // Airbnb's calendar changes: the old block goes on the next read.
        feed = ics();
        String calId = cal.at("/id").asText();
        assertThat(post("/rooms/calendars/" + calId + "/sync", h.owner(), Map.of()).status()).isEqualTo(200);
        assertThat(reserve(h, in.plusDays(5), in.plusDays(7), "Now free").status()).isEqualTo(200);

        // Our calendar for Airbnb: dates only, never names; the secret link is needed.
        String token = null;
        for (JsonNode sp : get("/spaces", h.owner()).body()) {
            if (sp.get("id").asText().equals(h.room())) {
                token = sp.get("icalToken").asText();
            }
        }
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + port + "/api/public/ical/" + h.slug() + "/" + h.room() + "/";
        HttpResponse<String> ics = http.send(HttpRequest.newBuilder(URI.create(base + token + ".ics")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ics.statusCode()).isEqualTo(200);
        assertThat(ics.headers().firstValue("Content-Type").orElse("")).startsWith("text/calendar");
        assertThat(ics.body()).contains("BEGIN:VCALENDAR").contains("@biznez").contains("Booked")
                .contains(in.plusDays(10).toString().replace("-", "")).doesNotContain("Secret Guest");
        assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "wrongtoken.ics")).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(404);
    }

    @Test
    void unsafeCalendarLinksAreRefused() {
        Hotel h = hotel();
        for (String bad : List.of("http://93.184.216.34/cal.ics", "https://127.0.0.1/cal.ics", "https://10.0.0.5/cal.ics",
                "https://192.168.1.1/cal.ics", "https://100.64.0.1/cal.ics", "https://169.254.169.254/latest", "https://[::1]/cal.ics",
                "file:///etc/passwd")) {
            assertThat(post("/rooms/calendars", h.owner(), Map.of("spaceId", h.room(), "url", bad)).status()).as(bad).isEqualTo(400);
        }
        String reception = staff(h.owner(), "RECEPTION");
        assertThat(post("/rooms/calendars", reception, Map.of("spaceId", h.room(), "url", "https://93.184.216.34/cal.ics")).status()).isEqualTo(403);
        assertThat(get("/rooms/calendars", h.owner()).body().size()).isZero();
    }

    @Test
    void weekendNightsTakeTheirOwnPrice() {
        Hotel h = hotel();
        Res rule = post("/rooms/rates", h.owner(), Map.of("name", "Weekend", "typeName", h.type(), "weekdays", List.of("fri", "sat"), "rate", 6000));
        assertThat(rule.status()).as(rule.body().toString()).isEqualTo(200);
        // Thursday to Sunday: Thu 4,000 + Fri 6,000 + Sat 6,000.
        LocalDate thu = today().plusDays(14).with(TemporalAdjusters.next(DayOfWeek.THURSDAY));
        JsonNode q = get("/rooms/quote?spaceId=" + h.room() + "&checkIn=" + thu + "&checkOut=" + thu.plusDays(3), h.owner()).body();
        assertThat(q.get("total").decimalValue()).isEqualByComparingTo("16000");
        assertThat(q.get("nights").asInt()).isEqualTo(3);
        JsonNode stay = reserve(h, thu, thu.plusDays(3), "Weekender").body();
        assertThat(stay.get("roomTotal").decimalValue()).isEqualByComparingTo("16000");
        // A season beats the weekend rule only where it is more specific; here a dated rule for the Friday wins.
        assertThat(post("/rooms/rates", h.owner(), Map.of("name", "Holiday", "spaceId", h.room(), "startsOn", thu.plusDays(1).toString(),
                "endsOn", thu.plusDays(1).toString(), "rate", 9000)).status()).isEqualTo(200);
        assertThat(get("/rooms/quote?spaceId=" + h.room() + "&checkIn=" + thu + "&checkOut=" + thu.plusDays(3), h.owner()).at("/total").decimalValue())
                .isEqualByComparingTo("19000");
        // Only the manager sets prices.
        assertThat(post("/rooms/rates", staff(h.owner(), "RECEPTION"), Map.of("rate", 1)).status()).isEqualTo(403);
    }

    @Test
    void aGuestBooksOnlineAndTheFrontDeskIsTold() {
        Hotel h = hotel();
        LocalDate in = today().plusDays(60);
        JsonNode page = get("/public/stay/" + h.slug() + "?checkIn=" + in + "&checkOut=" + in.plusDays(2) + "&guests=2", null).body();
        JsonNode mine = null;
        for (JsonNode o : page.get("options")) {
            if (o.get("type").asText().equals(h.type())) {
                mine = o;
            }
        }
        assertThat(mine).as(page.toString()).isNotNull();
        assertThat(mine.get("total").decimalValue()).isEqualByComparingTo("8000");

        Res booked = post("/public/stay/" + h.slug(), null, Map.of("type", h.type(), "checkIn", in.toString(), "checkOut", in.plusDays(2).toString(),
                "adults", 2, "name", "Wanjiru Kamau", "phone", "0712345678", "email", "wanjiru@example.test"));
        assertThat(booked.status()).as(booked.body().toString()).isEqualTo(200);
        assertThat(booked.at("/status").asText()).isEqualTo("RESERVED");
        assertThat(booked.body().has("id")).isFalse();
        String token = booked.at("/token").asText();
        assertThat(outbox.sent.stream().anyMatch(e -> e.to().equals("wanjiru@example.test"))).isTrue();
        assertThat(get("/public/stay/" + h.slug() + "/s/" + token, null).at("/guestName").asText()).isEqualTo("Wanjiru Kamau");

        // The room is gone for those dates, and the front desk sees a new online booking.
        assertThat(post("/public/stay/" + h.slug(), null, Map.of("type", h.type(), "checkIn", in.toString(), "checkOut", in.plusDays(1).toString(),
                "name", "Late", "phone", "0712000000")).status()).isEqualTo(409);
        assertThat(get("/rooms/alerts", h.owner()).at("/count").asInt()).isEqualTo(1);
        assertThat(post("/rooms/alerts/stop", h.owner(), Map.of()).status()).isEqualTo(200);
        assertThat(get("/rooms/alerts", h.owner()).at("/count").asInt()).isZero();

        // The guest cancels well before arrival; the room is free again.
        assertThat(post("/public/stay/" + h.slug() + "/s/" + token + "/cancel", null, Map.of()).at("/status").asText()).isEqualTo("CANCELLED");
        assertThat(get("/rooms/available?checkIn=" + in + "&checkOut=" + in.plusDays(2), h.owner()).body().toString()).contains(h.room());

        // Closed online booking, and businesses without rooms have no page.
        assertThat(post("/rooms/settings", h.owner(), Map.of("online", false)).status()).isEqualTo(200);
        assertThat(post("/public/stay/" + h.slug(), null, Map.of("type", h.type(), "checkIn", in.toString(), "checkOut", in.plusDays(1).toString(),
                "name", "Closed", "phone", "0712000000")).status()).isEqualTo(403);
        assertThat(get("/public/stay/" + signup("beauty").at("/tenant/slug").asText(), null).status()).isEqualTo(404);
        assertThat(get("/public/stay/" + h.slug() + "/s/nope", null).status()).isEqualTo(404);
    }

    @Test
    void aDepositHoldsTheRoomUntilPaidAndUnpaidHoldsAreLetGo() throws Exception {
        Hotel h = hotel();
        assertThat(post("/rooms/settings", h.owner(), Map.of("depositPercent", 50)).status()).isEqualTo(200);
        LocalDate in = today().plusDays(70);
        Map<String, Object> body = new HashMap<>(Map.of("type", h.type(), "checkIn", in.toString(), "checkOut", in.plusDays(2).toString(),
                "name", "Otieno", "phone", "0712345678", "email", "otieno@example.test"));
        Res booked = post("/public/stay/" + h.slug(), null, body);
        assertThat(booked.status()).as(booked.body().toString()).isEqualTo(200);
        assertThat(booked.at("/status").asText()).isEqualTo("HELD");
        assertThat(booked.at("/depositAmount").decimalValue()).isEqualByComparingTo("4000");
        String token = booked.at("/token").asText();
        JsonNode mine = null;
        for (int i = 0; i < 40; i++) {
            mine = get("/public/stay/" + h.slug() + "/s/" + token, null).body();
            if ("RESERVED".equals(mine.get("status").asText())) {
                break;
            }
            Thread.sleep(150);
        }
        assertThat(mine.get("status").asText()).isEqualTo("RESERVED");
        assertThat(mine.get("paid").decimalValue()).isEqualByComparingTo("4000");

        // A second guest's payment fails (simulator: phones ending 9999); when the hold runs out the room is let go.
        Res failed = post("/public/stay/" + h.slug(), null, Map.of("type", h.type(), "checkIn", in.plusDays(5).toString(),
                "checkOut", in.plusDays(6).toString(), "name", "No pay", "phone", "0712349999"));
        assertThat(failed.at("/status").asText()).isEqualTo("HELD");
        assertThat(reserve(h, in.plusDays(5), in.plusDays(6), "Walk-in").status()).as("held for the deposit").isEqualTo(409);
        Thread.sleep(800);
        as(h, () -> jdbc.update("UPDATE stays SET hold_until = now() - interval '1 minute' WHERE token = ?", failed.at("/token").asText()));
        assertThat(as(h, () -> online.sweep())).isEqualTo(1);
        assertThat(get("/public/stay/" + h.slug() + "/s/" + failed.at("/token").asText(), null).at("/status").asText()).isEqualTo("CANCELLED");
        assertThat(reserve(h, in.plusDays(5), in.plusDays(6), "Walk-in").status()).isEqualTo(200);
        // The paid one is never let go.
        as(h, () -> jdbc.update("UPDATE stays SET hold_until = now() - interval '1 minute' WHERE token = ?", token));
        assertThat(as(h, () -> online.sweep())).isZero();
    }

    @Test
    void theGuestRegisterIsAskedAtCheckInWhenSwitchedOn() {
        Hotel h = hotel();
        assertThat(post("/rooms/settings", h.owner(), Map.of("requireGuestId", true)).status()).isEqualTo(200);
        LocalDate in = today();
        String stay = reserve(h, in, in.plusDays(1), "Mary Njeri").at("/id").asText();
        assertThat(post("/stays/" + stay + "/check-in", h.owner(), Map.of()).status()).isEqualTo(400);
        assertThat(post("/stays/" + stay + "/check-in", h.owner(), Map.of("idType", "National ID", "idNumber", "12345678",
                "nationality", "Kenyan", "comingFrom", "Nakuru", "vehicleReg", "KDA 123A")).status()).isEqualTo(200);
        JsonNode reg = get("/rooms/register?from=" + in + "&to=" + in.plusDays(1), h.owner()).body();
        assertThat(reg.toString()).contains("12345678").contains("Kenyan").contains("KDA 123A");
        assertThat(get("/rooms/register?from=" + in + "&to=" + in, staff(h.owner(), "WAITER")).status()).isEqualTo(403);
        assertThat(get("/rooms/register?from=" + in + "&to=" + in, token(signup("hotel"))).body().toString()).doesNotContain("12345678");
    }
}
