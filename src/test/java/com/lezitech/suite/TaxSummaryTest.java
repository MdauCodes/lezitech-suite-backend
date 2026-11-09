package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The tax summary: VAT, Turnover Tax and the tourism levy from the business's own sales, per day and month. */
class TaxSummaryTest extends IntegrationTest {

    private BigDecimal at(JsonNode n, String pointer) {
        return new BigDecimal(n.at(pointer).asText("0"));
    }

    private void setTax(String owner, Map<String, Object> tax) {
        assertThat(call("PUT", "/settings", owner, Map.of("settings", Map.of("tax", tax))).status()).isEqualTo(200);
    }

    @Test
    void vatTurnoverTaxAndLevyAreWorkedOutFromSales() {
        String owner = token(signup("restaurant"));
        JsonNode before = get("/reports/tax", owner).body();
        String item = get("/items", owner).body().get(0).get("id").asText();
        JsonNode sale = post("/orders", owner, Map.of("lines", line(item, 2), "payments",
                List.of(Map.of("method", "CASH", "amount", 100000)))).body();
        BigDecimal total = new BigDecimal(sale.get("total").asText());
        BigDecimal vat = new BigDecimal(sale.get("tax").asText());

        // Nothing chosen yet: both VAT and Turnover Tax filing dates are shown.
        JsonNode open = get("/reports/tax", owner).body();
        assertThat(at(open, "/totals/grossSales").subtract(at(before, "/totals/grossSales"))).isEqualByComparingTo(total);
        assertThat(at(open, "/totals/outputVat").subtract(at(before, "/totals/outputVat"))).isEqualByComparingTo(vat);
        assertThat(open.at("/settings/regime").asText()).isEqualTo("NOT_SET");
        assertThat(open.at("/due").toString()).contains("\"VAT\"").contains("\"TOT\"");
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Nairobi"));
        assertThat(open.at("/due/0/date").asText()).isEqualTo(today.withDayOfMonth(1).plusMonths(1).withDayOfMonth(20).toString());
        // Today's line carries today's sales.
        JsonNode lastDay = open.at("/lines").get(open.at("/lines").size() - 1);
        assertThat(lastDay.get("period").asText()).isEqualTo(today.toString());

        setTax(owner, Map.of("regime", "TOT"));
        JsonNode tot = get("/reports/tax", owner).body();
        BigDecimal gross = at(tot, "/totals/grossSales");
        assertThat(at(tot, "/totals/owed")).isEqualByComparingTo(gross.multiply(new BigDecimal("0.015")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(tot.at("/due").size()).isEqualTo(1);

        setTax(owner, Map.of("regime", "VAT", "tourismLevy", true));
        JsonNode v = get("/reports/tax", owner).body();
        BigDecimal levy = gross.subtract(at(v, "/totals/outputVat")).multiply(new BigDecimal("0.02")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(at(v, "/totals/tourismLevy")).isEqualByComparingTo(levy);
        assertThat(at(v, "/totals/owed")).isEqualByComparingTo(at(v, "/totals/vatPayable").add(levy));
        assertThat(v.at("/due").toString()).contains("TOURISM_LEVY");

        // The owner can change a rate when the law changes.
        setTax(owner, Map.of("regime", "TOT", "totRate", 3));
        assertThat(at(get("/reports/tax", owner).body(), "/totals/tot")).isEqualByComparingTo(gross.multiply(new BigDecimal("0.03")).setScale(2, java.math.RoundingMode.HALF_UP));

        // A whole year comes back month by month.
        JsonNode year = get("/reports/tax?year=" + today.getYear(), owner).body();
        assertThat(year.at("/unit").asText()).isEqualTo("month");
        assertThat(year.at("/lines").size()).isEqualTo(today.getMonthValue());
    }

    @Test
    void onlyManagersAndAccountantsSeeItAndEachBusinessSeesItsOwn() {
        String owner = token(signup("retail"));
        assertThat(get("/reports/tax", staff(owner, "CASHIER")).status()).isEqualTo(403);
        assertThat(get("/reports/tax", staff(owner, "ACCOUNTANT")).status()).isEqualTo(200);
        String sugar = item(owner, "Sugar").get("id").asText();
        post("/orders", owner, Map.of("lines", line(sugar, 1), "payments", List.of(Map.of("method", "CASH", "amount", 190))));
        String other = token(signup("retail"));
        assertThat(at(get("/reports/tax", other).body(), "/totals/grossSales"))
                .isLessThan(at(get("/reports/tax", owner).body(), "/totals/grossSales"));
    }
}
