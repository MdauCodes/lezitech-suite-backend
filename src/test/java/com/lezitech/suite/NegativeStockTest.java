package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Stock can't go below zero unless the business allows it; batches always match the count. */
class NegativeStockTest extends IntegrationTest {

    @Test
    void pharmacyCannotSellMoreThanItHasAndNoMoneyIsKept() {
        String owner = token(signup("pharmacy"));
        JsonNode azi = item(owner, "Azithromycin");
        int have = azi.get("onHand").asInt();
        Res r = post("/orders", owner, Map.of("lines", line(azi.get("id").asText(), have + 1),
                "payments", List.of(Map.of("method", "CASH", "amount", 100000))));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.at("/code").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(r.at("/message").asText()).contains("only " + have + " left");
        // The whole sale was rolled back: no order, no payment, stock unchanged.
        assertThat(get("/orders", owner).body()).isEmpty();
        assertThat(item(owner, "Azithromycin").get("onHand").asInt()).isEqualTo(have);
    }

    @Test
    void restaurantMaySellBelowZeroAndOwnerCanSwitchIt() {
        String owner = token(signup("restaurant"));
        JsonNode soda = item(owner, "Soda 500ml");
        int have = soda.get("onHand").asInt();
        Res r = post("/orders", owner, Map.of("lines", line(soda.get("id").asText(), have + 2),
                "payments", List.of(Map.of("method", "CASH", "amount", 100000))));
        assertThat(r.status()).isEqualTo(200);
        assertThat(item(owner, "Soda 500ml").get("onHand").asInt()).isEqualTo(-2);

        call("PUT", "/settings", owner, Map.of("settings", Map.of("features", Map.of("allowNegativeStock", false))));
        assertThat(post("/orders", owner, Map.of("lines", line(soda.get("id").asText(), 1),
                "payments", List.of(Map.of("method", "CASH", "amount", 500)))).status()).isEqualTo(409);
    }

    @Test
    void batchesStayEqualToTheCount() {
        String owner = token(signup("pharmacy"));
        JsonNode para = item(owner, "Paracetamol");
        String id = para.get("id").asText();
        // Sell some (first-expiry-first-out), refund one sale, recount up, recount down.
        String sale = post("/orders", owner, Map.of("lines", line(id, 30), "payments", List.of(Map.of("method", "CASH", "amount", 2000)))).at("/id").asText();
        post("/orders", owner, Map.of("lines", line(id, 20), "payments", List.of(Map.of("method", "CASH", "amount", 2000))));
        post("/orders/" + sale + "/refund", owner, Map.of("reason", "Returned"));
        post("/stock/adjust", owner, Map.of("itemId", id, "counted", 260));
        post("/stock/adjust", owner, Map.of("itemId", id, "counted", 90));

        BigDecimal level = item(owner, "Paracetamol").get("onHand").decimalValue();
        assertThat(level).isEqualByComparingTo("90");
        BigDecimal batches = BigDecimal.ZERO;
        for (JsonNode b : get("/stock/expiring?days=100000", owner).body()) {
            if (b.get("itemName").asText().contains("Paracetamol")) {
                batches = batches.add(b.get("qty").decimalValue());
            }
        }
        // NO-BATCH rows have no expiry date and don't show in the expiry list; check via the full batch view.
        JsonNode all = get("/stock/batches?itemId=" + id, owner).body();
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode b : all) {
            sum = sum.add(b.get("qty").decimalValue());
        }
        assertThat(sum).isEqualByComparingTo(level);
        assertThat(batches).isLessThanOrEqualTo(level);
    }
}
