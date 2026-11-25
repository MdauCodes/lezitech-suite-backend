package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.attendance.AttendanceClock;
import com.lezitech.suite.attendance.AttendanceJobs;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staff attendance: clocking at the branch (location, then a selfie or the shop code), hours, lateness, forgotten
 * clock-outs, absences, the board and timesheets, tenants, the plan, and the selfie purge. Time is frozen to try hours.
 */
class AttendanceTest extends IntegrationTest {

    static final double LAT = -1.2921, LNG = 36.8219;
    /** About 111 m per 0.001 degree of latitude. */
    static final double DEG_PER_METER = 0.001 / 111.0;

    @Autowired
    AttendanceClock clock;
    @Autowired
    AttendanceJobs jobs;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void unfreeze() {
        clock.freeze(null);
    }

    /** A Nairobi time on Monday 23 November 2026, e.g. at("08:05") or at(1, "09:00") for the next day. */
    private static Instant at(String hhmm) {
        return at(0, hhmm);
    }

    private static Instant at(int plusDays, String hhmm) {
        return LocalDateTime.parse("2026-11-23T" + hhmm).plusDays(plusDays).atZone(AttendanceClock.NAIROBI).toInstant();
    }

    private record Shop(JsonNode session, String owner, String branch) {
    }

    private Shop shop() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String branch = get("/branches", owner).at("/branches/0/id").asText();
        Res set = call("PUT", "/attendance/branches/" + branch + "/location", owner, Map.of("lat", LAT, "lng", LNG, "accuracy", 10, "radiusM", 100));
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            days.add(Map.of("weekday", d, "start", "08:00", "end", "17:00", "graceMin", 10));
        }
        assertThat(call("PUT", "/attendance/branches/" + branch + "/hours", owner, Map.of("days", days)).status()).isEqualTo(200);
        return new Shop(s, owner, branch);
    }

    private static String jpeg() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
        o.writeBytes(head);
        o.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1, 0, 18, 'E', 'x', 'i', 'f', 0, 0, 'G', 'P', 'S', '-', 'S', 'E', 'C', 'R', 'E', 'T'});
        o.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 0x3F, 0, 1, 2, 3, 4, 5, (byte) 0xFF, (byte) 0xD9});
        return Base64.getEncoder().encodeToString(o.toByteArray());
    }

    private Res clockAt(String token, String kind, double northM, double eastM, double accuracy, Map<String, Object> extra) {
        Map<String, Object> b = new HashMap<>();
        b.put("kind", kind);
        b.put("lat", LAT + northM * DEG_PER_METER);
        b.put("lng", LNG + eastM * DEG_PER_METER);
        b.put("accuracy", accuracy);
        b.putAll(extra);
        return post("/attendance/clock", token, b);
    }

    private Res in(String token) {
        return clockAt(token, "IN", 5, 5, 12, Map.of("selfie", jpeg()));
    }

    private Res out(String token) {
        return clockAt(token, "OUT", 5, 5, 12, Map.of("selfie", jpeg()));
    }

    private String liveCode(Shop s, String branch) {
        return get("/attendance/branches/" + branch + "/code", s.owner()).at("/code").asText();
    }

    private String idOf(String staffToken) {
        return get("/auth/me", staffToken).at("/user/id").asText();
    }

    private JsonNode person(Shop s, String userId) {
        for (JsonNode p : get("/attendance/board", s.owner()).at("/people")) {
            if (p.get("userId").asText().equals(userId)) {
                return p;
            }
        }
        throw new AssertionError("not on the board");
    }

    @Test
    void clockingInAtTheShopWorksAndTheSelfieIsStrippedAndPrivate() {
        Shop s = shop();
        clock.freeze(at("08:05"));
        String cashier = staff(s.owner(), "CASHIER");
        Res r = in(cashier);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/clockedIn").asBoolean()).isTrue();
        assertThat(r.at("/event/method").asText()).isEqualTo("SELFIE");

        JsonNode me = person(s, idOf(cashier));
        assertThat(me.get("status").asText()).isEqualTo("IN");
        String file = me.get("selfieFileId").asText();
        Raw photo = raw("/files/" + file, s.owner());
        assertThat(photo.status()).isEqualTo(200);
        assertThat(new String(photo.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("GPS-SECRET").doesNotContain("Exif");
        assertThat(photo.bytes()[0] & 0xff).isEqualTo(0xFF);
        // The person's own photo is theirs to see; a colleague's is not.
        assertThat(raw("/files/" + file, cashier).status()).isEqualTo(200);
        assertThat(raw("/files/" + file, staff(s.owner(), "WAITER")).status()).isEqualTo(404);

        clock.freeze(at("17:02"));
        Res o = out(cashier);
        assertThat(o.at("/clockedIn").asBoolean()).isFalse();
        assertThat(o.at("/event/flags").toString()).doesNotContain("LEFT_EARLY");
        assertThat(clockAt(cashier, "OUT", 5, 5, 12, Map.of("selfie", jpeg())).status()).isEqualTo(409);
    }

    @Test
    void outsideTheRadiusAndPoorAccuracyAreRefused() {
        Shop s = shop();
        clock.freeze(at("08:05"));
        String cashier = staff(s.owner(), "CASHIER");
        Res far = clockAt(cashier, "IN", 250, 0, 10, Map.of("selfie", jpeg()));
        assertThat(far.status()).isEqualTo(400);
        assertThat(far.at("/code").asText()).isEqualTo("OUTSIDE_RADIUS");
        Res vague = clockAt(cashier, "IN", 0, 0, 200, Map.of("selfie", jpeg()));
        assertThat(vague.status()).isEqualTo(400);
        assertThat(vague.at("/message").asText()).isEqualTo("Your phone can't find where you are well enough. Step near a door or window and try again.");
        assertThat(clockAt(cashier, "IN", 0, 0, 150, Map.of("selfie", jpeg())).status()).isEqualTo(200);
    }

    @Test
    void theShopCodeMustBeRightCurrentOrPreviousAndForThisBranch() {
        Shop s = shop();
        clock.freeze(at("08:00"));
        String code = liveCode(s, s.branch());
        String west = post("/branches", s.owner(), Map.of("name", "Westlands branch")).at("/id").asText();
        call("PUT", "/attendance/branches/" + west + "/location", s.owner(), Map.of("lat", LAT, "lng", LNG, "accuracy", 10));
        String westCode = liveCode(s, west);

        String a = staff(s.owner(), "CASHIER"), b = staff(s.owner(), "CASHIER"), c = staff(s.owner(), "CASHIER"), d = staff(s.owner(), "CASHIER");
        assertThat(code).isNotEqualTo(westCode);
        assertThat(clockAt(a, "IN", 1, 1, 10, Map.of("code", "00000000")).at("/code").asText()).isEqualTo("BAD_CODE");
        assertThat(clockAt(a, "IN", 1, 1, 10, Map.of("code", westCode)).at("/code").asText()).isEqualTo("BAD_CODE");
        assertThat(clockAt(a, "IN", 1, 1, 10, Map.of("code", "lza:" + code)).at("/event/method").asText()).isEqualTo("CODE");

        // Thirty seconds later the same code is the previous window: still fine.
        clock.freeze(at("08:00").plusSeconds(30));
        assertThat(clockAt(b, "IN", 1, 1, 10, Map.of("code", code)).status()).isEqualTo(200);
        // A minute later it has run out.
        clock.freeze(at("08:00").plusSeconds(61));
        Res late = clockAt(c, "IN", 1, 1, 10, Map.of("code", code));
        assertThat(late.status()).isEqualTo(400);
        assertThat(late.at("/message").asText()).contains("run out");
        // The new code works.
        assertThat(clockAt(d, "IN", 1, 1, 10, Map.of("code", liveCode(s, s.branch()))).status()).isEqualTo(200);

        // Making a new key stops every old code.
        post("/attendance/branches/" + s.branch() + "/rotate-code", s.owner(), Map.of());
        assertThat(clockAt(c, "IN", 1, 1, 10, Map.of("code", code)).status()).isEqualTo(400);
    }

    @Test
    void theHowIsDecidedPerBranchAndASelfieIsRequiredWhenThatIsTheMethod() {
        Shop s = shop();
        clock.freeze(at("08:00"));
        String cashier = staff(s.owner(), "CASHIER");
        assertThat(call("PUT", "/attendance/branches/" + s.branch() + "/rules", s.owner(), Map.of("method", "SELFIE")).status()).isEqualTo(200);
        Res none = clockAt(cashier, "IN", 1, 1, 10, Map.of());
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.at("/code").asText()).isEqualTo("SELFIE_NEEDED");
        assertThat(clockAt(cashier, "IN", 1, 1, 10, Map.of("code", liveCode(s, s.branch()))).at("/code").asText()).isEqualTo("SELFIE_NEEDED");
        // Not a photo at all.
        Res junk = clockAt(cashier, "IN", 1, 1, 10, Map.of("selfie", Base64.getEncoder().encodeToString("not a photo".getBytes())));
        assertThat(junk.status()).isEqualTo(400);
        assertThat(clockAt(cashier, "IN", 1, 1, 10, Map.of("selfie", jpeg())).status()).isEqualTo(200);

        call("PUT", "/attendance/branches/" + s.branch() + "/rules", s.owner(), Map.of("method", "CODE"));
        String other = staff(s.owner(), "CASHIER");
        assertThat(clockAt(other, "IN", 1, 1, 10, Map.of("selfie", jpeg())).at("/code").asText()).isEqualTo("CODE_NEEDED");
    }

    @Test
    void aPrintedCodeNeedsTheOwnerToAllowItAndStillNeedsTheLocation() {
        Shop s = shop();
        clock.freeze(at("08:00"));
        String token = get("/attendance/branches/" + s.branch() + "/printed", s.owner()).at("/qr").asText();
        assertThat(token).startsWith("lzp:");
        String cashier = staff(s.owner(), "CASHIER");
        assertThat(clockAt(cashier, "IN", 1, 1, 10, Map.of("code", token)).status()).isEqualTo(400);
        call("PUT", "/attendance/branches/" + s.branch() + "/rules", s.owner(), Map.of("allowPrinted", true));
        assertThat(clockAt(cashier, "IN", 900, 0, 10, Map.of("code", token)).at("/code").asText()).isEqualTo("OUTSIDE_RADIUS");
        assertThat(clockAt(cashier, "IN", 1, 1, 10, Map.of("code", token)).at("/event/method").asText()).isEqualTo("PRINTED");
        // Making a new printed code retires the old one.
        post("/attendance/branches/" + s.branch() + "/rotate-printed", s.owner(), Map.of());
        String two = staff(s.owner(), "CASHIER");
        assertThat(clockAt(two, "IN", 1, 1, 10, Map.of("code", token)).status()).isEqualTo(400);
    }

    @Test
    void lateAndEarlyLeaveAreFlaggedAgainstHoursAndGrace() {
        Shop s = shop();
        String onTime = staff(s.owner(), "CASHIER"), late = staff(s.owner(), "WAITER");
        clock.freeze(at("08:10"));
        assertThat(in(onTime).at("/event/flags").toString()).doesNotContain("LATE"); // exactly start plus grace
        clock.freeze(at("08:25"));
        Res l = in(late);
        assertThat(l.at("/event/flags").toString()).contains("LATE");

        JsonNode board = get("/attendance/board", s.owner()).body();
        assertThat(board.at("/counts/in").asInt()).isEqualTo(2);
        assertThat(board.at("/counts/late").asInt()).isEqualTo(1);
        assertThat(person(s, idOf(late)).get("lateMin").asInt()).isEqualTo(25);

        clock.freeze(at("16:30"));
        Res e = out(late);
        assertThat(e.status()).isEqualTo(200);
        // Early leave is judged once the day has closed.
        clock.freeze(at("17:05"));
        JsonNode sheet = get("/attendance/timesheet?userId=" + idOf(late) + "&from=2026-11-23&to=2026-11-23", s.owner()).body();
        assertThat(sheet.at("/totals/daysLate").asInt()).isEqualTo(1);
        assertThat(sheet.at("/totals/earlyLeaves").asInt()).isEqualTo(1);
        assertThat(sheet.at("/days/0/lateMin").asInt()).isEqualTo(25);
        assertThat(sheet.at("/days/0/earlyMin").asInt()).isEqualTo(30);
        assertThat(sheet.at("/totals/minutes").asInt()).isEqualTo(485);
        assertThat(sheet.at("/days/0/events/0/flags").toString()).contains("LATE");

        // A person's own hours replace the branch's.
        String own = staff(s.owner(), "CASHIER");
        call("PUT", "/attendance/staff/" + idOf(own) + "/hours", s.owner(), Map.of("days", List.of(Map.of("weekday", 1, "start", "12:00", "end", "20:00", "graceMin", 0))));
        clock.freeze(at("12:01"));
        assertThat(in(own).at("/event/flags").toString()).contains("LATE");
        assertThat(person(s, idOf(own)).at("/hours/start").asText()).isEqualTo("12:00");
    }

    @Test
    void aForgottenClockOutIsClosedAtTheEndTimeAndTheNextDayStartsClean() {
        Shop s = shop();
        String cashier = staff(s.owner(), "CASHIER");
        clock.freeze(at("08:00"));
        assertThat(in(cashier).status()).isEqualTo(200);
        clock.freeze(at("17:10"));
        jobs.closeAll(); // a little time to finish up first
        assertThat(person(s, idOf(cashier)).get("status").asText()).isEqualTo("IN");
        clock.freeze(at("17:40"));
        assertThat(jobs.closeAll()).isGreaterThanOrEqualTo(1);
        assertThat(person(s, idOf(cashier)).get("status").asText()).isEqualTo("OUT");

        JsonNode sheet = get("/attendance/timesheet?userId=" + idOf(cashier) + "&from=2026-11-23&to=2026-11-23", s.owner()).body();
        assertThat(sheet.at("/days/0/noClockOut").asBoolean()).isTrue();
        assertThat(sheet.at("/totals/minutes").asInt()).isEqualTo(9 * 60);
        assertThat(sheet.at("/totals/noClockOut").asInt()).isEqualTo(1);
        assertThat(person(s, idOf(cashier)).get("noClockOut").asBoolean()).isTrue();

        clock.freeze(at(1, "08:00"));
        assertThat(in(cashier).status()).isEqualTo(200);
    }

    @Test
    void aScheduledDayWithNoClockInShowsAsAbsent() {
        Shop s = shop();
        String present = staff(s.owner(), "CASHIER"), away = staff(s.owner(), "WAITER");
        clock.freeze(at("08:00"));
        in(present);
        out(present);
        clock.freeze(at("10:00"));
        assertThat(person(s, idOf(away)).get("status").asText()).isEqualTo("NOT_YET");
        clock.freeze(at(1, "18:00"));
        assertThat(person(s, idOf(away)).get("status").asText()).isEqualTo("ABSENT");

        JsonNode sheet = get("/attendance/timesheet?userId=" + idOf(away) + "&from=2026-11-23&to=2026-11-24", s.owner()).body();
        assertThat(sheet.at("/totals/absences").asInt()).isEqualTo(2);
        assertThat(sheet.at("/days/0/absent").asBoolean()).isTrue();
        JsonNode all = get("/attendance/timesheets?from=2026-11-23&to=2026-11-24", s.owner()).body();
        assertThat(all.toString()).contains("WAITER person");
    }

    @Test
    void staffClockOnlyThemselvesAndOnlyBossesSeeTheBoard() {
        Shop s = shop();
        clock.freeze(at("08:00"));
        String a = staff(s.owner(), "CASHIER"), b = staff(s.owner(), "WAITER");
        // Naming someone else changes nothing: the event is the caller's own.
        Res r = clockAt(a, "IN", 1, 1, 10, Map.of("selfie", jpeg(), "userId", idOf(b)));
        assertThat(r.status()).isEqualTo(200);
        assertThat(person(s, idOf(b)).get("status").asText()).isEqualTo("NOT_YET");
        assertThat(person(s, idOf(a)).get("status").asText()).isEqualTo("IN");

        assertThat(get("/attendance/board", a).status()).isEqualTo(403);
        assertThat(get("/attendance/timesheet?userId=" + idOf(b) + "&from=2026-11-23&to=2026-11-23", a).status()).isEqualTo(403);
        assertThat(call("PUT", "/attendance/branches/" + s.branch() + "/location", a, Map.of("lat", 1, "lng", 1, "accuracy", 5)).status()).isEqualTo(403);
        assertThat(get("/attendance/branches/" + s.branch() + "/code", a).status()).isEqualTo(403);
        assertThat(get("/attendance/mine", a).status()).isEqualTo(200);

        String accountant = staff(s.owner(), "ACCOUNTANT"), manager = staff(s.owner(), "MANAGER");
        assertThat(get("/attendance/board", accountant).status()).isEqualTo(200);
        assertThat(get("/attendance/timesheets?from=2026-11-23&to=2026-11-23", accountant).status()).isEqualTo(200);
        assertThat(get("/attendance/branches", accountant).status()).isEqualTo(403);
        assertThat(get("/attendance/board", manager).status()).isEqualTo(200);
        assertThat(get("/attendance/branches", manager).status()).isEqualTo(200);
    }

    @Test
    void oneBusinessNeverSeesAnothersAttendance() {
        Shop one = shop(), two = shop();
        clock.freeze(at("08:00"));
        String cashier = staff(one.owner(), "CASHIER");
        in(cashier);
        String file = person(one, idOf(cashier)).get("selfieFileId").asText();
        assertThat(get("/attendance/board", two.owner()).at("/people")).isEmpty();
        assertThat(get("/attendance/timesheet?userId=" + idOf(cashier) + "&from=2026-11-23&to=2026-11-23", two.owner()).status()).isEqualTo(404);
        assertThat(raw("/files/" + file, two.owner()).status()).isEqualTo(404);
        assertThat(get("/attendance/branches/" + one.branch() + "/code", two.owner()).status()).isEqualTo(404);
        assertThat(call("PUT", "/attendance/branches/" + one.branch() + "/location", two.owner(), Map.of("lat", 1, "lng", 1, "accuracy", 5)).status()).isEqualTo(404);
        // A code from the other business's branch is no good here.
        String mine = staff(two.owner(), "CASHIER");
        assertThat(clockAt(mine, "IN", 1, 1, 10, Map.of("code", liveCode(one, one.branch()))).status()).isEqualTo(400);
    }

    @Test
    void attendanceIsAGrowthToolSoLiteIsRefusedAfterTheTrial() {
        Shop s = shop();
        String cashier = staff(s.owner(), "CASHIER");
        assertThat(get("/attendance/me", cashier).status()).isEqualTo(200); // the free days open everything
        String tenant = s.session().at("/tenant/id").asText();
        jdbc.update("UPDATE tenants SET status = 'ACTIVE', plan = 'LITE', paid_until = now() + interval '30 days' WHERE id = ?", UUID.fromString(tenant));
        for (Res r : List.of(get("/attendance/me", cashier), post("/attendance/clock", cashier, Map.of("kind", "IN")), get("/attendance/board", s.owner()))) {
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.at("/code").asText()).isEqualTo("PLAN_FEATURE");
        }
        assertThat(post("/attendance/branches/" + s.branch() + "/screens", s.owner(), Map.of()).status()).isEqualTo(403); // not even a link can be made
    }

    @Test
    void thePlanKnowsTheToolAndGrowthHasIt() {
        JsonNode s = signup("restaurant");
        String tenant = s.at("/tenant/id").asText();
        jdbc.update("UPDATE tenants SET status = 'ACTIVE', plan = 'GROWTH', paid_until = now() + interval '30 days' WHERE id = ?", UUID.fromString(tenant));
        assertThat(get("/attendance/me", token(s)).status()).isEqualTo(200);
    }

    @Test
    void theShopScreenLinkShowsOnlyTheCodeAndCanBeRevoked() {
        Shop s = shop();
        Res made = post("/attendance/branches/" + s.branch() + "/screens", s.owner(), Map.of("label", "Till phone"));
        assertThat(made.status()).isEqualTo(200);
        String link = made.at("/token").asText();
        Res shown = get("/public/attendance/screen/" + link, null);
        assertThat(shown.status()).isEqualTo(200);
        assertThat(shown.at("/code").asText()).isEqualTo(liveCode(s, s.branch()));
        assertThat(shown.at("/qr").asText()).startsWith("lza:");
        assertThat(shown.body().has("lat")).isFalse();
        assertThat(get("/public/attendance/screen/" + link + "x", null).status()).isEqualTo(404);
        assertThat(get("/attendance/branches/" + s.branch() + "/screens", s.owner()).toString()).doesNotContain(link);

        String id = get("/attendance/branches/" + s.branch() + "/screens", s.owner()).at("/0/id").asText();
        assertThat(call("DELETE", "/attendance/screens/" + id, s.owner(), null).status()).isEqualTo(200);
        assertThat(get("/public/attendance/screen/" + link, null).status()).isEqualTo(404);
    }

    @Test
    void aFakeLookingLocationIsFlaggedNotBlockedAndLogged() {
        Shop s = shop();
        clock.freeze(at("08:00"));
        String zero = staff(s.owner(), "CASHIER"), jumper = staff(s.owner(), "WAITER");
        Res r = clockAt(zero, "IN", 1, 1, 0, Map.of("selfie", jpeg()));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/event/flags").toString()).contains("ZERO_ACCURACY");
        assertThat(get("/activity", s.owner()).body().toString()).contains("ATTENDANCE_FLAG");

        // Across the shop in a few seconds: faster than a person can go.
        call("PUT", "/attendance/branches/" + s.branch() + "/location", s.owner(), Map.of("lat", LAT, "lng", LNG, "accuracy", 10, "radiusM", 500));
        assertThat(clockAt(jumper, "IN", 400, 0, 10, Map.of("selfie", jpeg())).status()).isEqualTo(200);
        clock.freeze(at("08:00").plusSeconds(5));
        Res back = clockAt(jumper, "OUT", -400, 0, 10, Map.of("selfie", jpeg()));
        assertThat(back.status()).isEqualTo(200);
        assertThat(back.at("/event/flags").toString()).contains("LOCATION_JUMP");
    }

    @Test
    void clockAttemptsAreLimitedPerPerson() {
        Shop s = shop();
        clock.freeze(at("08:00"));
        String a = staff(s.owner(), "CASHIER"), b = staff(s.owner(), "WAITER");
        for (int i = 0; i < 10; i++) {
            assertThat(clockAt(a, "IN", 0, 0, 500, Map.of()).status()).isEqualTo(400);
        }
        Res stopped = clockAt(a, "IN", 0, 0, 10, Map.of("selfie", jpeg()));
        assertThat(stopped.status()).isEqualTo(429);
        assertThat(stopped.at("/message").asText()).contains("try again");
        assertThat(clockAt(b, "IN", 0, 0, 10, Map.of("selfie", jpeg())).status()).isEqualTo(200); // someone else is not held up
        clock.freeze(at("08:11"));
        assertThat(clockAt(a, "IN", 0, 0, 10, Map.of("selfie", jpeg())).status()).isEqualTo(200);
    }

    @Test
    void theTillCanWaitForAClockIn() {
        Shop s = shop();
        clock.freeze(at("08:00"));
        String cashier = staff(s.owner(), "CASHIER");
        assertThat(post("/shifts/open", cashier, Map.of("openingCash", 0)).status()).isEqualTo(200);
        call("PUT", "/attendance/branches/" + s.branch() + "/rules", s.owner(), Map.of("tillNeedsClock", true));
        String other = staff(s.owner(), "CASHIER");
        Res stopped = post("/shifts/open", other, Map.of("openingCash", 0));
        assertThat(stopped.status()).isEqualTo(409);
        assertThat(stopped.at("/message").asText()).contains("Clock in first");
        in(other);
        assertThat(post("/shifts/open", other, Map.of("openingCash", 0)).status()).isEqualTo(200);
    }

    @Test
    void oldSelfiesAreDeletedAfterSixtyDaysButTheRecordStays() {
        Shop s = shop();
        clock.freeze(at("08:00"));
        String cashier = staff(s.owner(), "CASHIER");
        in(cashier);
        String file = person(s, idOf(cashier)).get("selfieFileId").asText();
        clock.freeze(at(59, "09:00"));
        jobs.purgeAll();
        assertThat(raw("/files/" + file, s.owner()).status()).isEqualTo(200);
        clock.freeze(at(61, "09:00"));
        assertThat(jobs.purgeAll()).isGreaterThanOrEqualTo(1);
        assertThat(raw("/files/" + file, s.owner()).status()).isEqualTo(404);
        JsonNode sheet = get("/attendance/timesheet?userId=" + idOf(cashier) + "&from=2026-11-23&to=2026-11-23", s.owner()).body();
        assertThat(sheet.at("/days/0/events/0/kind").asText()).isEqualTo("IN");
        assertThat(sheet.at("/days/0/events/0/selfieFileId").isNull()).isTrue();
    }

    @Test
    void theNightSummaryCarriesAStaffLine() {
        Shop s = shop();
        String a = staff(s.owner(), "CASHIER"), b = staff(s.owner(), "WAITER"), c = staff(s.owner(), "KITCHEN");
        clock.freeze(at("08:00"));
        in(a);
        clock.freeze(at("08:30"));
        in(b);
        clock.freeze(at("20:00"));
        JsonNode night = get("/night/2026-11-23", s.owner()).body();
        assertThat(night.at("/attendance/text").asText()).isEqualTo("Staff: 2 in, 1 late (WAITER 30 min), 1 absent");
    }

    @Test
    void severalPairsAMakeBreaksAndOnlyTheLastClockOutIsJudgedForLeavingEarly() {
        Shop s = shop();
        String cashier = staff(s.owner(), "CASHIER"), leaver = staff(s.owner(), "WAITER");
        clock.freeze(at("08:00"));
        in(cashier);
        clock.freeze(at("12:00"));
        out(cashier);
        clock.freeze(at("12:30"));
        Res back = in(cashier);
        assertThat(back.at("/event/flags").toString()).doesNotContain("LATE"); // back from a break is never late
        clock.freeze(at("17:00"));
        out(cashier);
        clock.freeze(at("08:00"));
        in(leaver);
        clock.freeze(at("16:00"));
        out(leaver); // a break, not leaving: they come back
        clock.freeze(at("16:20"));
        in(leaver);
        clock.freeze(at("17:00"));
        out(leaver);
        clock.freeze(at("18:00"));
        JsonNode sheet = get("/attendance/timesheet?userId=" + idOf(cashier) + "&from=2026-11-23&to=2026-11-23", s.owner()).body();
        assertThat(sheet.at("/totals/minutes").asInt()).isEqualTo(8 * 60 + 30);
        assertThat(sheet.at("/days/0/breakMinutes").asInt()).isEqualTo(30);
        assertThat(sheet.at("/days/0/breaks/0/minutes").asInt()).isEqualTo(30);
        assertThat(sheet.at("/days/0/earlyMin").isNull()).isTrue();
        JsonNode l = get("/attendance/timesheet?userId=" + idOf(leaver) + "&from=2026-11-23&to=2026-11-23", s.owner()).body();
        assertThat(l.at("/totals/earlyLeaves").asInt()).isZero();
    }

    @Test
    void aNightShiftCrossesMidnightForLatenessEarlyLeaveForgottenClockOutAndAbsence() {
        Shop s = shop();
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            days.add(Map.of("weekday", d, "start", "18:00", "end", "02:00", "graceMin", 10));
        }
        assertThat(call("PUT", "/attendance/branches/" + s.branch() + "/hours", s.owner(), Map.of("days", days)).status()).isEqualTo(200);
        String late = staff(s.owner(), "CASHIER"), early = staff(s.owner(), "WAITER"), forgot = staff(s.owner(), "KITCHEN"), away = staff(s.owner(), "SALES");

        clock.freeze(at("18:25"));
        in(late);
        clock.freeze(at("18:05"));
        in(early);
        in(forgot);
        // After midnight the people still in are still shown, under the day the shift started.
        clock.freeze(at(1, "00:30"));
        JsonNode board = get("/attendance/board", s.owner()).body();
        assertThat(person(s, idOf(late)).get("status").asText()).isEqualTo("IN");
        assertThat(board.at("/counts/in").asInt()).isEqualTo(3);
        clock.freeze(at(1, "01:15"));
        assertThat(out(early).status()).isEqualTo(200);
        clock.freeze(at(1, "02:10"));
        assertThat(out(late).status()).isEqualTo(200);
        jobs.closeAll();
        assertThat(person(s, idOf(forgot)).get("status").asText()).isEqualTo("IN"); // 30 minutes of grace first
        clock.freeze(at(1, "02:40"));
        jobs.closeAll();

        String q = "&from=2026-11-23&to=2026-11-23";
        JsonNode l = get("/attendance/timesheet?userId=" + idOf(late) + q, s.owner()).body();
        assertThat(l.at("/days/0/lateMin").asInt()).isEqualTo(25);
        assertThat(l.at("/days/0/earlyMin").isNull()).isTrue();
        assertThat(l.at("/totals/minutes").asInt()).isEqualTo(465);
        JsonNode e = get("/attendance/timesheet?userId=" + idOf(early) + q, s.owner()).body();
        assertThat(e.at("/days/0/earlyMin").asInt()).isEqualTo(45);
        JsonNode f = get("/attendance/timesheet?userId=" + idOf(forgot) + q, s.owner()).body();
        assertThat(f.at("/days/0/noClockOut").asBoolean()).isTrue();
        assertThat(f.at("/totals/minutes").asInt()).isEqualTo(8 * 60 - 5); // closed at 02:00
        JsonNode a = get("/attendance/timesheet?userId=" + idOf(away) + q, s.owner()).body();
        assertThat(a.at("/days/0/absent").asBoolean()).isTrue();
        // Not absent while the shift is still on.
        clock.freeze(at("23:00"));
        assertThat(person(s, idOf(away)).get("status").asText()).isEqualTo("NOT_YET");
    }
}
