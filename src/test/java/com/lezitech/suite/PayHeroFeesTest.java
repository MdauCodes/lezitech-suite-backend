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

        assertThat(call("PUT", "/settings/payments/fee-mode", owner, Map.of("mode", "ADD_TO_BILL")).status()).isEqualTo(400);
        assertThat(call("PUT", "/settings/payments/fee-mode", owner, Map.of("mode", "cost")).status()).isEqualTo(200);
        assertThat(get("/settings/payments/payhero-fees", owner).at("/mode").asText()).isEqualTo("COST");

        String cashier = staff(owner, "CASHIER");
        assertThat(call("PUT", "/settings/payments/fee-mode", cashier, Map.of("mode", "KNOW")).status()).isEqualTo(403);

        JsonNode rep = get("/reports/sales", owner).body();
        assertThat(rep.at("/mpesaFees/mode").asText()).isEqualTo("COST");
        assertThat(rep.at("/mpesaFees/amount").decimalValue()).isEqualByComparingTo("0");
    }
}
