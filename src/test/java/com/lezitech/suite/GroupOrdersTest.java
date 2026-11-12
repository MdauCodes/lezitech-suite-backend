package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.publicapi.PublicController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Package E: one order across several tables, with one combined bill paid from the phone. */
class GroupOrdersTest extends IntegrationTest {

    @BeforeEach
    void freshLimits() {
        PublicController.PER_TABLE.clear();
        PublicController.PER_CLIENT.clear();
        PublicController.PER_ORDER_PAY.clear();
    }

    record R(String owner, String slug, List<String> tables, List<JsonNode> dishes) {
    }

    private R restaurant() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        List<String> tables = new ArrayList<>();
        get("/floors", owner).body().get(0).get("tables").forEach(t -> tables.add(t.get("id").asText()));
        List<JsonNode> dishes = new ArrayList<>();
        get("/public/menu/" + slug, null).body().get("items").forEach(i -> {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                dishes.add(i);
            }
        });
        assertThat(tables.size()).as("sample restaurant has several tables").isGreaterThanOrEqualTo(2);
        return new R(owner, slug, tables, dishes);
    }

    private Map<String, Object> part(R r, int table, int dish, int qty) {
        return Map.of("tableId", r.tables().get(table), "lines", List.of(Map.of("itemId", r.dishes().get(dish).get("id").asText(), "qty", qty)));
    }

    private Res group(R r) {
        return post("/public/orders/" + r.slug() + "/group", null, Map.of("customerLabel", "Wanjiru's party",
                "tables", List.of(part(r, 0, 0, 2), part(r, 1, 1, 3))));
    }

    private JsonNode track(R r, String batch) {
        return get("/public/group/" + r.slug() + "/" + batch, null).body();
    }

    private JsonNode waitFor(R r, String batch, Predicate<JsonNode> done) throws InterruptedException {
        JsonNode t = null;
        for (int i = 0; i < 80; i++) {
            t = track(r, batch);
            if (done.test(t)) {
                return t;
            }
            Thread.sleep(150);
        }
        return t;
    }

    private static boolean allPaid(JsonNode g) {
        for (JsonNode t : g.get("tables")) {
            if (!"PAID".equals(t.get("status").asText())) {
                return false;
            }
        }
        return true;
    }

    private List<JsonNode> tickets(R r, Res placed) {
        List<Integer> numbers = new ArrayList<>();
        placed.body().get("orders").forEach(o -> numbers.add(o.get("number").asInt()));
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode t : get("/kitchen", r.owner()).body()) {
            if (numbers.contains(t.get("number").asInt())) {
                out.add(t);
            }
        }
        return out;
    }

    private void cookAll(R r, Res placed) {
        for (JsonNode t : tickets(r, placed)) {
            post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", r.owner(), Map.of());
            post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", r.owner(), Map.of());
        }
    }

    private String path(R r, String batch, String action) {
        return "/public/group/" + r.slug() + "/" + batch + "/" + action;
    }

    @Test
    void twoTablesGetTwoBillsSharingOneGroupAndBothReachTheKitchen() {
        R r = restaurant();
        assertThat(get("/public/tables/" + r.slug(), null).body().toString()).contains(r.tables().get(0), r.tables().get(1));

        Res placed = group(r);
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        String batch = placed.at("/batchId").asText();
        assertThat(placed.body().get("orders")).hasSize(2);
        double expected = r.dishes().get(0).get("price").asDouble() * 2 + r.dishes().get(1).get("price").asDouble() * 3;
        assertThat(placed.at("/total").asDouble()).isEqualTo(expected);

        List<JsonNode> tickets = tickets(r, placed);
        assertThat(tickets).hasSize(2);
        tickets.forEach(t -> assertThat(t.get("groupWith").isNull()).as("kitchen sees it's a group").isFalse());

        JsonNode g = track(r, batch);
        assertThat(g.get("tables")).hasSize(2);
        assertThat(g.at("/payment/due").asDouble()).isEqualTo(expected);
        assertThat(g.at("/payment/open").asBoolean()).as("still cooking").isFalse();
        assertThat(post(path(r, batch, "pay"), null, Map.of("phone", "0712345678")).status()).isEqualTo(409);
    }

    @Test
    void oneCombinedPaymentClosesEveryTableWithTheTipRecordedOnce() throws Exception {
        R r = restaurant();
        Res placed = group(r);
        String batch = placed.at("/batchId").asText();
        cookAll(r, placed);
        JsonNode ready = track(r, batch);
        assertThat(ready.at("/payment/open").asBoolean()).isTrue();

        assertThat(post(path(r, batch, "pay"), null, Map.of("phone", "0712345678", "tip", 500)).status()).isEqualTo(200);
        JsonNode paid = waitFor(r, batch, g -> allPaid(g) && g.at("/tables/0/tip").asDouble() > 0);
        for (JsonNode t : paid.get("tables")) {
            assertThat(t.get("status").asText()).isEqualTo("PAID");
            assertThat(t.get("paid").asDouble()).as("each table's bill is paid exactly").isEqualTo(t.get("total").asDouble());
        }
        assertThat(paid.at("/tables/0/tip").asDouble() + paid.at("/tables/1/tip").asDouble()).isEqualTo(500);
        assertThat(get("/reports/tips", r.owner()).body().at("/totals/amount").asDouble()).isEqualTo(500);
        assertThat(paid.at("/payment/due").asDouble()).isZero();

        assertThat(post(path(r, batch, "rating"), null, Map.of("stars", 5)).status()).isEqualTo(200);
        assertThat(post(path(r, batch, "rating"), null, Map.of("stars", 4)).status()).isEqualTo(409);
    }

    @Test
    void aSplitAcrossTwoPhonesIsSpreadOverTheTables() throws Exception {
        R r = restaurant();
        Res placed = group(r);
        String batch = placed.at("/batchId").asText();
        cookAll(r, placed);
        long due = Math.round(track(r, batch).at("/payment/due").asDouble());
        long half = due / 2;

        Res split = post(path(r, batch, "split"), null, Map.of("payers", List.of(
                Map.of("phone", "0711000001", "amount", due - half), Map.of("phone", "0711000002", "amount", half))));
        assertThat(split.status()).as(split.body().toString()).isEqualTo(200);
        JsonNode paid = waitFor(r, batch, GroupOrdersTest::allPaid);
        for (JsonNode t : paid.get("tables")) {
            assertThat(t.get("status").asText()).isEqualTo("PAID");
            assertThat(t.get("paid").asDouble()).isEqualTo(t.get("total").asDouble());
        }
        List<String> statuses = new ArrayList<>();
        paid.at("/payment/splits").forEach(s -> statuses.add(s.get("status").asText()));
        assertThat(statuses).containsExactly("PAID", "PAID");
    }

    @Test
    void aPayFirstGroupGoesToTheKitchenOncePaid() throws Exception {
        R r = restaurant();
        call("PUT", "/settings", r.owner(), Map.of("settings", Map.of("ordering", Map.of("payWhen", "FIRST"))));
        Res placed = group(r);
        String batch = placed.at("/batchId").asText();
        assertThat(placed.at("/heldForPayment").asBoolean()).isTrue();
        assertThat(tickets(r, placed)).isEmpty();
        assertThat(track(r, batch).at("/payment/open").asBoolean()).isTrue();

        post(path(r, batch, "pay"), null, Map.of("phone", "0712345678"));
        waitFor(r, batch, g -> allPaid(g) && "RECEIVED".equals(g.at("/tables/1/stage").asText()));
        assertThat(tickets(r, placed)).hasSize(2);
    }

    @Test
    void aGroupTableGetsItsOwnBillEvenIfTheTableHasOneOpen() {
        R r = restaurant();
        Res single = post("/public/orders/" + r.slug(), null, part(r, 0, 0, 1));
        assertThat(single.status()).isEqualTo(200);
        Res placed = group(r);
        assertThat(placed.status()).isEqualTo(200);
        int singleNumber = single.at("/number").asInt();
        placed.body().get("orders").forEach(o -> assertThat(o.get("number").asInt()).isNotEqualTo(singleNumber));
        // The earlier bill is untouched: still just its one dish.
        JsonNode t = get("/public/track/" + r.slug() + "/" + single.at("/token").asText(), null).body();
        assertThat(t.at("/order/lines")).hasSize(1);
    }

    @Test
    void groupsNeedTwoOfTheRestaurantsOwnTablesAndUnknownGroupsAreHidden() {
        R r = restaurant();
        R other = restaurant();
        String url = "/public/orders/" + r.slug() + "/group";
        assertThat(post(url, null, Map.of("tables", List.of(part(r, 0, 0, 1)))).status()).isEqualTo(400);
        assertThat(post(url, null, Map.of("tables", List.of(part(r, 0, 0, 1), part(r, 0, 1, 1)))).status()).isEqualTo(400);
        Res foreign = post(url, null, Map.of("tables", List.of(part(r, 0, 0, 1),
                Map.of("tableId", other.tables().get(0), "lines", List.of(Map.of("itemId", r.dishes().get(0).get("id").asText(), "qty", 1))))));
        assertThat(foreign.status()).isEqualTo(404);
        // All or nothing: the first table's part wasn't placed either.
        assertThat(get("/kitchen", r.owner()).body()).isEmpty();

        String batch = group(r).at("/batchId").asText();
        assertThat(get("/public/group/" + other.slug() + "/" + batch, null).status()).isEqualTo(404);
        assertThat(get("/public/group/" + r.slug() + "/00000000-0000-0000-0000-000000000000", null).status()).isEqualTo(404);
    }
}
