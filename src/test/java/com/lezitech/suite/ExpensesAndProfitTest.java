package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 1: business expenses, cash paid from a till, and profit & loss with the cost saved at the time of sale. */
class ExpensesAndProfitTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    /** A product with a known selling and buying price, VAT-free so the sums are plain. */
    private String product(String owner, int price, int cost) {
        JsonNode it = item(owner, "");
        ObjectNode body = it.deepCopy();
        body.put("price", price);
        body.put("cost", cost);
        body.put("taxRate", 0);
        body.put("trackStock", false);
        assertThat(call("PUT", "/items/" + it.get("id").asText(), owner, json.convertValue(body, Map.class)).status()).isEqualTo(200);
        return it.get("id").asText();
    }

    private void sell(String token, String item, int qty, int price) {
        Res r = post("/orders", token, Map.of("lines", line(item, qty), "payments", List.of(Map.of("method", "CASH", "amount", qty * price))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    }

    @Test
    void profitIsSalesLessWhatItCostLessWhatWasSpent() {
        String owner = token(signup("retail"));
        post("/shifts/open", owner, Map.of("openingCash", 0));
        String soap = product(owner, 100, 60);
        sell(owner, soap, 3, 100);

        assertThat(post("/expenses", owner, Map.of("category", "Electricity", "what", "Tokens", "amount", 120, "method", "MPESA")).status()).isEqualTo(200);
        JsonNode pnl = get("/reports/profit-loss", owner).body();
        assertThat(pnl.get("sales").decimalValue()).isEqualByComparingTo("300");
        assertThat(pnl.get("costOfSales").decimalValue()).isEqualByComparingTo("180");
        assertThat(pnl.get("grossProfit").decimalValue()).isEqualByComparingTo("120");
        assertThat(pnl.get("costs").toString()).contains("Electricity");
        assertThat(pnl.get("netProfit").decimalValue()).isEqualByComparingTo("0");

        // Raising the buying price later doesn't change the profit on what was already sold.
        product(owner, 100, 90);
        assertThat(get("/reports/profit-loss", owner).body().get("costOfSales").decimalValue()).isEqualByComparingTo("180");
        // The previous period is there to compare with.
        assertThat(pnl.at("/previous/sales").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void cashPaidFromATillLowersWhatTheTillShouldHave() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        String soap = product(owner, 100, 60);
        // Not without an open till, and cashiers can't record ordinary expenses or see the list.
        assertThat(post("/expenses", cashier, Map.of("fromTill", true, "what", "Water", "amount", 50)).status()).isEqualTo(409);
        assertThat(post("/expenses", cashier, Map.of("what", "Water", "amount", 50)).status()).isEqualTo(403);
        JsonNode shift = post("/shifts/open", cashier, Map.of("openingCash", 1000)).body();
        sell(cashier, soap, 2, 100);
        Res paid = post("/expenses", cashier, Map.of("fromTill", true, "category", "Water", "what", "Water vendor", "amount", 50));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        assertThat(paid.at("/method").asText()).isEqualTo("CASH");
        assertThat(get("/expenses", cashier).status()).isEqualTo(403);

        JsonNode tills = get("/shifts/" + shift.get("id").asText(), owner).body();
        assertThat(tills.get("expectedCash").decimalValue()).isEqualByComparingTo("1150"); // 1000 + 200 sold - 50 paid out
        assertThat(get("/activity", owner).body().get("entries").toString()).contains("TILL_PAYOUT");
    }

    @Test
    void ownersKeepTheListAndOtherBusinessesSeeNothing() {
        String owner = token(signup("restaurant"));
        assertThat(get("/expenses/categories", owner).body().toString()).contains("Rent", "Gas and charcoal", "Other");
        String id = post("/expenses", owner, Map.of("category", "Rent", "what", "November rent", "amount", 30000, "method", "BANK")).at("/id").asText();
        assertThat(post("/expenses", owner, Map.of("what", "Nothing", "amount", 0)).status()).isEqualTo(400);
        assertThat(post("/expenses", owner, Map.of("amount", 10)).status()).isEqualTo(400);

        JsonNode list = get("/expenses", owner).body();
        assertThat(list.get("total").decimalValue()).isEqualByComparingTo("30000");
        assertThat(list.at("/byCategory/0/category").asText()).isEqualTo("Rent");

        assertThat(call("PUT", "/expenses/" + id, owner, Map.of("category", "Rent", "what", "November rent", "amount", 32000, "method", "BANK")).status()).isEqualTo(200);
        assertThat(get("/activity", owner).body().get("entries").toString()).contains("EXPENSE_CHANGE");

        String other = token(signup("restaurant"));
        assertThat(get("/expenses", other).body().get("total").decimalValue()).isEqualByComparingTo("0");
        assertThat(call("DELETE", "/expenses/" + id, other, null).status()).isEqualTo(404);
        assertThat(call("DELETE", "/expenses/" + id, owner, null).status()).isEqualTo(200);
    }

    @Test
    void profitAndLossIsAGrowthToolButExpensesAreOnEveryPlan() {
        JsonNode s = signup("retail");
        String owner = token(s);
        jdbc.update("UPDATE tenants SET status = 'ACTIVE', plan = 'LITE', paid_until = now() + interval '30 days' WHERE id = ?",
                UUID.fromString(s.at("/tenant/id").asText()));
        Res refused = get("/reports/profit-loss", owner);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.at("/code").asText()).isEqualTo("PLAN_FEATURE");
        assertThat(post("/expenses", owner, Map.of("what", "Bags", "amount", 200)).status()).isEqualTo(200);
    }
}
