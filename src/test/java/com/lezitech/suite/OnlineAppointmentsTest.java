package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.booking.AppointmentPublicController;
import com.lezitech.suite.booking.OnlineAppointments;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.support.IntegrationTest;
import com.lezitech.suite.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2: clients book a salon online, pay a deposit if asked, get a confirmation and reminders, and can cancel. */
class OnlineAppointmentsTest extends IntegrationTest {

    @Autowired
    Mailer mailer;
    @Autowired
    OnlineAppointments appts;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txm;

    EmailSender.LogSender outbox;

    @BeforeEach
    void setUp() {
        outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
        AppointmentPublicController.BOOKINGS.clear();
        AppointmentPublicController.DEPOSIT.clear();
    }

    record Salon(String owner, String slug, String tenant, String service) {
    }

    private Salon salon() {
        JsonNode s = signup("beauty");
        String owner = token(s);
        staff(owner, "STYLIST");
        String slug = s.at("/tenant/slug").asText();
        JsonNode page = get("/public/appointments/" + slug, null).body();
        return new Salon(owner, slug, s.at("/tenant/id").asText(), page.at("/services/0/id").asText());
    }

    /** A Wednesday a week or more ahead (open 08:00-19:00 by default). */
    private static LocalDate wednesday() {
        return LocalDate.now(ZoneId.of("Africa/Nairobi")).plusDays(7).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));
    }

    private JsonNode times(Salon s, LocalDate day) {
        return get("/public/appointments/" + s.slug() + "?date=" + day + "&itemId=" + s.service(), null).body().get("times");
    }

    private Res book(Salon s, LocalDate day, String time, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("itemId", s.service(), "date", day.toString(), "time", time,
                "name", "Wanjiku Kamau", "phone", "0712345678", "email", "wanjiku@example.test"));
        body.putAll(extra);
        return post("/public/appointments/" + s.slug(), null, body);
    }

    private <T> T as(Salon s, java.util.function.Supplier<T> work) {
        return TenantContext.runAs(UUID.fromString(s.tenant()), null, () -> new TransactionTemplate(txm).execute(x -> work.get()));
    }

    @Test
    void aClientBooksAFreeTimeAndGetsAConfirmation() {
        Salon s = salon();
        LocalDate day = wednesday();
        JsonNode free = times(s, day);
        assertThat(free.size()).isGreaterThan(0);
        String first = free.get(0).get("time").asText();
        assertThat(first).isEqualTo("08:00");

        Res booked = book(s, day, first, Map.of());
        assertThat(booked.status()).as(booked.body().toString()).isEqualTo(200);
        assertThat(booked.at("/status").asText()).isEqualTo("BOOKED");
        assertThat(booked.at("/token").asText()).isNotBlank();
        assertThat(booked.body().has("id")).isFalse();
        assertThat(outbox.sent.stream().anyMatch(e -> e.to().equals("wanjiku@example.test") && e.subject().startsWith("Booked:"))).isTrue();

        // The staff diary shows it as an online booking, and it rings until seen.
        JsonNode diary = get("/appointments?date=" + day, s.owner()).body();
        assertThat(diary.get("appointments").toString()).contains("ONLINE").contains("Wanjiku Kamau");
        assertThat(get("/appointments/alerts", s.owner()).body().get("count").asInt()).isEqualTo(1);
        post("/appointments/alerts/stop", s.owner(), Map.of());
        assertThat(get("/appointments/alerts", s.owner()).body().get("count").asInt()).isZero();
    }

    @Test
    void aTakenTimeCantBeBookedTwiceAndHoursAreRespected() {
        Salon s = salon();
        LocalDate day = wednesday();
        // One stylist working only from 10:00 to 17:00 on Wednesdays.
        String stylist = userId(s.owner(), "STYLIST");
        Map<String, Object> week = new HashMap<>();
        week.put("wed", List.of("10:00", "17:00"));
        assertThat(post("/appointments/staff/" + stylist + "/hours", s.owner(), Map.of("hours", week)).status()).isEqualTo(200);
        JsonNode free = get("/public/appointments/" + s.slug() + "?date=" + day + "&itemId=" + s.service() + "&staffId=" + stylist, null).body().get("times");
        assertThat(free.get(0).get("time").asText()).isEqualTo("10:00");
        assertThat(free.toString()).doesNotContain("\"time\":\"09:45\"").doesNotContain("\"time\":\"13:15\"");
        String ten = free.get(0).get("time").asText();
        assertThat(free.get(0).get("staffIds").toString()).contains(stylist);

        assertThat(book(s, day, ten, Map.of("staffId", stylist)).status()).isEqualTo(200);
        Res again = book(s, day, ten, Map.of("staffId", stylist, "phone", "0722000000", "name", "Akinyi"));
        assertThat(again.status()).isEqualTo(409);
        String withHer = "/public/appointments/" + s.slug() + "?itemId=" + s.service() + "&staffId=" + stylist + "&date=";
        assertThat(get(withHer + day, null).body().get("times").toString()).doesNotContain("\"10:00\"");
        // Closed days have no times, and missing details are refused.
        assertThat(get(withHer + day.plusDays(1), null).body().get("times").size()).isZero();
        assertThat(post("/public/appointments/" + s.slug(), null, Map.of("itemId", s.service(), "date", day.toString(), "time", "11:00")).status()).isEqualTo(400);
    }

    @Test
    void aDepositHoldsTheTimeUntilPaidAndGoesOnTheBill() throws Exception {
        Salon s = salon();
        assertThat(post("/appointments/settings", s.owner(), Map.of("deposit", 500)).status()).isEqualTo(200);
        LocalDate day = wednesday();
        String time = times(s, day).get(0).get("time").asText();
        Res held = book(s, day, time, Map.of());
        assertThat(held.status()).as(held.body().toString()).isEqualTo(200);
        assertThat(held.at("/status").asText()).isEqualTo("HELD");
        assertThat(held.at("/payment/status").asText()).isEqualTo("PENDING");
        String token = held.at("/token").asText();

        JsonNode mine = null;
        for (int i = 0; i < 60; i++) {
            mine = get("/public/appointments/" + s.slug() + "/a/" + token, null).body();
            if ("BOOKED".equals(mine.get("status").asText())) {
                break;
            }
            Thread.sleep(150);
        }
        assertThat(mine.get("status").asText()).isEqualTo("BOOKED");
        assertThat(mine.get("depositStatus").asText()).isEqualTo("PAID");

        // At checkout the deposit is already on the bill.
        post("/shifts/open", s.owner(), Map.of("openingCash", 0));
        String id = null;
        for (JsonNode a : get("/appointments?date=" + day, s.owner()).body().get("appointments")) {
            if (token.equals(a.get("token").asText())) {
                id = a.get("id").asText();
            }
        }
        JsonNode bill = post("/appointments/" + id + "/checkout", s.owner(), Map.of()).body();
        assertThat(bill.get("paid").decimalValue()).isEqualByComparingTo("500");
    }

    @Test
    void remindersGoOnceAndClientsCanCancelUntilTheCutOff() {
        Salon s = salon();
        LocalDate day = wednesday();
        String time = times(s, day).get(0).get("time").asText();
        String token = book(s, day, time, Map.of()).at("/token").asText();

        // Pretend it's the evening before: booked long ago, starting within 24 hours.
        UUID id = as(s, () -> appts.idOfToken(token));
        as(s, () -> jdbc.update("UPDATE appointments SET starts_at = now() + interval '20 hours', ends_at = now() + interval '21 hours', created_at = now() - interval '3 days' WHERE id = ?", id));
        assertThat(as(s, () -> appts.sendDueReminders())).isEqualTo(1);
        assertThat(as(s, () -> appts.sendDueReminders())).isZero();
        assertThat(outbox.sent.stream().filter(e -> e.subject().startsWith("Reminder:")).count()).isEqualTo(1);
        // Staff can send one by hand too.
        assertThat(post("/appointments/" + id + "/remind", s.owner(), Map.of()).at("/status").asText()).isEqualTo("SENT");

        // Within the cut-off (72 hours here), the client can't cancel online.
        post("/appointments/settings", s.owner(), Map.of("cancelHours", 72));
        assertThat(post("/public/appointments/" + s.slug() + "/a/" + token + "/cancel", null, Map.of()).status()).isEqualTo(409);
        post("/appointments/settings", s.owner(), Map.of("cancelHours", 2));
        Res cancelled = post("/public/appointments/" + s.slug() + "/a/" + token + "/cancel", null, Map.of());
        assertThat(cancelled.at("/status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void restaurantsAndOtherBusinessesDontGetTheSalonPage() {
        JsonNode r = signup("restaurant");
        assertThat(get("/public/appointments/" + r.at("/tenant/slug").asText(), null).status()).isEqualTo(404);
        Salon a = salon();
        Salon b = salon();
        String token = book(a, wednesday(), times(a, wednesday()).get(0).get("time").asText(), Map.of()).at("/token").asText();
        assertThat(get("/public/appointments/" + b.slug() + "/a/" + token, null).status()).isEqualTo(404);
        // Switched off: no times and no bookings.
        post("/appointments/settings", a.owner(), Map.of("online", false));
        assertThat(times(a, wednesday()).size()).isZero();
        assertThat(book(a, wednesday(), "09:00", Map.of()).status()).isEqualTo(403);
    }
}
