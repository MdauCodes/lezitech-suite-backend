package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Catering orders: booking, deposit and balance on one bill, the manager's say on cancelling paid orders, and the tenant wall. */
class CateringTest extends IntegrationTest {

    private JsonNode signup() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Test canteen " + email.substring(6, 10), "sector", "restaurant", "subSector", "canteen",
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    private String date() {
        return LocalDate.now(ZoneId.of("Africa/Nairobi")).plusDays(5).toString();
    }

    private Map<String, Object> order(String itemId, Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>(Map.of("name", "Wanjiru Kamau", "phone", "0712345678", "date", date(), "time", "13:00",
                "place", "Karen, Nairobi", "people", 50, "notes", "No pork",
                "lines", List.of(Map.of("itemId", itemId, "qty", 1, "perPerson", true, "unitPrice", 500))));
        m.putAll(extra);
        return m;
    }

    /** At the menu price, so no approval is needed. */
    private Map<String, Object> atMenuPrice(String itemId) {
        Map<String, Object> m = order(itemId, Map.of());
        m.put("lines", List.of(Map.of("itemId", itemId, "qty", 1, "perPerson", true)));
        return m;
    }

    private String itemId(String owner) {
        return item(owner, "Catering: buffet").get("id").asText();
    }

    @Test
    void canteensPromiseCateringAndItIsOnInTheirSetup() {
        JsonNode s = signup();
        assertThat(s.at("/tenant/settings/features/catering").asBoolean()).isTrue();
    }

