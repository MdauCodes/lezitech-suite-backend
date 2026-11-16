package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A new shop adds a product and says how many it has: that's its opening stock, with a history line. */
class OpeningStockTest extends IntegrationTest {

    @Test
    void newProductStartsWithTheCountGiven() {
        JsonNode s = signup("retail");
        String owner = token(s);
        Res made = post("/items", owner, Map.of("name", "Sugar 2kg", "kind", "PRODUCT", "price", 380, "cost", 330,
                "trackStock", true, "unit", "pc", "openingStock", 24));
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        String id = made.at("/id").asText();
        JsonNode items = get("/items", owner).body();
        JsonNode sugar = null;
        for (JsonNode i : items) if (i.at("/id").asText().equals(id)) sugar = i;
        assertThat(sugar).isNotNull();
        assertThat(sugar.at("/onHand").decimalValue()).isEqualByComparingTo("24");

        // Services and untracked items ignore it.
        Res svc = post("/items", owner, Map.of("name", "Delivery", "kind", "SERVICE", "price", 100, "trackStock", false, "openingStock", 5));
        assertThat(svc.status()).isEqualTo(200);
    }
}
