package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Buy by the carton, sell by the piece (or the whole carton): stock is always in pieces. */
class PacksTest extends IntegrationTest {

    private JsonNode itemOf(String token, String id) {
        for (JsonNode i : get("/items", token).body()) if (i.at("/id").asText().equals(id)) return i;
        throw new IllegalStateException("not found");
    }

    @Test
    void cartonsAndPieces() {
        String owner = token(signup("wholesale"));
        String name = "Zz Soda 500ml " + UUID.randomUUID().toString().substring(0, 4);
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "kind", "PRODUCT", "price", 130, "cost", 100, "trackStock", true, "unit", "pc"));
        body.putAll(Map.of("openingStock", 48, "packUnit", "Carton", "packSize", 24, "packBuyPrice", 2400, "packPrice", 2800));
        Res made = post("/items", owner, body);
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        String soda = made.at("/id").asText();
        JsonNode it = itemOf(owner, soda);
        assertThat(it.at("/buyUnit").asText()).isEqualTo("carton");
        assertThat(it.at("/packPrice").asDouble()).isEqualTo(2800.0);

        // One carton and three loose bottles: 27 bottles off stock.
        Res order = post("/orders", owner, Map.of("lines", List.of(
                Map.of("itemId", soda, "qty", 1, "asPack", true), Map.of("itemId", soda, "qty", 3))));
        assertThat(order.status()).as(order.body().toString()).isEqualTo(200);
        String bill = order.at("/id").asText();
        assertThat(order.at("/total").asDouble()).isEqualTo(2800 + 3 * 130.0);
        assertThat(order.at("/lines").toString()).contains("(carton of 24)");
        assertThat(post("/orders/" + bill + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", 3190)))).at("/status").asText()).isEqualTo("PAID");
        assertThat(itemOf(owner, soda).at("/onHand").asDouble()).isEqualTo(21.0);

        // Refunded: all 27 come back.
        Res refund = post("/orders/" + bill + "/refund", owner, Map.of("reason", "Wrong order", "method", "CASH"));
        assertThat(refund.status()).as(refund.body().toString()).isEqualTo(200);
        assertThat(itemOf(owner, soda).at("/onHand").asDouble()).isEqualTo(48.0);

        // A product with no pack can't be sold as one.
        String loose = post("/items", owner, Map.of("name", "Zz Loose " + UUID.randomUUID().toString().substring(0, 4), "kind", "PRODUCT", "price", 10, "trackStock", false)).at("/id").asText();
        assertThat(post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", loose, "qty", 1, "asPack", true)))).status()).isEqualTo(400);

        // Ordered from a supplier in cartons: 2 cartons at 2,300 = 48 more bottles, a bottle now costs 95.83.
        Res po = post("/purchases", owner, Map.of("lines", List.of(Map.of("itemId", soda, "qty", 2, "unitCost", 2300, "inPacks", true))));
        assertThat(po.status()).as(po.body().toString()).isEqualTo(200);
        assertThat(post("/purchases/" + po.at("/id").asText() + "/receive", owner, Map.of()).status()).isEqualTo(200);
        JsonNode after = itemOf(owner, soda);
        assertThat(after.at("/onHand").asDouble()).isEqualTo(96.0);
        assertThat(after.at("/cost").asDouble()).isEqualTo(95.83);
        assertThat(after.at("/buyPrice").asDouble()).isEqualTo(2300.0);

        // Excel: the pack columns.
        String juice = "Zz Juice 1L " + UUID.randomUUID().toString().substring(0, 4);
        assertThat(post("/items/import", owner, Map.of("rows", List.of(Map.of("name", juice, "price", 250, "pack", "Crate", "packSize", 12, "packBuyPrice", 2160, "packPrice", 2800)))).status()).isEqualTo(200);
        JsonNode j = null;
        for (JsonNode i : get("/items", owner).body()) if (i.at("/name").asText().equals(juice)) j = i;
        assertThat(j).isNotNull();
        assertThat(j.at("/buySize").asDouble()).isEqualTo(12.0);
        assertThat(j.at("/cost").asDouble()).isEqualTo(180.0);
    }
}
