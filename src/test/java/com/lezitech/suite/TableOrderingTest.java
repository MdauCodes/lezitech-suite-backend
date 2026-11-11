package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.publicapi.PublicController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Package B: diners order from the table QR; prices come from the menu, the kitchen sees it, a waiter gets it. */
class TableOrderingTest extends IntegrationTest {

    @BeforeEach
    void freshLimits() {
        PublicController.PER_TABLE.clear();
        PublicController.PER_CLIENT.clear();
    }

    record Place(String token, String slug, String table, String item, double price, String tenant) {
    }

    private Place restaurant() {
        JsonNode owner = signup("restaurant");
        String token = token(owner);
        String slug = owner.at("/tenant/slug").asText();
        String table = get("/floors", token).body().get(0).at("/tables/0/id").asText();
        JsonNode menu = get("/public/menu/" + slug, null).body();
        JsonNode dish = null;
        for (JsonNode i : menu.get("items")) {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty()) {
                dish = i;
                break;
            }
        }
        return new Place(token, slug, table, dish.get("id").asText(), dish.get("price").asDouble(), owner.at("/tenant/id").asText());
    }

    private Res order(Place p, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("tableId", p.table(),
                "lines", List.of(Map.of("itemId", p.item(), "qty", 2, "note", "no onions"))));
        body.putAll(extra);
        return post("/public/orders/" + p.slug(), null, body);
    }

    @Test
    void aDinerOrdersFromTheTableAndTheKitchenSeesItAtOnce() {
        Place p = restaurant();
        JsonNode menu = get("/public/menu/" + p.slug() + "?table=" + p.table(), null).body();
        assertThat(menu.at("/ordering/open").asBoolean()).isTrue();
        assertThat(menu.at("/table/label").asText()).isNotBlank();

        Res r = order(p, Map.of("customerLabel", "Brian", "phone", "0712345678"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/total").asDouble()).isEqualTo(p.price() * 2);
        assertThat(r.at("/heldForPayment").asBoolean()).isFalse();

        JsonNode track = get("/public" + r.at("/trackPath").asText(), null).body().get("order");
        assertThat(track.get("stage").asText()).isEqualTo("RECEIVED");
        assertThat(track.get("customerLabel").asText()).isEqualTo("Brian");

        JsonNode kitchen = get("/kitchen", p.token()).body();
        assertThat(kitchen.toString()).contains("no onions");
    }

    @Test
    void thePhoneCannotSetPricesOrOrderWhatIsFinished() {
        Place p = restaurant();
        // A price, a discount or a made-up item sent from the phone is ignored or refused.
        Res cheap = post("/public/orders/" + p.slug(), null, Map.of("tableId", p.table(),
                "lines", List.of(Map.of("itemId", p.item(), "qty", 1, "unitPrice", 1, "discount", 50))));
        assertThat(cheap.status()).isEqualTo(200);
        assertThat(cheap.at("/total").asDouble()).isEqualTo(p.price());
        assertThat(post("/public/orders/" + p.slug(), null, Map.of("tableId", p.table(),
                "lines", List.of(Map.of("name", "Free lunch", "unitPrice", 0, "qty", 1)))).status()).isEqualTo(400);
        assertThat(post("/public/orders/" + p.slug(), null, Map.of("tableId", p.table(),
                "lines", List.of(Map.of("itemId", p.item(), "qty", 500)))).status()).isEqualTo(400);

        // Marked finished (the kitchen screen gets this button in package C): sold out on the menu and refused.
        markUnavailable(p, p.item());
        assertThat(get("/public/menu/" + p.slug(), null).body().get("items").toString())
                .contains("\"id\":\"" + p.item() + "\"");
        assertThat(order(p, Map.of()).status()).isEqualTo(400);
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager txm;

    private void markUnavailable(Place p, String itemId) {
        com.lezitech.suite.tenant.TenantContext.runAs(java.util.UUID.fromString(p.tenant()), null, () -> {
            new org.springframework.transaction.support.TransactionTemplate(txm).executeWithoutResult(st ->
                    jdbc.update("UPDATE items SET unavailable_until = 'infinity' WHERE id = ?::uuid", itemId));
            return null;
        });
    }

    @Test
    void aSecondRoundAtTheSameTableGoesOnTheSameBill() {
        Place p = restaurant();
        Res first = order(p, Map.of());
        Res second = order(p, Map.of());
        assertThat(second.at("/addedToOpenBill").asBoolean()).isTrue();
        assertThat(second.at("/number").asInt()).isEqualTo(first.at("/number").asInt());
        assertThat(second.at("/total").asDouble()).isEqualTo(p.price() * 4);
    }

    @Test
    void theLeastBusyWaiterGetsTheOrder() {
        Place p = restaurant();
        String busy = staff(p.token(), "WAITER");
        JsonNode floors = get("/floors", p.token()).body();
        String other = floors.get(0).at("/tables/1/id").asText();
        // The first waiter takes a table themselves.
        assertThat(post("/orders", busy, Map.of("channel", "DINE_IN", "tableId", other,
                "lines", List.of(Map.of("itemId", p.item(), "qty", 3)))).status()).isEqualTo(200);
        String free = staff(p.token(), "WAITER");
        String freeId = get("/auth/me", free).body().at("/user/id").asText();

        String token = order(p, Map.of()).at("/token").asText();
        String assigned = com.lezitech.suite.tenant.TenantContext.runAs(java.util.UUID.fromString(p.tenant()), null, () ->
                new org.springframework.transaction.support.TransactionTemplate(txm).execute(st ->
                        jdbc.queryForObject("SELECT assigned_to::text FROM orders WHERE public_token = ?::uuid", String.class, token)));
        assertThat(assigned).isEqualTo(freeId);
    }

    @Test
    void payFirstRestaurantsHoldTheOrderAndTakeawayGetsAPickupCode() {
        Place p = restaurant();
        assertThat(call("PUT", "/settings", p.token(), Map.of("settings", Map.of("ordering", Map.of("payWhen", "FIRST")))).status()).isEqualTo(200);
        Res held = order(p, Map.of());
        assertThat(held.at("/heldForPayment").asBoolean()).isTrue();
        assertThat(get("/public" + held.at("/trackPath").asText(), null).body().at("/order/stage").asText()).isEqualTo("OPEN");
        assertThat(get("/kitchen", p.token()).body().toString()).doesNotContain("no onions");

        Res takeaway = post("/public/orders/" + p.slug(), null, Map.of("lines", List.of(Map.of("itemId", p.item(), "qty", 1))));
        assertThat(takeaway.status()).isEqualTo(200);
        assertThat(takeaway.at("/pickupCode").asText()).matches("\\d{4}");
        assertThat(takeaway.at("/table").isNull()).isTrue();
    }

    @Test
    void ordersStopWhenTheRestaurantSwitchesThemOffOrTooManyArrive() {
        Place p = restaurant();
        for (int i = 0; i < 5; i++) {
            assertThat(order(p, Map.of()).status()).isEqualTo(200);
        }
        assertThat(order(p, Map.of()).status()).isEqualTo(429);

        Place q = restaurant();
        assertThat(call("PUT", "/settings", q.token(), Map.of("settings", Map.of("ordering", Map.of("selfOrder", false)))).status()).isEqualTo(200);
        assertThat(get("/public/menu/" + q.slug(), null).body().at("/ordering/open").asBoolean()).isFalse();
        assertThat(order(q, Map.of()).status()).isEqualTo(403);
        // A table from another restaurant isn't found.
        assertThat(post("/public/orders/" + p.slug(), null, Map.of("tableId", q.table(),
                "lines", List.of(Map.of("itemId", p.item(), "qty", 1)))).status()).isEqualTo(404);
    }
}
