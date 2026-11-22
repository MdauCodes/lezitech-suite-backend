package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Cancelling a bill with things on it, and selling below what an item cost, both need a manager. */
class VoidAndCostApprovalTest extends IntegrationTest {

    private String newItem(String owner, double price, double cost) {
        Res r = post("/items", owner, Map.of("name", "Costed " + System.nanoTime(), "kind", "PRODUCT", "price", price, "cost", cost));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private Res sellAt(String token, String itemId, int price, Map<String, Object> approval) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("lines", List.of(Map.of("itemId", itemId, "qty", 1, "unitPrice", price)));
        if (approval != null) {
            body.put("approval", approval);
        }
        return post("/orders", token, body);
    }

    @Test
    void aCashierCannotCancelABillWithALineOnItWithoutAManagerPin() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        String sugar = item(owner, "Sugar").get("id").asText();

        String bill = post("/orders", cashier, Map.of("lines", line(sugar, 1))).at("/id").asText();
        Res r = post("/orders/" + bill + "/void", cashier, Map.of("reason", "Changed mind"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.at("/message").asText()).contains("A manager must approve cancelling this bill");
        r = post("/orders/" + bill + "/void", cashier, Map.of("reason", "Changed mind", "approval", Map.of("userId", managerId, "pin", "9999")));
        assertThat(r.status()).isEqualTo(403);
        r = post("/orders/" + bill + "/void", cashier, Map.of("reason", "Changed mind", "approval", Map.of("userId", managerId, "pin", "1234")));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/status").asText()).isEqualTo("VOID");
    }

    @Test
    void anEmptyBillCancelsFineAndManagersNeedNoPin() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        String manager = staff(owner, "MANAGER");
        String sugar = item(owner, "Sugar").get("id").asText();

        String empty = post("/orders", cashier, Map.of("lines", List.of())).at("/id").asText();
        assertThat(post("/orders/" + empty + "/void", cashier, Map.of("reason", "Opened by mistake")).status()).isEqualTo(200);

