package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The lowest price an item may be sold at, and the activity log that records price changes and other money events. */
class ActivityAndFloorTest extends IntegrationTest {

    private String withLowestPrice(String owner, String name, int shelf, int lowest) {
        JsonNode it = item(owner, name);
        ObjectNode body = ((ObjectNode) it.deepCopy());
        body.put("price", shelf);
        body.put("wholesalePrice", lowest);
        assertThat(call("PUT", "/items/" + it.get("id").asText(), owner, json.convertValue(body, Map.class)).status()).isEqualTo(200);
        return it.get("id").asText();
    }

    private Res sell(String token, String itemId, int price, Map<String, Object> extra) {
        Map<String, Object> line = new java.util.HashMap<>(Map.of("itemId", itemId, "qty", 2, "unitPrice", price));
        Map<String, Object> body = new java.util.HashMap<>(Map.of("lines", List.of(line), "payments", List.of(Map.of("method", "CASH", "amount", 1000))));
        body.putAll(extra);
        return post("/orders", token, body);
    }

    private JsonNode activity(String owner) {
        return get("/activity", owner).body();
    }

    @Test
    void cashierCanBargainDownToTheLowestPriceButNotBelow() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        String managerId;
        staff(owner, "MANAGER");
        managerId = userId(owner, "MANAGER");
        post("/shifts/open", cashier, Map.of("openingCash", 0));
        String sugar = withLowestPrice(owner, "Sugar", 200, 170);

        // Between shelf and lowest: the cashier's call, logged as a notice with the money given up.
        assertThat(sell(cashier, sugar, 180, Map.of()).status()).isEqualTo(200);
        // Below the lowest: needs a manager's PIN.
        Res below = sell(cashier, sugar, 150, Map.of());
        assertThat(below.status()).isEqualTo(403);
        assertThat(below.at("/code").asText()).isEqualTo("APPROVAL_NEEDED");
        assertThat(sell(cashier, sugar, 150, Map.of("approval", Map.of("userId", managerId, "pin", "1234"))).status()).isEqualTo(200);

        JsonNode log = activity(owner);
        JsonNode entries = log.get("entries");
        JsonNode notice = find(entries, "PRICE_CHANGE");
        assertThat(notice.get("severity").asText()).isEqualTo("NOTICE");
        assertThat(notice.get("amount").decimalValue()).isEqualByComparingTo("40"); // (200 - 180) × 2
        JsonNode concern = find(entries, "BELOW_LOWEST_PRICE");
        assertThat(concern.get("severity").asText()).isEqualTo("CONCERN");
        assertThat(concern.get("approvedBy").asText()).isEqualTo("MANAGER person");
        assertThat(concern.get("amount").decimalValue()).isEqualByComparingTo("100"); // (200 - 150) × 2
        assertThat(log.at("/totals/concerns").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(log.get("byPerson").get(0).get("name").asText()).isEqualTo("CASHIER person");

        // The log is for owners and managers, not cashiers.
        assertThat(get("/activity", cashier).status()).isEqualTo(403);
    }

    @Test
    void ownerCanRequireAPinForAnyPriceCut() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        String sugar = withLowestPrice(owner, "Sugar", 200, 170);
        assertThat(call("PUT", "/settings", owner, Map.of("settings", Map.of("bargainToLowest", false))).status()).isEqualTo(200);
        assertThat(sell(cashier, sugar, 180, Map.of()).status()).isEqualTo(403);
    }

    @Test
    void priceEditsShortTillsAndCancellationsAreLogged() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        withLowestPrice(owner, "Sugar", 210, 170);
        JsonNode shift = post("/shifts/open", cashier, Map.of("openingCash", 1000)).body();
        String sugar = item(owner, "Sugar").get("id").asText();
        post("/orders", cashier, Map.of("lines", line(sugar, 1), "payments", List.of(Map.of("method", "CASH", "amount", 210))));
        post("/shifts/" + shift.get("id").asText() + "/close", cashier, Map.of("countedCash", 1100));
        String open = post("/orders", owner, Map.of("lines", line(sugar, 1))).at("/id").asText();
        assertThat(post("/orders/" + open + "/void", owner, Map.of("reason", "Customer left")).status()).isEqualTo(200);

        JsonNode entries = activity(owner).get("entries");
        assertThat(find(entries, "ITEM_PRICE_EDIT").get("summary").asText()).contains("Sugar");
        JsonNode short_ = find(entries, "CASH_SHORT");
        assertThat(short_.get("severity").asText()).isEqualTo("CONCERN");
        assertThat(short_.get("amount").decimalValue()).isEqualByComparingTo("110");
        assertThat(find(entries, "VOID").get("summary").asText()).contains("Customer left");
    }

    private static JsonNode find(JsonNode entries, String kind) {
        for (JsonNode e : entries) {
            if (kind.equals(e.get("kind").asText())) {
                return e;
            }
        }
        throw new AssertionError("No " + kind + " in the activity log: " + entries);
    }
}
