package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.property.RentJobs;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Malls & property, package M4: daily and weekly stalls, the collector's round, the office's cash count, casual tickets. */
class MarketTest extends IntegrationTest {

    @Autowired
    RentJobs jobs;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Nairobi"));

    private String lease(String owner, String unitName, String frequency, int rent, LocalDate from) {
        String unit = null;
        for (JsonNode u : get("/property/units", owner).body()) {
            if (unitName.equals(u.get("name").asText())) {
                unit = u.get("id").asText();
            }
        }
        Map<String, Object> body = new HashMap<>(Map.of("spaceId", unit, "tenantName", "Mama " + unitName, "phone", "0712345678",
                "rent", rent, "serviceCharge", 0, "startsOn", from.toString()));
        if (frequency != null) {
            body.put("frequency", frequency);
        }
        Res r = post("/property/leases", owner, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private JsonNode stall(JsonNode round, String lease) {
        for (JsonNode s : round.get("stalls")) {
            if (lease.equals(s.get("leaseId").asText())) {
                return s;
            }
        }
        return null;
    }

    @Test
    void dailyStallsAreChargedEachOpenDayAndTheCollectorTakesCash() {
        JsonNode s = signup("property");
        String owner = token(s);
        UUID tenant = UUID.fromString(s.at("/tenant/id").asText());
        UUID ownerId = UUID.fromString(s.at("/user/id").asText());
        String daily = lease(owner, "S03", "DAILY", 50, TODAY.minusDays(3));
        jobs.forTenant(tenant, ownerId, TODAY.minusDays(2));
        jobs.forTenant(tenant, ownerId, TODAY.minusDays(1));
        String collector = staff(owner, "COLLECTOR");
        String collectorId = userId(owner, "COLLECTOR");

        // The round makes today's charge; three open days are owed.
        JsonNode round = get("/market/round", collector).body();
        assertThat(round.get("open").asBoolean()).isTrue();
        assertThat(stall(round, daily).get("owed").decimalValue()).isEqualByComparingTo("150");
        assertThat(stall(round, daily).get("chargeStatus").asText()).isEqualTo("OPEN");

        // 100 in cash pays the two older days; today is excused (trader sick) and isn't charged again.
        assertThat(post("/market/collect", collector, Map.of("leaseId", daily, "amount", 100)).status()).isEqualTo(200);
        assertThat(post("/market/excuse", collector, Map.of("leaseId", daily, "reason", "")).status()).isEqualTo(400);
        assertThat(post("/market/excuse", collector, Map.of("leaseId", daily, "reason", "Sick")).status()).isEqualTo(200);
        round = get("/market/round", collector).body();
        assertThat(stall(round, daily).get("owed").decimalValue()).isEqualByComparingTo("0");
        assertThat(stall(round, daily).get("chargeStatus").asText()).isEqualTo("VOID");
        assertThat(round.at("/mine/cash").decimalValue()).isEqualByComparingTo("100");
        // A paid day can't be excused.
        assertThat(post("/market/excuse", collector, Map.of("leaseId", daily, "reason", "Sick", "day", TODAY.minusDays(1).toString())).status()).isEqualTo(409);

        // The office counts the collector's cash: 10 short is shown and logged; counted once only; collectors can't count.
        assertThat(get("/market/days", collector).status()).isEqualTo(403);
        JsonNode days = get("/market/days", owner).body();
        assertThat(days.get(0).get("recorded").decimalValue()).isEqualByComparingTo("100");
        JsonNode counted = post("/market/days/" + collectorId + "/count", owner, Map.of("counted", 90)).body();
        assertThat(counted.at("/count/difference").decimalValue()).isEqualByComparingTo("-10");
        assertThat(post("/market/days/" + collectorId + "/count", owner, Map.of("counted", 100)).status()).isEqualTo(409);
        assertThat(get("/activity", owner).body().get("entries").toString()).contains("10.00 short");
        // Collectors can't open the landlord's screens.
        assertThat(get("/property/units", collector).status()).isEqualTo(403);
    }

    @Test
    void weeklyAndMonthlyStallsAndClosedDays() {
        String owner = token(signup("property"));
        String weekly = lease(owner, "S04", "WEEKLY", 300, TODAY.minusDays(1));
        String monthly = lease(owner, "S06", null, 4000, YearMonth.from(TODAY).atDay(1));
        JsonNode round = get("/market/round", owner).body();
        get("/market/round", owner);
        assertThat(stall(round, weekly).get("owed").decimalValue()).isEqualByComparingTo("300");
        assertThat(get("/property/leases/" + weekly + "/statement", owner).at("/lines").toString()).contains("week of");
        // Monthly is the default and works as before; weekly stalls aren't in the monthly run.
        post("/property/invoices/generate", owner, Map.of("period", YearMonth.from(TODAY).toString()));
        assertThat(get("/property/leases/" + monthly + "/statement", owner).at("/balance").decimalValue()).isEqualByComparingTo("4000");
        assertThat(get("/property/leases/" + weekly + "/statement", owner).at("/balance").decimalValue()).isEqualByComparingTo("300");

        // A day the market is closed: no daily charges.
        List<Integer> open = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7));
        open.remove(Integer.valueOf(TODAY.getDayOfWeek().getValue()));
        assertThat(post("/property/settings", owner, Map.of("market", Map.of("openDays", open))).status()).isEqualTo(200);
        String daily = lease(owner, "S07", "DAILY", 50, TODAY);
        round = get("/market/round", owner).body();
        assertThat(round.get("open").asBoolean()).isFalse();
        assertThat(stall(round, daily).get("owed").decimalValue()).isEqualByComparingTo("0");
        assertThat(DayOfWeek.MONDAY.getValue()).isEqualTo(1);
    }

    @Test
    void casualTicketsOnlyWhenSwitchedOn() {
        String owner = token(signup("property"));
        String collector = staff(owner, "COLLECTOR");
        assertThat(post("/market/tickets", collector, Map.of("name", "Hawker Jane")).status()).isEqualTo(403);
        post("/property/settings", owner, Map.of("market", Map.of("openDays", List.of(1, 2, 3, 4, 5, 6, 7), "casualTickets", true, "ticketFee", 40)));
        Res t = post("/market/tickets", collector, Map.of("name", "Hawker Jane"));
        assertThat(t.status()).as(t.body().toString()).isEqualTo(200);
        assertThat(t.at("/ticket").asInt()).isPositive();
        assertThat(t.at("/amount").decimalValue()).isEqualByComparingTo("40");
        assertThat(get("/market/round", collector).at("/mine/tickets").asInt()).isEqualTo(1);
        assertThat(get("/market/round", collector).at("/mine/cash").decimalValue()).isEqualByComparingTo("40");
    }
}
