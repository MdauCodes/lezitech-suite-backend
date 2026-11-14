package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Malls & property, package M3: water and electricity meters on the monthly bill, and service charge three ways. */
class MetersAndServiceChargeTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Nairobi"));

    private JsonNode unit(String owner, String name) {
        for (JsonNode u : get("/property/units", owner).body()) {
            if (name.equals(u.get("name").asText())) {
                return u;
            }
        }
        return null;
    }

    private String lease(String owner, String unitName, YearMonth from) {
        Map<String, Object> body = new HashMap<>(Map.of("spaceId", unit(owner, unitName).get("id").asText(), "tenantName", "Wafula Traders",
                "rent", 10000, "serviceCharge", 3000, "dueDay", 5, "startsOn", from.atDay(1).toString()));
        Res r = post("/property/leases", owner, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private BigDecimal balance(String owner, String lease) {
        return get("/property/leases/" + lease + "/statement", owner).at("/balance").decimalValue();
    }

    @Test
    void meterUsageGoesOnTheNextBillForTheKindsTheLandlordCharges() {
        String owner = token(signup("property"));
        YearMonth now = YearMonth.from(TODAY);
        assertThat(post("/property/settings", owner, Map.of("serviceMode", "INCLUDED", "utilities", Map.of(
                "WATER", Map.of("charge", true, "price", 150, "fixedFee", 200, "unit", "m³"),
                "POWER", Map.of("charge", false, "price", 28, "fixedFee", 0, "unit", "kWh")))).status()).isEqualTo(200);
        String lease = lease(owner, "G03", now.minusMonths(1));
        String space = unit(owner, "G03").get("id").asText();

        // The caretaker reads the meters: the first reading only sets the start.
        String tech = staff(owner, "TECHNICIAN");
        Res first = post("/meters/readings", tech, Map.of("spaceId", space, "kind", "WATER", "reading", 1200));
        assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
        assertThat(first.at("/used").decimalValue()).isEqualByComparingTo("0");
        assertThat(post("/meters/readings", tech, Map.of("spaceId", space, "kind", "WATER", "reading", 1190)).status()).isEqualTo(400);
        assertThat(post("/meters/readings", tech, Map.of("spaceId", space, "kind", "WATER", "reading", 1214)).at("/used").decimalValue()).isEqualByComparingTo("14");
        post("/meters/readings", tech, Map.of("spaceId", space, "kind", "POWER", "reading", 500));
        post("/meters/readings", tech, Map.of("spaceId", space, "kind", "POWER", "reading", 600));
        assertThat(get("/property/units", tech).status()).isEqualTo(403);

        // This month's bill: rent (service charge included in it) + 14 m³ × 150 + the water meter fee; electricity isn't charged.
        post("/property/invoices/generate", owner, Map.of("period", now.toString()));
        assertThat(balance(owner, lease)).isEqualByComparingTo("12300");
        String lines = get("/property/leases/" + lease + "/statement", owner).at("/lines").toString();
        assertThat(lines).contains("Water · 1200 → 1214").doesNotContain("Electricity").doesNotContain("Service charge");

        // Billed readings aren't billed again, and can't be removed.
        post("/property/invoices/generate", owner, Map.of("period", now.plusMonths(1).toString()));
        assertThat(balance(owner, lease)).isEqualByComparingTo("22500");
        JsonNode history = get("/meters/units/" + space, owner).body();
        String billedWater = null;
        for (JsonNode r : history) {
            if ("WATER".equals(r.get("kind").asText()) && r.get("used").decimalValue().signum() > 0) {
                billedWater = r.get("id").asText();
            }
        }
        assertThat(post("/meters/readings/" + billedWater + "/delete", owner, Map.of()).status()).isEqualTo(409);

        // Water included for this tenant from now on: only rent next time.
        assertThat(post("/property/leases/" + lease + "/utilities", owner, Map.of("chargeWater", false)).status()).isEqualTo(200);
        post("/meters/readings", tech, Map.of("spaceId", space, "kind", "WATER", "reading", 1230));
        post("/property/invoices/generate", owner, Map.of("period", now.plusMonths(2).toString()));
        assertThat(balance(owner, lease)).isEqualByComparingTo("32500");
    }

    @Test
    void serviceChargeIsFixedSharedFromTheBudgetOrIncludedAndTheYearCarriesOver() {
        String owner = token(signup("property"));
        YearMonth now = YearMonth.from(TODAY);
        int year = now.getYear();
        String fixed = lease(owner, "G03", now);
        post("/property/invoices/generate", owner, Map.of("period", now.toString()));
        assertThat(balance(owner, fixed)).isEqualByComparingTo("13000");

        // Budget: the year's costs shared by floor size.
        post("/property/settings", owner, Map.of("serviceMode", "BUDGET"));
        JsonNode sum = post("/property/service-charge/" + year + "/lines", owner, Map.of("lines", List.of(
                Map.of("name", "Security", "amount", 1_200_000), Map.of("name", "Cleaning", "amount", 402_000)))).body();
        assertThat(sum.get("total").decimalValue()).isEqualByComparingTo("1602000");
        BigDecimal share = null;
        for (JsonNode u : sum.get("shares")) {
            if ("G04".equals(u.get("name").asText())) {
                share = u.get("monthly").decimalValue();
            }
        }
        assertThat(share).isPositive();
        String budget = lease(owner, "G04", now);
        post("/property/invoices/generate", owner, Map.of("period", now.toString()));
        assertThat(balance(owner, budget)).isEqualByComparingTo(new BigDecimal("10000").add(share));

        // Included in the rent: no service-charge line.
        post("/property/settings", owner, Map.of("serviceMode", "INCLUDED"));
        String included = lease(owner, "G05", now);
        post("/property/invoices/generate", owner, Map.of("period", now.toString()));
        assertThat(balance(owner, included)).isEqualByComparingTo("10000");

        // Spending through the year; closing carries spent minus billed into next year's budget.
        assertThat(post("/property/service-charge/" + year + "/expenses", owner, Map.of("what", "Guards, November", "amount", 100_000)).status()).isEqualTo(200);
        JsonNode closed = post("/property/service-charge/" + year + "/close", owner, Map.of()).body();
        BigDecimal carried = closed.get("carried").decimalValue();
        assertThat(carried).isEqualByComparingTo(closed.get("spent").decimalValue().subtract(closed.get("billed").decimalValue()));
        assertThat(closed.at("/nextYear/carried").decimalValue()).isEqualByComparingTo(carried);
        assertThat(post("/property/service-charge/" + year + "/lines", owner, Map.of("lines", List.of())).status()).isEqualTo(409);
        assertThat(post("/property/service-charge/" + year + "/close", owner, Map.of()).status()).isEqualTo(409);
    }
}
