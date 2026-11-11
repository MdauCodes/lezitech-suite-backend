package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Till, back office and customer reports are separate, and each is only open to the jobs that need it. */
class ReportsSplitTest extends IntegrationTest {

    @Test
    void backOfficeShowsStockValueAndMoneyOwed() {
        String owner = token(signup("retail"));
        JsonNode r = get("/reports/back-office", owner).body();
        assertThat(r.at("/stock/costValue").decimalValue()).isPositive();
        assertThat(r.at("/stock/saleValue").decimalValue()).isGreaterThanOrEqualTo(r.at("/stock/costValue").decimalValue());
        assertThat(r.has("lowStock")).isTrue();
        assertThat(r.at("/owedToYou/amount").isNumber()).isTrue();
        assertThat(r.at("/youOwe/amount").isNumber()).isTrue();
    }

    @Test
    void customerReportCountsWhoBought() {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        String customer = post("/customers", owner, Map.of("name", "Report Test Customer", "phone", "0711000222")).at("/id").asText();
        for (int i = 0; i < 2; i++) {
            assertThat(post("/orders", owner, Map.of("lines", line(sugar, 1), "customerId", customer,
                    "payments", List.of(Map.of("method", "CASH", "amount", 500)))).status()).isEqualTo(200);
        }
        JsonNode r = get("/reports/customers", owner).body();
        assertThat(r.at("/summary/buyingCustomers").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(r.at("/summary/cameBack").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(r.at("/topCustomers/0/name").asText()).isEqualTo("Report Test Customer");
        assertThat(r.has("leads")).isTrue();
    }

    @Test
    void eachReportIsOpenOnlyToTheJobsThatNeedIt() {
        String owner = token(signup("wholesale"));
        String cashier = staff(owner, "CASHIER");
        String sales = staff(owner, "SALES");
        String accountant = staff(owner, "ACCOUNTANT");
        assertThat(get("/reports/back-office", cashier).status()).isEqualTo(403);
        assertThat(get("/reports/customers", cashier).status()).isEqualTo(403);
        assertThat(get("/reports/back-office", sales).status()).isEqualTo(403);
        assertThat(get("/reports/customers", sales).status()).isEqualTo(200);
        assertThat(get("/reports/back-office", accountant).status()).isEqualTo(200);
    }
}
