package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branches: a second place of the same business. Staff work at one branch, and the till, stock and sales follow
 * them there; stock moves between branches; the owner sees everything added up, and each branch in the report.
 */
class BranchesTest extends IntegrationTest {

    private double onHand(String token, String itemId) {
        for (JsonNode i : get("/items", token).body()) {
            if (i.get("id").asText().equals(itemId)) {
                return i.get("onHand").asDouble();
            }
        }
        throw new AssertionError("no item");
    }

    @Test
    void staffAtABranchSellFromThatBranchsStock() {
        JsonNode s = signup("retail");
        String owner = token(s);
        JsonNode list = get("/branches", owner).body();
        assertThat(list.at("/branches")).hasSize(1);
        String main = list.at("/branches/0/id").asText();

        Res added = post("/branches", owner, Map.of("name", "Westlands branch", "address", "Ring Road, Westlands"));
        assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
        String west = added.at("/id").asText();
        assertThat(post("/branches", owner, Map.of("name", "westlands branch")).status()).isEqualTo(409);

        // A stocked item: all of it is at the main place to start with.
        JsonNode item = null;
        for (JsonNode i : get("/items", owner).body()) {
            if (item == null && i.get("trackStock").asBoolean() && i.get("onHand").asDouble() >= 10) {
                item = i;
            }
        }
        String itemId = item.get("id").asText();
        double total = item.get("onHand").asDouble();
        double price = item.get("price").asDouble();

        // The cashier works at Westlands: nothing there yet.
        String cashier = staff(owner, "CASHIER");
        String cashierId = get("/auth/me", cashier).at("/user/id").asText();
        assertThat(post("/branches/assign", owner, Map.of("userId", cashierId, "locationId", west)).status()).isEqualTo(200);
        assertThat(onHand(cashier, itemId)).isZero();

        // Move 5 to Westlands; the cashier sells 2 there.
        assertThat(post("/stock/transfer", owner, Map.of("fromId", main, "toId", west, "lines", List.of(Map.of("itemId", itemId, "qty", 5)))).status()).isEqualTo(200);
        assertThat(onHand(cashier, itemId)).isEqualTo(5);
        post("/shifts/open", cashier, Map.of("openingCash", 0));
        String bill = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", itemId, "qty", 2)))).at("/id").asText();
        assertThat(post("/orders/" + bill + "/pay", cashier, Map.of("payments", List.of(Map.of("method", "CASH", "amount", price * 2)))).at("/status").asText()).isEqualTo("PAID");
        assertThat(onHand(cashier, itemId)).isEqualTo(3);

        // The owner sees it all added up; working at Westlands, only that branch.
        assertThat(onHand(owner, itemId)).isEqualTo(total - 2);
        post("/branches/work-at", owner, Map.of("locationId", west));
        assertThat(onHand(owner, itemId)).isEqualTo(3);
        post("/branches/work-at", owner, Map.of("locationId", ""));
        assertThat(get("/branches", owner).at("/workingAt").isNull()).isTrue();

        // The sales report shows each branch.
        assertThat(get("/reports/sales", owner).at("/byBranch").toString()).contains("Westlands branch");
        assertThat(get("/branches", owner).body().toString()).contains("Westlands branch");

        // Staff can't add branches; the main place can't be closed.
        assertThat(post("/branches", cashier, Map.of("name", "Kilimani")).status()).isEqualTo(403);
        assertThat(call("PUT", "/branches/" + main, owner, Map.of("active", false)).status()).isEqualTo(400);
    }
}
