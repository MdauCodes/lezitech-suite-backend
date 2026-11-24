package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Sold by weight: kilos with decimals on the line, whole-shilling totals, stock in kilos, refunds put the kilos back. */
class ByWeightTest extends IntegrationTest {

    private String owner;
    private String cashier;

    private void setUp(String sector, String sub) {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Test " + sub + " " + email.substring(6, 10), "sector", sector, "subSector", sub,
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        owner = token(r.body());
        cashier = staff(owner, "CASHIER");
        post("/shifts/open", cashier, Map.of("openingCash", 0));
    }

    private BigDecimal onHand(String name) {
        return item(owner, name).get("onHand").decimalValue();
    }

    private Res sell(String token, String itemId, Object qty, Map<String, Object> extra) {
        Map<String, Object> l = new java.util.HashMap<>(extra);
        l.put("itemId", itemId);
        l.put("qty", qty);
        return post("/orders", token, Map.of("lines", List.of(l)));
    }

    @Test
    void butcheryMeatIsSoldInKilosAndPieceItemsAreUnchanged() {
        setUp("retail", "butchery");
        JsonNode steak = item(owner, "Steak");
        String id = steak.get("id").asText();
        assertThat(steak.get("soldByWeight").asBoolean()).isTrue();
        assertThat(steak.get("price").decimalValue()).isEqualByComparingTo("800");
        assertThat(item(owner, "Sausages").get("soldByWeight").asBoolean()).isFalse();
        BigDecimal start = onHand("Steak");

        // 0.75 kg at 800 per kg is 600, and 0.75 kg comes off the shelf. The line says kg.
        Res sale = sell(cashier, id, 0.75, Map.of());
        assertThat(sale.status()).as(sale.body().toString()).isEqualTo(200);
        assertThat(sale.at("/total").decimalValue()).isEqualByComparingTo("600");
        assertThat(sale.at("/lines/0/qty").decimalValue()).isEqualByComparingTo("0.75");
        assertThat(sale.at("/lines/0/unitName").asText()).isEqualTo("kg");
        String orderId = sale.at("/id").asText();
        assertThat(post("/orders/" + orderId + "/pay", cashier, Map.of("payments", List.of(Map.of("method", "CASH", "amount", 600)))).at("/status").asText()).isEqualTo("PAID");
        assertThat(onHand("Steak")).isEqualByComparingTo(start.subtract(new BigDecimal("0.75")));

        // Amount entry: KES 500 at 800 per kg is 0.625 kg, which sells for exactly 500.
        BigDecimal kg = new BigDecimal("500").divide(new BigDecimal("800"), 3, java.math.RoundingMode.HALF_UP);
        assertThat(kg).isEqualByComparingTo("0.625");
        assertThat(sell(cashier, id, kg, Map.of()).at("/total").decimalValue()).isEqualByComparingTo("500");

        // A cashier cannot set the price of a by-weight item: it stays the item's.
        Res cheap = sell(cashier, id, 1, Map.of("unitPrice", 100));
        assertThat(cheap.status()).isEqualTo(200);
        assertThat(cheap.at("/total").decimalValue()).isEqualByComparingTo("800");
        // More than 3 decimals is refused.
        assertThat(sell(cashier, id, 0.7501, Map.of()).status()).isEqualTo(400);

        // Whole shillings: 0.333 kg at 800 is 266.4, which rounds to 266.
        assertThat(sell(cashier, id, 0.333, Map.of()).at("/total").decimalValue()).isEqualByComparingTo("266");

        // Refund of the weight line puts the same kilos back.
        BigDecimal beforeRefund = onHand("Steak");
        assertThat(post("/orders/" + orderId + "/refund", owner, Map.of("reason", "Wrong cut")).status()).isEqualTo(200);
        assertThat(onHand("Steak")).isEqualByComparingTo(beforeRefund.add(new BigDecimal("0.75")));

        // A piece item still sells whole pieces at its normal price.
        String sausages = item(owner, "Sausages").get("id").asText();
        Res two = sell(cashier, sausages, 2, Map.of());
        assertThat(two.at("/total").decimalValue()).isEqualByComparingTo("700");
        assertThat(two.at("/lines/0/unitName").isNull()).isTrue();
    }

    @Test
    void stockCountsAcceptKilosWithDecimals() {
        setUp("retail", "butchery");
        String id = item(owner, "Steak").get("id").asText();
        assertThat(post("/stock/adjust", owner, Map.of("itemId", id, "counted", 12.5)).status()).isEqualTo(200);
        assertThat(onHand("Steak")).isEqualByComparingTo("12.5");
    }

    @Test
    void grillStarterMeatIsByWeight() {
        setUp("restaurant", "grill");
        assertThat(item(owner, "Goat choma (per kg)").get("soldByWeight").asBoolean()).isTrue();
    }
}