        String b1 = post("/orders", owner, Map.of("lines", line(sugar, 1))).at("/id").asText();
        assertThat(post("/orders/" + b1 + "/void", owner, Map.of("reason", "Test")).status()).isEqualTo(200);
        String b2 = post("/orders", manager, Map.of("lines", line(sugar, 1))).at("/id").asText();
        assertThat(post("/orders/" + b2 + "/void", manager, Map.of("reason", "Test")).status()).isEqualTo(200);
    }

    @Test
    void sellingBelowCostNeedsAManagerPinForACashierButNotAnOwnerOrManager() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        String manager = staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        String id = newItem(owner, 100, 80);

        Res r = sellAt(cashier, id, 70, null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.at("/message").asText()).contains("below what it cost (80");
        assertThat(sellAt(cashier, id, 70, Map.of("userId", managerId, "pin", "9999")).status()).isEqualTo(403);
        r = sellAt(cashier, id, 70, Map.of("userId", managerId, "pin", "1234"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/total").decimalValue()).isEqualByComparingTo("70");

        assertThat(sellAt(owner, id, 70, null).status()).isEqualTo(200);
        assertThat(sellAt(manager, id, 70, null).status()).isEqualTo(200);
    }

    private void allowCashierDiscounts(String owner) {
        Res r = call("PUT", "/settings", owner, Map.of("settings", Map.of("access", Map.of("CASHIER", Map.of("discounts", true)))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    }

    @Test
    void zeroingEveryLineThenCancellingOrDeletingStillNeedsAManagerPin() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        String sugar = item(owner, "Sugar").get("id").asText();

        for (String how : List.of("void", "delete")) {
            Res made = post("/orders", cashier, Map.of("lines", line(sugar, 1)));
            String bill = made.at("/id").asText();
            String lineId = made.at("/lines/0/id").asText();
            Res zero = call("PATCH", "/orders/" + bill + "/lines/" + lineId, cashier, Map.of("qty", 0));
            assertThat(zero.status()).as(zero.body().toString()).isEqualTo(200);
            assertThat(get("/activity?kind=ITEM_REMOVED", owner).at("/entries").toString()).contains("#");

            if (how.equals("void")) {
                Res r = post("/orders/" + bill + "/void", cashier, Map.of("reason", "Nothing on it"));
                assertThat(r.status()).isEqualTo(403);
                assertThat(post("/orders/" + bill + "/void", cashier, Map.of("reason", "x", "approval", Map.of("userId", managerId, "pin", "9999"))).status()).isEqualTo(403);
                r = post("/orders/" + bill + "/void", cashier, Map.of("reason", "Nothing on it", "approval", Map.of("userId", managerId, "pin", "1234")));
                assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
                assertThat(get("/activity?kind=VOID", owner).at("/entries").toString()).contains("approvedBy");
            } else {
                assertThat(call("DELETE", "/orders/" + bill, cashier, Map.of()).status()).isEqualTo(403);
                assertThat(call("DELETE", "/orders/" + bill, cashier, Map.of("approval", Map.of("userId", managerId, "pin", "1234"))).status()).isEqualTo(200);
            }
        }
    }

    @Test
    void aCashierAllowedDiscountsStillCannotCancelALoadedBillOrSellBelowCost() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        allowCashierDiscounts(owner);
        String sugar = item(owner, "Sugar").get("id").asText();

        String bill = post("/orders", cashier, Map.of("lines", line(sugar, 1))).at("/id").asText();
        Res r = post("/orders/" + bill + "/void", cashier, Map.of("reason", "Changed mind"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(post("/orders/" + bill + "/void", cashier, Map.of("reason", "Changed mind", "approval", Map.of("userId", managerId, "pin", "1234"))).status()).isEqualTo(200);

        String id = newItem(owner, 100, 80);
        r = sellAt(cashier, id, 70, null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.at("/message").asText()).contains("below what it cost");
    }

    @Test
    void belowCostIsStillCaughtAtOrAboveTheWholesalePriceWhenBargainingIsAllowed() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        String managerId;
        staff(owner, "MANAGER");
        managerId = userId(owner, "MANAGER");
        // Wholesale 60 is under the cost of 80, so 70 is allowed by the bargaining rule but is below cost.
        Res made = post("/items", owner, Map.of("name", "Bargain " + System.nanoTime(), "kind", "PRODUCT", "price", 100, "cost", 80, "wholesalePrice", 60));
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        String id = made.at("/id").asText();

        Res r = sellAt(cashier, id, 70, null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.at("/message").asText()).contains("below what it cost (80");
        r = sellAt(cashier, id, 70, Map.of("userId", managerId, "pin", "1234"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);

        JsonNode entries = get("/activity?kind=BELOW_COST", owner).at("/entries");
        assertThat(entries.size()).isGreaterThan(0);
        assertThat(entries.get(0).get("approvedBy").asText()).isNotBlank();
        // Between wholesale and cost with no PIN is the exact case that used to slip through.
        assertThat(sellAt(cashier, id, 65, null).status()).isEqualTo(403);
    }

    @Test
    void rejectingEveryLineThroughTheKitchenIsWrittenDownAndStillNeedsAManagerPinToCancelOrDelete() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String cashier = staff(owner, "CASHIER");
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        String table = get("/floors", owner).body().get(0).get("tables").get(0).get("id").asText();
        List<String> dishes = new java.util.ArrayList<>();
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText()) && dishes.size() < 2) {
                dishes.add(i.get("id").asText());
            }
        }

        for (String how : List.of("void", "delete")) {
            // The diner's order goes straight to the kitchen; the cashier then sends every dish back.
            Res placed = post("/public/orders/" + slug, null, Map.of("tableId", table, "lines", List.of(
                    Map.of("itemId", dishes.get(0), "qty", 1), Map.of("itemId", dishes.get(1), "qty", 1))));
            assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
            int number = placed.at("/number").asInt();
            JsonNode ticket = null;
            for (JsonNode t : get("/kitchen", owner).body()) {
                if (t.get("number").asInt() == number) {
                    ticket = t;
                }
            }
            assertThat(ticket).isNotNull();
            String bill = ticket.get("orderId").asText();
            int before = get("/activity?kind=ITEM_REMOVED", owner).at("/entries").size();
            for (JsonNode l : ticket.get("lines")) {
                Res r = post("/kitchen/lines/" + l.get("id").asText() + "/reject", cashier, Map.of("reason", "finished"));
                assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
            }
            // Each dish taken off through the kitchen screen left a note, flagged because the kitchen had it.
            JsonNode entries = get("/activity?kind=ITEM_REMOVED", owner).at("/entries");
            assertThat(entries.size()).isEqualTo(before + 2);
            assertThat(entries.toString()).contains("the kitchen could not make it").contains("CONCERN");

            if (how.equals("void")) {
                assertThat(post("/orders/" + bill + "/void", cashier, Map.of("reason", "Nothing on it")).status()).isEqualTo(403);
                Res r = post("/orders/" + bill + "/void", cashier, Map.of("reason", "Nothing on it", "approval", Map.of("userId", managerId, "pin", "1234")));
                assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
            } else {
                assertThat(call("DELETE", "/orders/" + bill, cashier, Map.of()).status()).isEqualTo(403);
                assertThat(call("DELETE", "/orders/" + bill, cashier, Map.of("approval", Map.of("userId", managerId, "pin", "1234"))).status()).isEqualTo(200);
            }
        }
    }

    @Test
    void theManagerPinCountIsSharedAcrossEveryWayOfWritingTheSameId() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        String sugar = item(owner, "Sugar").get("id").asText();
        String bill = post("/orders", cashier, Map.of("lines", line(sugar, 1))).at("/id").asText();

        // Alternating the letter case must not give a fresh set of tries each time.
        int refused = 0;
        for (int i = 0; i < 8; i++) {
            String spelling = i % 2 == 0 ? managerId.toLowerCase() : managerId.toUpperCase();
            Res r = post("/orders/" + bill + "/void", cashier, Map.of("reason", "x", "approval", Map.of("userId", spelling, "pin", "000" + i)));
            if (r.status() == 429) {
                refused++;
            }
        }
        assertThat(refused).isGreaterThan(0);
        // Even the right PIN waits while the lock is on.
        assertThat(post("/orders/" + bill + "/void", cashier, Map.of("reason", "x", "approval", Map.of("userId", managerId.toUpperCase(), "pin", "1234"))).status()).isEqualTo(429);
    }
}
