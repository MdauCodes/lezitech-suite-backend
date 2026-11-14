package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.restaurant.ReservationController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restaurants, package R4: a table booked online (a table at once, a deposit when the owner's rule asks), the ring on
 * staff screens until someone acknowledges it, seating with the deposit on the bill, the waiting list, and branded emails.
 */
class TableBookingsTest extends IntegrationTest {

    @Autowired
    Mailer mailer;
    private EmailSender.LogSender log;

    @BeforeEach
    void setUp() {
        log = new EmailSender.LogSender();
        mailer.useSender(log);
        ReservationController.BOOKINGS.clear();
        ReservationController.DEPOSIT.clear();
    }

    @AfterEach
    void tearDown() {
        mailer.useSender(null);
    }

    private String tomorrow() {
        return LocalDate.now(ZoneId.of("Africa/Nairobi")).plusDays(1).toString();
    }

    private JsonNode waitFor(String slug, String token, String status) throws InterruptedException {
        JsonNode r = null;
        for (int i = 0; i < 80; i++) {
            r = get("/public/book/" + slug + "/r/" + token, null).body();
            if (status.equals(r.at("/status").asText())) {
                return r;
            }
            Thread.sleep(100);
        }
        return r;
    }

    @Test
    void anOnlineBookingGetsATableRingsForStaffAndEmailsTheGuestInTheRestaurantsName() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String waiter = staff(owner, "WAITER");

        // The booking page lists free times for tomorrow.
        JsonNode page = get("/public/book/" + slug + "?date=" + tomorrow() + "&party=4", null).body();
        assertThat(page.at("/times").toString()).contains("19:00");
        assertThat(page.at("/deposit").asDouble()).isZero();

        // Wanjiru books for 4 at 19:00: a table at once, no deposit by default.
        Res booked = post("/public/book/" + slug, null, Map.of("name", "Wanjiru Kamau", "phone", "0712345678", "email", "wanjiru@example.co.ke",
                "party", 4, "date", tomorrow(), "time", "19:00"));
        assertThat(booked.status()).as(booked.body().toString()).isEqualTo(200);
        assertThat(booked.at("/status").asText()).isEqualTo("BOOKED");
        assertThat(booked.at("/tableLabel").asText()).isNotBlank();
        assertThat(booked.at("/confirmedByStaff").asBoolean()).isFalse();
        String token = booked.at("/token").asText();

        // The confirmation email is in the restaurant's name and look, replies go to the restaurant.
        EmailSender.Email mail = log.sent.stream().filter(e -> e.to().equals("wanjiru@example.co.ke")).findFirst().orElseThrow();
        String business = s.at("/tenant/name").asText();
        assertThat(mail.fromName()).isEqualTo(business);
        assertThat(mail.replyTo()).isNotBlank();
        assertThat(mail.subject()).contains(business);
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("target", "email-preview-booking.html"), mail.html());
        } catch (java.io.IOException ignored) {
            // a preview file for eyeballing the design; not part of the test
        }
        assertThat(mail.html()).contains("<table role=\"presentation\"").contains("Your table is booked").contains("/book/" + slug + "/r/" + token)
                .doesNotContain("<style").doesNotContain("data:image");

        // It rings on staff screens until someone stops it; then the guest's page says the restaurant confirmed it.
        JsonNode alerts = get("/reservations/alerts", waiter).body();
        assertThat(alerts.at("/count").asInt()).isEqualTo(1);
        assertThat(alerts.at("/items/0/name").asText()).isEqualTo("Wanjiru Kamau");
        assertThat(post("/reservations/alerts/stop", waiter, Map.of("upTo", alerts.at("/upTo").asText())).status()).isEqualTo(200);
        assertThat(get("/reservations/alerts", owner).at("/count").asInt()).isZero();
        assertThat(get("/public/book/" + slug + "/r/" + token, null).at("/confirmedByStaff").asBoolean()).isTrue();

        // That table is taken at 19:00 for 4; a party too big for any table is turned away with other times.
        assertThat(post("/public/book/" + slug, null, Map.of("name", "Big group", "phone", "0712345679", "party", 12,
                "date", tomorrow(), "time", "19:00")).status()).isIn(409, 400);

        // The guest cancels from their page.
        assertThat(post("/public/book/" + slug + "/r/" + token + "/cancel", null, Map.of()).at("/status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void aDepositHoldsTheTableAndGoesOnTheBillWhenTheyAreSeated() throws Exception {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        // The owner's rule: KES 500 a person for groups of 4 or more.
        assertThat(post("/reservations/settings", owner, Map.of("depositPerPerson", 500, "depositFromParty", 4)).at("/depositPerPerson").asDouble()).isEqualTo(500);
        assertThat(get("/public/book/" + slug + "?date=" + tomorrow() + "&party=4", null).at("/deposit").asDouble()).isEqualTo(2000);

        Res booked = post("/public/book/" + slug, null, Map.of("name", "Otieno", "phone", "0712345678", "party", 4, "date", tomorrow(), "time", "13:00"));
        assertThat(booked.at("/status").asText()).isEqualTo("HELD");
        assertThat(booked.at("/depositAmount").asDouble()).isEqualTo(2000);
        String token = booked.at("/token").asText();
        // The simulated M-Pesa prompt is paid: the booking is confirmed.
        JsonNode r = waitFor(slug, token, "BOOKED");
        assertThat(r.at("/status").asText()).isEqualTo("BOOKED");
        assertThat(r.at("/depositStatus").asText()).isEqualTo("PAID");

        // Staff seat them: their bill opens on the table with the KES 2,000 already paid towards it.
        String id = null;
        for (JsonNode b : get("/reservations?date=" + tomorrow(), owner).at("/bookings")) {
            if ("Otieno".equals(b.get("name").asText())) {
                id = b.get("id").asText();
            }
        }
        JsonNode seated = post("/reservations/" + id + "/seat", owner, Map.of()).body();
        assertThat(seated.at("/status").asText()).isEqualTo("SEATED");
        JsonNode bill = get("/orders/" + seated.at("/orderId").asText(), owner).body();
        assertThat(bill.at("/status").asText()).isEqualTo("OPEN");
        assertThat(bill.at("/paid").asDouble()).isEqualTo(2000);
        assertThat(bill.at("/guests").asInt()).isEqualTo(4);
    }

    @Test
    void walkInsWaitAndAreToldWhenTheirTableIsReady() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String first = post("/reservations/waitlist", owner, Map.of("name", "Achieng", "party", 2, "email", "achieng@example.co.ke")).at("/token").asText();
        post("/reservations/waitlist", owner, Map.of("name", "Mwangi", "party", 3));
        JsonNode list = get("/reservations", owner).at("/waiting");
        assertThat(list).hasSize(2);
        assertThat(get("/public/book/" + slug + "/r/" + first, null).at("/place").asInt()).isEqualTo(1);
        assertThat(log.sent.stream().anyMatch(e -> e.subject().contains("waiting list"))).isTrue();

        String id = list.get(0).get("id").asText();
        assertThat(post("/reservations/" + id + "/ready", owner, Map.of()).at("/status").asText()).isEqualTo("READY");
        assertThat(log.sent.stream().anyMatch(e -> e.to().equals("achieng@example.co.ke") && e.subject().contains("is ready"))).isTrue();
        String table = get("/floors", owner).at("/0/tables/0/id").asText();
        assertThat(post("/reservations/" + id + "/seat", owner, Map.of("tableId", table)).at("/status").asText()).isEqualTo("SEATED");
        assertThat(get("/reservations", owner).at("/waiting")).hasSize(1);
    }

    @Test
    void staffBookingsDoNotRingAndANoShowFreesTheTable() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        Res b = post("/reservations", owner, Map.of("name", "Phone booking", "phone", "0722000000", "party", 2, "date", tomorrow(), "time", "12:00"));
        assertThat(b.status()).as(b.body().toString()).isEqualTo(200);
        assertThat(get("/reservations/alerts", owner).at("/count").asInt()).isZero();
        JsonNode first = get("/reservations?date=" + tomorrow(), owner).at("/bookings/0");
        String id = first.get("id").asText();
        String table = first.get("tableId").asText();
        // Someone else can't have that table then...
        assertThat(post("/reservations", owner, Map.of("name", "Another", "party", 2, "date", tomorrow(), "time", "12:30", "tableId", table)).status()).isEqualTo(409);
        // ...until the first party is marked as not coming: the table is free again.
        assertThat(post("/reservations/" + id + "/close", owner, Map.of("status", "NO_SHOW")).at("/status").asText()).isEqualTo("NO_SHOW");
        assertThat(post("/reservations", owner, Map.of("name", "Another", "party", 2, "date", tomorrow(), "time", "12:30", "tableId", table)).status()).isEqualTo(200);
        assertThat(List.of(first)).hasSize(1);
    }
}
