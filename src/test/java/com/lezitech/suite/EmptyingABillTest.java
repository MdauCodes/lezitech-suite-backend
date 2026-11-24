package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bill can't be quietly emptied of everything it is worth. Correcting a bill that is still being typed is
 * free; once the kitchen has the dish, or the customer has been shown the bill, a manager agrees.
 */
class EmptyingABillTest extends IntegrationTest {

    private record Shop(String owner, String cashier, String managerId, String bill, String line) {
    }

    private Shop shop() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        String sugar = item(owner, "Sugar").get("id").asText();
        Res made = post("/orders", cashier, Map.of("lines", line(sugar, 2)));
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        return new Shop(owner, cashier, managerId, made.at("/id").asText(), made.at("/lines/0/id").asText());
    }

    private Map<String, Object> pin(String managerId) {
        return Map.of("userId", managerId, "pin", "1234");
    }

    @Test
    void correctingABillStillBeingTypedStaysFree() {
        Shop s = shop();
        Res off = call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(), Map.of("qty", 1));
        assertThat(off.status()).as(off.body().toString()).isEqualTo(200);
        Res all = call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(), Map.of("qty", 0));
        assertThat(all.status()).as(all.body().toString()).isEqualTo(200);
        // It is still written down, as it always was.
        assertThat(get("/activity?kind=ITEM_REMOVED", s.owner()).at("/entries").toString()).contains("Sugar");
    }

    @Test
    void onceTheCustomerHasSeenTheBillAManagerAgrees() {
        Shop s = shop();
        assertThat(post("/orders/" + s.bill() + "/printed", s.cashier(), Map.of()).status()).isEqualTo(200);
        Res refused = call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(), Map.of("qty", 0));
        assertThat(refused.status()).as(refused.body().toString()).isEqualTo(403);
        assertThat(refused.at("/code").asText()).isEqualTo("APPROVAL_NEEDED");
        // A wrong PIN is no good.
        assertThat(call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(),
                Map.of("qty", 0, "approval", Map.of("userId", s.managerId(), "pin", "9999"))).status()).isEqualTo(403);
        Res allowed = call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(),
                Map.of("qty", 0, "approval", pin(s.managerId())));
        assertThat(allowed.status()).as(allowed.body().toString()).isEqualTo(200);
    }

    @Test
    void addingToAPrintedBillIsStillFree() {
        Shop s = shop();
        post("/orders/" + s.bill() + "/printed", s.cashier(), Map.of());
        Res more = call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(), Map.of("qty", 5));
        assertThat(more.status()).as(more.body().toString()).isEqualTo(200);
    }

    @Test
    void aBiggerDiscountOnAPrintedBillAlsoNeedsAManager() {
        Shop s = shop();
        // This job is allowed to give discounts, so only the printing makes the difference here.
        Res set = call("PUT", "/settings", s.owner(),
                Map.of("settings", Map.of("access", Map.of("CASHIER", Map.of("discounts", true)))));
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        Res whileDraft = call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(), Map.of("discount", 5));
        assertThat(whileDraft.status()).as(whileDraft.body().toString()).isEqualTo(200);
        assertThat(post("/orders/" + s.bill() + "/printed", s.cashier(), Map.of()).status()).isEqualTo(200);
        Res refused = call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(), Map.of("discount", 10));
        assertThat(refused.status()).as(refused.body().toString()).isEqualTo(403);
        assertThat(call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(),
                Map.of("discount", 10, "approval", pin(s.managerId()))).status()).isEqualTo(200);
    }

    @Test
    void splittingIsFreeButTheNewBillRemembersTheCustomerSawIt() {
        Shop s = shop();
        post("/orders/" + s.bill() + "/printed", s.cashier(), Map.of());
        // Splitting itself is ordinary work on a busy floor and is not blocked.
        Res split = post("/orders/" + s.bill() + "/split", s.cashier(), Map.of("lineIds", List.of(s.line())));
        assertThat(split.status()).as(split.body().toString()).isEqualTo(200);
        String moved = split.at("/id").asText();
        String movedLine = split.at("/lines/0/id").asText();
        // But the items cannot then be emptied off the new bill for nothing.
        Res refused = call("PATCH", "/orders/" + moved + "/lines/" + movedLine, s.cashier(), Map.of("qty", 0));
        assertThat(refused.status()).as(refused.body().toString()).isEqualTo(403);
        assertThat(call("PATCH", "/orders/" + moved + "/lines/" + movedLine, s.cashier(),
                Map.of("qty", 0, "approval", pin(s.managerId()))).status()).isEqualTo(200);
    }

    @Test
    void whenTheOwnerTrustsTheJobNoPinIsAsked() {
        Shop s = shop();
        post("/orders/" + s.bill() + "/printed", s.cashier(), Map.of());
        Res set = call("PUT", "/settings", s.owner(),
                Map.of("settings", Map.of("access", Map.of("CASHIER", Map.of("cancels", true)))));
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        Res off = call("PATCH", "/orders/" + s.bill() + "/lines/" + s.line(), s.cashier(), Map.of("qty", 0));
        assertThat(off.status()).as(off.body().toString()).isEqualTo(200);
    }

    @Test
    void onlyTheKitchenOrAManagerCanSayADishCannotBeMade() {
        JsonNode signed = signup("restaurant");
        String owner = token(signed);
        String slug = signed.at("/tenant/slug").asText();
        assertThat(slug).isNotBlank();
        String waiter = staff(owner, "WAITER");
        String cook = staff(owner, "KITCHEN");
        String table = get("/floors", owner).body().get(0).get("tables").get(0).get("id").asText();
        String dish = null;
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                dish = i.get("id").asText();
                break;
            }
        }
        assertThat(dish).isNotNull();
        // A table order goes straight to the kitchen, so the dish is really being cooked.
        Res placed = post("/public/orders/" + slug, null,
                Map.of("tableId", table, "lines", List.of(Map.of("itemId", dish, "qty", 1))));
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        String lineId = null;
        for (JsonNode t : get("/kitchen", owner).body()) {
            for (JsonNode l : t.get("lines")) {
                lineId = l.get("id").asText();
            }
        }
        assertThat(lineId).isNotNull();
        // A waiter cannot use the kitchen screen to take a cooking dish off a bill.
        Res byWaiter = post("/kitchen/lines/" + lineId + "/reject", waiter, Map.of("reason", "finished"));
        assertThat(byWaiter.status()).as(byWaiter.body().toString()).isEqualTo(403);
        // The kitchen can, because the food genuinely isn't coming.
        Res byCook = post("/kitchen/lines/" + lineId + "/reject", cook, Map.of("reason", "finished"));
        assertThat(byCook.status()).as(byCook.body().toString()).isEqualTo(200);
    }

    @Test
    void chargingAPrintedBillToARoomCarriesThatWithIt() {
        String owner = token(signup("hotel"));
        String cashier = staff(owner, "CASHIER");
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        java.time.LocalDate in = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"));
        String room = get("/rooms/available?checkIn=" + in + "&checkOut=" + in.plusDays(2), owner).body().get(0).get("id").asText();
        Res stay = post("/stays", owner, Map.of("spaceId", room, "checkIn", in.toString(),
                "checkOut", in.plusDays(2).toString(), "guestName", "Wanjiku Kamau", "phone", "0712000222", "rate", 4000));
        assertThat(stay.status()).as(stay.body().toString()).isEqualTo(200);
        assertThat(post("/stays/" + stay.at("/id").asText() + "/check-in", owner, Map.of()).status()).isEqualTo(200);
        String thing = item(owner, "Soda").get("id").asText();
        Res made = post("/orders", cashier, Map.of("lines", line(thing, 2)));
        String bill = made.at("/id").asText();
        assertThat(post("/orders/" + bill + "/printed", cashier, Map.of()).status()).isEqualTo(200);
        Res charged = post("/orders/" + bill + "/charge-to-stay", cashier, Map.of("stayId", stay.at("/id").asText()));
        assertThat(charged.status()).as(charged.body().toString()).isEqualTo(200);
        String folio = charged.at("/id").asText();
        String folioLine = null;
        for (JsonNode l : get("/orders/" + folio, owner).at("/lines")) {
            folioLine = l.get("id").asText();
        }
        assertThat(folioLine).isNotNull();
        // Moving a printed bill onto a fresh folio is not a way to take the items off for nothing.
        Res refused = call("PATCH", "/orders/" + folio + "/lines/" + folioLine, cashier, Map.of("qty", 0));
        assertThat(refused.status()).as(refused.body().toString()).isEqualTo(403);
        assertThat(call("PATCH", "/orders/" + folio + "/lines/" + folioLine, cashier,
                Map.of("qty", 0, "approval", Map.of("userId", managerId, "pin", "1234"))).status()).isEqualTo(200);
    }

    @Test
    void aWaiterCannotUseTheSwapRouteToStripACookingDish() {
        JsonNode signed = signup("restaurant");
        String owner = token(signed);
        String slug = signed.at("/tenant/slug").asText();
        String waiter = staff(owner, "WAITER");
        staff(owner, "MANAGER");
        String managerId = userId(owner, "MANAGER");
        String table = get("/floors", owner).body().get(0).get("tables").get(0).get("id").asText();
        java.util.List<String> dishes = new java.util.ArrayList<>();
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                dishes.add(i.get("id").asText());
            }
        }
        assertThat(dishes.size()).isGreaterThan(1);
        Res placed = post("/public/orders/" + slug, null,
                Map.of("tableId", table, "lines", List.of(Map.of("itemId", dishes.get(0), "qty", 1))));
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        String lineId = null;
        for (JsonNode t : get("/kitchen", owner).body()) {
            for (JsonNode l : t.get("lines")) {
                lineId = l.get("id").asText();
            }
        }
        assertThat(lineId).isNotNull();
        // Offering a swap ends with the dish off the bill if the diner says no, so the waiter is asked too.
        Res swap = post("/kitchen/lines/" + lineId + "/reject", waiter,
                Map.of("reason", "finished", "substituteItemId", dishes.get(1)));
        assertThat(swap.status()).as(swap.body().toString()).isEqualTo(403);
        assertThat(post("/kitchen/lines/" + lineId + "/reject", waiter,
                Map.of("reason", "finished", "substituteItemId", dishes.get(1),
                        "approval", Map.of("userId", managerId, "pin", "1234"))).status()).isEqualTo(200);
    }
}