    @Test
    void bookPayDepositThenBalanceAndTotalsAreRight() {
        JsonNode s = signup();
        String owner = token(s);
        String id = itemId(owner);
        post("/shifts/open", owner, Map.of("openingCash", 0));

        // 50 people x 500 each = 25,000; deposit 10,000 in cash.
        Res placed = post("/catering", owner, order(id, Map.of("deposit", 10000, "depositMethod", "CASH")));
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        JsonNode o = placed.body();
        assertThat(o.get("total").asDouble()).isEqualTo(25000);
        assertThat(o.get("paid").asDouble()).isEqualTo(10000);
        assertThat(o.get("balance").asDouble()).isEqualTo(15000);
        assertThat(o.get("status").asText()).isEqualTo("BOOKED");
        assertThat(o.at("/lines/0/totalQty").asDouble()).isEqualTo(50);
        String cid = o.get("id").asText();

        // More than the balance is refused, and M-Pesa needs its code.
        assertThat(post("/catering/" + cid + "/pay", owner, Map.of("amount", 20000, "method", "CASH")).status()).isEqualTo(400);
        assertThat(post("/catering/" + cid + "/pay", owner, Map.of("amount", 5000, "method", "MPESA")).status()).isEqualTo(400);
        Res mp = post("/catering/" + cid + "/pay", owner, Map.of("amount", 5000, "method", "MPESA", "reference", "SHK1234ABC"));
        assertThat(mp.status()).as(mp.body().toString()).isEqualTo(200);
        assertThat(mp.body().get("balance").asDouble()).isEqualTo(10000);

        // The balance clears the bill, so every shilling is a payment on one bill.
        Res bal = post("/catering/" + cid + "/pay", owner, Map.of("amount", 10000, "method", "CASH"));
        assertThat(bal.status()).as(bal.body().toString()).isEqualTo(200);
        assertThat(bal.body().get("balance").asDouble()).isZero();
        assertThat(bal.body().get("paid").asDouble()).isEqualTo(25000);
        assertThat(bal.body().get("billNumber").asInt()).isPositive();
        assertThat(bal.body().get("billStatus").asText()).isEqualTo("PAID");

        assertThat(post("/catering/" + cid + "/status", owner, Map.of("status", "PREPARING")).body().get("status").asText()).isEqualTo("PREPARING");
        assertThat(post("/catering/" + cid + "/status", owner, Map.of("status", "DELIVERED")).body().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(get("/catering", owner).body().get("orders")).hasSize(1);

        // The customer's summary link needs no sign-in and shows no staff fields.
        JsonNode mine = get("/public/catering/" + s.at("/tenant/slug").asText() + "/o/" + bal.body().get("token").asText(), null).body();
        assertThat(mine.at("/order/total").asDouble()).isEqualTo(25000);
        assertThat(mine.at("/order").has("orderId")).isFalse();
    }

    @Test
    void cancellingAnOrderWithMoneyPaidNeedsAManager() {
        JsonNode s = signup();
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        String id = itemId(owner);
        post("/shifts/open", owner, Map.of("openingCash", 0));
        post("/shifts/open", cashier, Map.of("openingCash", 0));

        // No money yet: the cashier may cancel it outright.
        String free = post("/catering", cashier, atMenuPrice(id)).body().get("id").asText();
        assertThat(post("/catering/" + free + "/cancel", cashier, Map.of("reason", "Changed mind")).body().get("status").asText()).isEqualTo("CANCELLED");

        // With a deposit paid the cashier can only ask; the order stays until a manager answers.
        String cid = post("/catering", owner, order(id, Map.of("deposit", 5000))).body().get("id").asText();
        Res asked = post("/catering/" + cid + "/cancel", cashier, Map.of("reason", "Customer rang to cancel"));
        assertThat(asked.status()).as(asked.body().toString()).isEqualTo(200);
        assertThat(asked.body().get("askedManager").asBoolean()).isTrue();
        assertThat(asked.body().get("status").asText()).isEqualTo("BOOKED");
        assertThat(asked.body().get("paid").asDouble()).isEqualTo(5000);

        // The owner cancels it and the deposit goes back.
        Res done = post("/catering/" + cid + "/cancel", owner, Map.of("reason", "Customer rang to cancel"));
        assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
        assertThat(done.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(done.body().get("balance").asDouble()).isZero();
        assertThat(post("/catering/" + cid + "/pay", owner, Map.of("amount", 100, "method", "CASH")).status()).isEqualTo(409);
    }

    @Test
    void anotherBusinessCannotSeeOrTouchTheOrder() {
        String ownerA = token(signup());
        JsonNode sb = signup();
        String ownerB = token(sb);
        post("/shifts/open", ownerA, Map.of("openingCash", 0));
        String cid = post("/catering", ownerA, order(itemId(ownerA), Map.of("deposit", 1000))).body().get("id").asText();
        String publicToken = get("/catering", ownerA).body().at("/orders/0/token").asText();

        assertThat(get("/catering", ownerB).body().get("orders")).isEmpty();
        assertThat(post("/catering/" + cid + "/pay", ownerB, Map.of("amount", 100, "method", "CASH")).status()).isEqualTo(404);
        assertThat(post("/catering/" + cid + "/cancel", ownerB, Map.of("reason", "x")).status()).isEqualTo(404);
        assertThat(post("/catering/" + cid + "/status", ownerB, Map.of("status", "PREPARING")).status()).isEqualTo(404);
        // Their link only opens under their own business, and not with another business's menu item.
        assertThat(get("/public/catering/" + sb.at("/tenant/slug").asText() + "/o/" + publicToken, null).status()).isEqualTo(404);
        assertThat(post("/catering", ownerB, order(itemId(ownerA), Map.of())).status()).isEqualTo(404);
    }

    @Test
    void staffWithoutPermissionAreRefused() {
        JsonNode s = signup();
        String owner = token(s);
        String id = itemId(owner);
        String waiter = staff(owner, "WAITER");
        String cid = post("/catering", owner, order(id, Map.of())).body().get("id").asText();
        assertThat(post("/catering", waiter, order(id, Map.of())).status()).isEqualTo(403);
        assertThat(get("/catering", waiter).status()).isEqualTo(403);
        assertThat(post("/catering/" + cid + "/pay", waiter, Map.of("amount", 100, "method", "CASH")).status()).isEqualTo(403);
        assertThat(post("/catering/" + cid + "/cancel", waiter, Map.of("reason", "x")).status()).isEqualTo(403);
    }

    @Test
    void aChangedPriceNeedsAManagerOrTheirPin() {
        JsonNode s = signup();
        String owner = token(s);
        String id = itemId(owner);
        String cashier = staff(owner, "CASHIER");
        staff(owner, "MANAGER");
        Map<String, Object> cheaper = order(id, Map.of());
        assertThat(post("/catering", cashier, cheaper).status()).isEqualTo(403);
        Map<String, Object> approved = order(id, Map.of("approval", Map.of("userId", userId(owner, "MANAGER"), "pin", "1234")));
        Res ok = post("/catering", cashier, approved);
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(post("/catering", owner, cheaper).status()).isEqualTo(200);
    }

    @Test
    void aDealCannotChangeTheBillAndTheFinalPaymentClosesItExactly() {
        JsonNode s = signup();
        String owner = token(s);
        String id = itemId(owner);
        assertThat(post("/menu-tools/deals", owner, Map.of("name", "Buffet 1+1", "kind", "BOGO", "buy", 1, "get", 1,
                "items", List.of(Map.of("itemId", id)))).status()).isEqualTo(200);
        post("/shifts/open", owner, Map.of("openingCash", 0));
        Map<String, Object> o = atMenuPrice(id);
        o.put("people", 10);
        o.put("deposit", 1000);
        JsonNode placed = post("/catering", owner, o).body();
        double menu = item(owner, "Catering: buffet").get("price").asDouble();
        assertThat(placed.get("total").asDouble()).isEqualTo(menu * 10);
        assertThat(placed.get("balance").asDouble()).isEqualTo(menu * 10 - 1000);
        // The till cannot edit a catering bill.
        String bill = placed.get("orderId").asText();
        assertThat(post("/orders/" + bill + "/lines", owner, Map.of("lines", List.of(Map.of("itemId", id, "qty", 1)))).status()).isEqualTo(409);
        JsonNode done = post("/catering/" + placed.get("id").asText() + "/pay", owner, Map.of("amount", menu * 10 - 1000, "method", "CASH")).body();
        assertThat(done.get("balance").asDouble()).isZero();
        assertThat(done.get("billStatus").asText()).isEqualTo("PAID");
        assertThat(get("/orders/" + bill, owner).body().get("total").asDouble()).isEqualTo(menu * 10);
    }

    @Test
    void noPaymentsWhileACancelRequestWaitsAndCodesAreUsedOnce() {
        JsonNode s = signup();
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        String id = itemId(owner);
        post("/shifts/open", owner, Map.of("openingCash", 0));
        post("/shifts/open", cashier, Map.of("openingCash", 0));
        String cid = post("/catering", owner, order(id, Map.of("deposit", 2000))).body().get("id").asText();
        assertThat(post("/catering/" + cid + "/pay", owner, Map.of("amount", 500, "method", "MPESA", "reference", "QWE123RTY")).status()).isEqualTo(200);
        assertThat(post("/catering/" + cid + "/pay", owner, Map.of("amount", 500, "method", "MPESA", "reference", "qwe123rty")).status()).isEqualTo(409);
        // A deposit by M-Pesa needs its real code.
        assertThat(post("/catering", owner, order(id, Map.of("deposit", 500, "depositMethod", "MPESA"))).status()).isEqualTo(400);
        assertThat(post("/catering/" + cid + "/cancel", cashier, Map.of("reason", "Rang to cancel")).body().get("askedManager").asBoolean()).isTrue();
        assertThat(post("/catering/" + cid + "/pay", cashier, Map.of("amount", 100, "method", "CASH")).status()).isEqualTo(409);
    }

    @Test
    void editingKeepsTheTimeAndTheCustomerLinkHidesStaffNotes() {
        JsonNode s = signup();
        String owner = token(s);
        JsonNode placed = post("/catering", owner, order(itemId(owner), Map.of())).body();
        String cid = placed.get("id").asText();
        Res moved = post("/catering/" + cid, owner, Map.of("date", LocalDate.now(ZoneId.of("Africa/Nairobi")).plusDays(9).toString()));
        assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);
        assertThat(moved.body().get("time").asText()).isEqualTo(placed.get("time").asText());
        assertThat(post("/catering/" + cid, owner, Map.of("date", "next friday")).status()).isEqualTo(400);
        JsonNode mine = get("/public/catering/" + s.at("/tenant/slug").asText() + "/o/" + placed.get("token").asText(), null).body().get("order");
        assertThat(mine.has("notes")).isFalse();
        assertThat(mine.has("createdAt")).isFalse();
        assertThat(mine.at("/lines/0").has("itemId")).isFalse();
    }
}
