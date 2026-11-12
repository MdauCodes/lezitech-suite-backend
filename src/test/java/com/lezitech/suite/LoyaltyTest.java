package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.publicapi.PublicController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Package G: loyalty points on the owner's rules, earned when a bill is paid, used at the till, every move recorded. */
class LoyaltyTest extends IntegrationTest {

    private static Map<String, Object> rules() {
        return Map.of("loyalty", Map.of("on", true, "pointsPer100", 5, "pointValue", 1, "minToRedeem", 10, "expiryMonths", 0));
    }

    private double points(String token, String customer) {
        return get("/customers/" + customer + "/loyalty", token).at("/points").asDouble();
    }

    @Test
    void pointsAreEarnedUsedRefundedAndAdjustedOnTheOwnersRules() {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        String c = post("/customers", owner, Map.of("name", "Wanjiku", "phone", "0711222333")).body().get("id").asText();

        // Off by default: a paid sale earns nothing.
        post("/orders", owner, Map.of("customerId", c, "lines", line(sugar, 2), "payments", List.of(Map.of("method", "CASH", "amount", 380))));
        JsonNode off = get("/customers/" + c + "/loyalty", owner).body();
        assertThat(off.get("on").asBoolean()).isFalse();
        assertThat(off.get("points").asDouble()).isZero();

        assertThat(call("PUT", "/settings", owner, Map.of("settings", rules())).status()).isEqualTo(200);

        // 380 paid at 5 points per 100 = 19 points.
        post("/orders", owner, Map.of("customerId", c, "lines", line(sugar, 2), "payments", List.of(Map.of("method", "CASH", "amount", 380))));
        assertThat(points(owner, c)).isEqualTo(19);

        // Using points: at least the minimum, no more than they have, recorded as a POINTS payment.
        String bill = post("/orders", owner, Map.of("customerId", c, "lines", line(sugar, 1))).body().get("id").asText();
        assertThat(post("/orders/" + bill + "/loyalty/redeem", owner, Map.of("points", 5)).status()).isEqualTo(400);
        assertThat(post("/orders/" + bill + "/loyalty/redeem", owner, Map.of("points", 25)).status()).isEqualTo(400);
        Res used = post("/orders/" + bill + "/loyalty/redeem", owner, Map.of("points", 10));
        assertThat(used.status()).as(used.body().toString()).isEqualTo(200);
        assertThat(used.at("/paid").asDouble()).isEqualTo(10);
        assertThat(points(owner, c)).isEqualTo(9);

        // The till can't type in a POINTS payment by itself.
        assertThat(post("/orders/" + bill + "/pay", owner, Map.of("payments", List.of(Map.of("method", "POINTS", "amount", 50)))).status()).isEqualTo(400);

        // The rest in cash closes the bill; points are earned on the 180 paid in money, not on the 10 paid with points.
        Res paid = post("/orders/" + bill + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", 180))));
        assertThat(paid.at("/status").asText()).isEqualTo("PAID");
        assertThat(points(owner, c)).isEqualTo(18);

        // A refund takes back the 9 earned and gives back the 10 used; the points part goes back as points.
        Res refund = post("/orders/" + bill + "/refund", owner, Map.of("reason", "Wrong sugar", "method", "CASH"));
        assertThat(refund.status()).as(refund.body().toString()).isEqualTo(200);
        assertThat(refund.body().get("payments").toString()).contains("\"method\":\"POINTS\",\"amount\":-10");
        assertThat(points(owner, c)).isEqualTo(19);

        // Only owners and managers adjust by hand, with a reason, and never below zero.
        String cashier = staff(owner, "CASHIER");
        assertThat(post("/customers/" + c + "/loyalty/adjust", cashier, Map.of("points", 5, "note", "x")).status()).isEqualTo(403);
        assertThat(post("/customers/" + c + "/loyalty/adjust", owner, Map.of("points", 5)).status()).isEqualTo(400);
        assertThat(post("/customers/" + c + "/loyalty/adjust", owner, Map.of("points", -100, "note", "oops")).status()).isEqualTo(400);
        assertThat(post("/customers/" + c + "/loyalty/adjust", owner, Map.of("points", 5, "note", "Birthday")).at("/points").asDouble()).isEqualTo(24);

        // Every move is in the history, newest first.
        JsonNode moves = get("/customers/" + c + "/loyalty", owner).at("/moves");
        assertThat(moves).hasSize(6);
        assertThat(moves.get(0).get("kind").asText()).isEqualTo("ADJUST");
        assertThat(moves.get(0).get("note").asText()).isEqualTo("Birthday");
    }

    @Test
    void aPhoneOrderEarnsPointsForTheNumberItWasPlacedWith() {
        PublicController.PER_TABLE.clear();
        PublicController.PER_CLIENT.clear();
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        call("PUT", "/settings", owner, Map.of("settings", rules()));
        String table = get("/floors", owner).body().get(0).get("tables").get(0).get("id").asText();
        String dish = null;
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (dish == null && !i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                dish = i.get("id").asText();
            }
        }
        JsonNode placed = post("/public/orders/" + slug, null, Map.of("tableId", table, "customerLabel", "Otieno", "phone", "0722 333 444",
                "lines", List.of(Map.of("itemId", dish, "qty", 2)))).body();
        String token = placed.get("token").asText();
        assertThat(get("/public/track/" + slug + "/" + token, null).body().get("loyalty").isNull()).as("no customer until paid").isTrue();

        String orderId = null;
        for (JsonNode o : get("/orders?status=OPEN&kind=SALE", owner).body()) {
            if (o.get("number").asInt() == placed.get("number").asInt()) {
                orderId = o.get("id").asText();
            }
        }
        double total = placed.get("total").asDouble();
        assertThat(post("/orders/" + orderId + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", total)))).status()).isEqualTo(200);

        JsonNode loyalty = get("/public/track/" + slug + "/" + token, null).body().get("loyalty");
        double expected = Math.floor(total * 5) / 100;
        assertThat(loyalty.get("earned").asDouble()).isEqualTo(expected);
        assertThat(loyalty.get("points").asDouble()).isEqualTo(expected);

        // The customer was added from the phone number and name.
        JsonNode customers = get("/customers?q=Otieno", owner).body();
        assertThat(customers).hasSize(1);
        assertThat(customers.get(0).get("loyaltyPoints").asDouble()).isEqualTo(expected);
    }
}
