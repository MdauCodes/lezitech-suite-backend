package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.mpesa.PayHeroFees;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PayHero's charge per payment: worked out from its published table, shown to the owner, and in reports. */
class PayHeroFeesTest extends IntegrationTest {

    @Autowired
    PayHeroFees fees;

    @Test
    void chargeFollowsPayHerosBands() {
        assertThat(fees.fee(new BigDecimal("10"))).isEqualByComparingTo("0");
        assertThat(fees.fee(new BigDecimal("49"))).isEqualByComparingTo("1");
        assertThat(fees.fee(new BigDecimal("499.40"))).isEqualByComparingTo("10"); // sent as 500 whole shillings
        assertThat(fees.fee(new BigDecimal("1000"))).isEqualByComparingTo("15");
        assertThat(fees.fee(new BigDecimal("35000"))).isEqualByComparingTo("105"); // the stray empty band is skipped
        assertThat(fees.fee(new BigDecimal("5000000"))).isEqualByComparingTo("320"); // above the top band: top price
        assertThat(fees.fee(BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void ownerSeesTheChargeAndChoosesHowToCountIt() {
        JsonNode s = signup("retail");
        String owner = token(s);
        JsonNode t = get("/settings/payments/payhero-fees", owner).body();
        assertThat(t.at("/mode").asText()).isEqualTo("KNOW");
        assertThat(t.at("/bands").size()).isGreaterThan(5);
        assertThat(t.at("/examples/1/amount").asInt()).isEqualTo(500);
        assertThat(t.at("/examples/1/fee").decimalValue()).isEqualByComparingTo("10");

        assertThat(call("PUT", "/settings/payments/fee-mode", owner, Map.of("mode", "SOMETHING")).status()).isEqualTo(400);
        assertThat(call("PUT", "/settings/payments/fee-mode", owner, Map.of("mode", "cost")).status()).isEqualTo(200);
        assertThat(get("/settings/payments/payhero-fees", owner).at("/mode").asText()).isEqualTo("COST");

        String cashier = staff(owner, "CASHIER");
        assertThat(call("PUT", "/settings/payments/fee-mode", cashier, Map.of("mode", "KNOW")).status()).isEqualTo(403);

        JsonNode rep = get("/reports/sales", owner).body();
        assertThat(rep.at("/mpesaFees/mode").asText()).isEqualTo("COST");
        assertThat(rep.at("/mpesaFees/amount").decimalValue()).isEqualByComparingTo("0");
    }

    private JsonNode waitFor(String requestId, String token) throws InterruptedException {
        JsonNode st = null;
        for (int i = 0; i < 50; i++) {
            st = get("/mpesa/requests/" + requestId, token).body();
            if (!"PENDING".equals(st.get("status").asText())) {
                return st;
            }
            Thread.sleep(100);
        }
        return st;
    }

    private boolean hasChargeLine(JsonNode order) {
        for (JsonNode l : order.at("/lines")) {
            if ("M-Pesa charge (PayHero)".equals(l.at("/name").asText())) return true;
        }
        return false;
    }

    @Test
    void customerPaysTheChargeWhenTheOwnerChoseSo() throws Exception {
        String owner = token(signup("retail"));
        call("PUT", "/settings/payments/fee-mode", owner, Map.of("mode", "CUSTOMER"));
        String itemId = item(owner, "Sugar").get("id").asText();

        // Paid: the charge is a line on the bill and the prompt asks for bill plus charge.
        String order = post("/orders", owner, Map.of("lines", line(itemId, 1))).at("/id").asText();
        BigDecimal before = get("/orders/" + order, owner).at("/total").decimalValue();
        Res start = post("/mpesa/stk", owner, Map.of("orderId", order, "phone", "0712 345 678"));
        assertThat(start.status()).as(start.body().toString()).isEqualTo(200);
        BigDecimal fee = start.at("/customerFee").decimalValue();
        assertThat(fee).isEqualByComparingTo(fees.fee(before.add(fees.fee(before))));
        assertThat(fee.signum()).isPositive();
        assertThat(start.at("/amount").decimalValue()).isEqualByComparingTo(before.add(fee));
        assertThat(waitFor(start.at("/id").asText(), owner).get("status").asText()).isEqualTo("PAID");
        JsonNode paid = get("/orders/" + order, owner).body();
        assertThat(paid.at("/status").asText()).isEqualTo("PAID");
        assertThat(paid.at("/total").decimalValue()).isEqualByComparingTo(before.add(fee));
        assertThat(hasChargeLine(paid)).isTrue();

        // Cancelled by the customer: the charge comes off the bill again.
        String order2 = post("/orders", owner, Map.of("lines", line(itemId, 1))).at("/id").asText();
        Res start2 = post("/mpesa/stk", owner, Map.of("orderId", order2, "phone", "0712349999"));
        assertThat(hasChargeLine(get("/orders/" + order2, owner).body())).isTrue();
        assertThat(waitFor(start2.at("/id").asText(), owner).get("status").asText()).isEqualTo("CANCELLED");
        JsonNode open = get("/orders/" + order2, owner).body();
        assertThat(hasChargeLine(open)).isFalse();
        assertThat(open.at("/total").decimalValue()).isEqualByComparingTo(before);

        // Other modes leave the bill alone.
        call("PUT", "/settings/payments/fee-mode", owner, Map.of("mode", "KNOW"));
        String order3 = post("/orders", owner, Map.of("lines", line(itemId, 1))).at("/id").asText();
        Res start3 = post("/mpesa/stk", owner, Map.of("orderId", order3, "phone", "0712 345 678"));
        assertThat(start3.at("/amount").decimalValue()).isEqualByComparingTo(before);
    }

    @Test
    void guidedPayHeroSetupAsksPlainly() {
        String owner = token(signup("retail"));
        assertThat(post("/settings/payments/payhero/keys", owner, Map.of("username", " ", "password", "x")).status()).isEqualTo(400);
        Res noKeys = get("/settings/payments/payhero/account", owner);
        assertThat(noKeys.status()).isEqualTo(400);
        assertThat(noKeys.at("/message").asText()).contains("PayHero API username and password");
        assertThat(post("/settings/payments/payhero/use", owner, Map.of("channelId", "")).status()).isEqualTo(400);
        assertThat(post("/settings/payments/payhero/add-till", owner, Map.of("type", "bank", "number", "123456")).status()).isEqualTo(400);
        assertThat(post("/settings/payments/payhero/top-up", owner, Map.of("amount", 5, "phone", "0712345678")).status()).isEqualTo(400);
        String cashier = staff(owner, "CASHIER");
        assertThat(get("/settings/payments/payhero/account", cashier).status()).isEqualTo(403);
    }
}
