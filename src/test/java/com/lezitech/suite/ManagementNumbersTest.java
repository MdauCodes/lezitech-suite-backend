package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The owner's home: customers served this month and the busiest staff. */
class ManagementNumbersTest extends IntegrationTest {

    @Test
    void homeShowsCustomersServedAndBusiestStaff() {
        String owner = token(signup("restaurant"));
        String cashier = staff(owner, "CASHIER");
        String itemId = get("/items", owner).body().get(0).get("id").asText();
        String customerId = post("/customers", owner, Map.of("name", "Regular Wanjiku", "phone", "0711000123")).body().get("id").asText();

        post("/shifts/open", cashier, Map.of("openingCash", 0));
        Res sale = post("/orders", cashier, Map.of("lines", line(itemId, 1), "customerId", customerId,
                "payments", List.of(Map.of("method", "CASH", "amount", 100000))));
        assertThat(sale.status()).as(sale.body().toString()).isEqualTo(200);

        Res home = get("/reports/dashboard", owner);
        assertThat(home.status()).isEqualTo(200);
        assertThat(home.at("/kpis/customers_served_month").asLong()).isEqualTo(1);
        assertThat(home.at("/kpis/walk_in_sales_month").asLong()).isZero();
        JsonNode top = home.body().get("topStaff");
        assertThat(top.isArray()).isTrue();
        assertThat(top.get(0).get("name").asText()).isEqualTo("CASHIER person");
        assertThat(top.get(0).get("orders").asLong()).isEqualTo(1);
        // The single busiest-today entry the app already uses is still there.
        assertThat(home.at("/kpis").has("top_staff")).isTrue();
    }
}
