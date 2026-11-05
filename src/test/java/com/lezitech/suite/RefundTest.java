package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Refunds give the money back as it came in, net out of the till count, and return stock. */
class RefundTest extends IntegrationTest {

    @Test
    void managerRefundNetsOutOfTheTillAndReturnsStock() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        String manager = staff(owner, "MANAGER");
        post("/shifts/open", cashier, Map.of("openingCash", 1000));
        post("/shifts/open", manager, Map.of("openingCash", 0));
        String sugar = item(owner, "Sugar").get("id").asText();
        int before = item(owner, "Sugar").get("onHand").asInt();

        // Split tender: 100 M-Pesa + 500 cash for a 380 sale -> 220 change, 280 cash kept.
        JsonNode sale = post("/orders", cashier, Map.of("lines", line(sugar, 2),
                "payments", List.of(Map.of("method", "MPESA", "amount", 100, "reference", "ABC"), Map.of("method", "CASH", "amount", 500)))).body();
        assertThat(sale.get("change").decimalValue()).isEqualByComparingTo("220");
        String id = sale.get("id").asText();

        // Cashiers can't refund.
        assertThat(post("/orders/" + id + "/refund", cashier, Map.of("reason", "Wrong item")).status()).isEqualTo(403);
        // A reason is required.
        assertThat(post("/orders/" + id + "/refund", manager, Map.of("reason", "")).status()).isEqualTo(400);

        Res r = post("/orders/" + id + "/refund", manager, Map.of("reason", "Customer returned it"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/status").asText()).isEqualTo("REFUNDED");
        assertThat(r.at("/payments")).hasSize(4);
        // Refunding twice is refused.
        assertThat(post("/orders/" + id + "/refund", manager, Map.of("reason", "again")).status()).isEqualTo(409);

        // Stock is back.
        assertThat(item(owner, "Sugar").get("onHand").asInt()).isEqualTo(before);

        // The manager's till shows the money going out; the cashier's shows it coming in.
        JsonNode managerShift = get("/shifts/current", manager).body();
        assertThat(managerShift.get("refunds").decimalValue()).isEqualByComparingTo("380");
        assertThat(managerShift.get("expectedCash").decimalValue()).isEqualByComparingTo("-280");
        JsonNode cashierShift = get("/shifts/current", cashier).body();
        assertThat(cashierShift.get("expectedCash").decimalValue()).isEqualByComparingTo("1280");

        // Reports: no sales left, the refund is shown, and money by method nets to zero.
        JsonNode rep = get("/reports/sales", owner).body();
        assertThat(rep.at("/totals/sales").decimalValue()).isEqualByComparingTo("0");
        assertThat(rep.at("/refunds/amount").decimalValue()).isEqualByComparingTo("380");
        for (JsonNode m : rep.get("byMethod")) {
            assertThat(m.get("amount").decimalValue()).isEqualByComparingTo("0");
        }
    }

    @Test
    void refundingAnAccountSaleTakesItOffWhatTheyOwe() {
        String owner = token(signup("retail"));
        JsonNode c = post("/customers", owner, Map.of("name", "Account Atieno", "creditLimit", 5000)).body();
        String sugar = item(owner, "Sugar").get("id").asText();
        String id = post("/orders", owner, Map.of("customerId", c.get("id").asText(), "lines", line(sugar, 1),
                "payments", List.of(Map.of("method", "CREDIT", "amount", 190)))).at("/id").asText();
        assertThat(get("/customers/" + c.get("id").asText(), owner).at("/balance").decimalValue()).isEqualByComparingTo("190");
        // Cancelling a completed sale is a refund.
        assertThat(post("/orders/" + id + "/void", owner, Map.of("reason", "Billed the wrong person")).status()).isEqualTo(200);
        assertThat(get("/customers/" + c.get("id").asText(), owner).at("/balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(get("/orders/" + id, owner).at("/status").asText()).isEqualTo("REFUNDED");
    }
}
