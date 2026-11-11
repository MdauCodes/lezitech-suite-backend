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

/** Package D: diners pay from their phone, alone or split across phones, with tips; pay-first orders wait for payment. */
class TablePaymentsTest extends IntegrationTest {

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
        return new R(owner, slug, tables, dishes);
    }

    private Res order(R r, int table) {
        return post("/public/orders/" + r.slug(), null, Map.of("tableId", r.tables().get(table),
                "lines", List.of(Map.of("itemId", r.dishes().get(0).get("id").asText(), "qty", 2),
                        Map.of("itemId", r.dishes().get(1).get("id").asText(), "qty", 1))));
    }

    private JsonNode track(R r, String token) {
        return get("/public/track/" + r.slug() + "/" + token, null).body();
    }

    private JsonNode waitFor(R r, String token, Predicate<JsonNode> done) throws InterruptedException {
        JsonNode t = null;
        for (int i = 0; i < 80; i++) {
            t = track(r, token);
            if (done.test(t)) {
                return t;
            }
            Thread.sleep(150);
        }
        return t;
    }

    /** The kitchen finishes everything on the order. */
    private void cookAll(R r, int number) {
        for (JsonNode t : get("/kitchen", r.owner()).body()) {
            if (t.get("number").asInt() == number) {
                post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", r.owner(), Map.of());
                post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", r.owner(), Map.of());
            }
        }
    }

    private String path(R r, String token, String action) {
        return "/public/track/" + r.slug() + "/" + token + "/" + action;
    }

    @Test
    void theDinerPaysFromTheirPhoneWithATipOnceTheFoodIsReady() throws Exception {
        R r = restaurant();
        String waiter = staff(r.owner(), "WAITER");
        Res placed = order(r, 0);
        String token = placed.at("/token").asText();
        double total = placed.at("/total").asDouble();

        assertThat(track(r, token).at("/payment/open").asBoolean()).as("still cooking").isFalse();
        assertThat(post(path(r, token, "pay"), null, Map.of("phone", "0712345678")).status()).isEqualTo(409);
        assertThat(post(path(r, token, "rating"), null, Map.of("stars", 5)).status()).isEqualTo(409);

        cookAll(r, placed.at("/number").asInt());
        JsonNode ready = track(r, token);
        assertThat(ready.at("/payment/open").asBoolean()).isTrue();
        assertThat(ready.at("/payment/due").asDouble()).isEqualTo(total);
        assertThat(ready.get("tipPresets").toString()).isEqualTo("[300,500,1000,3000]");

        assertThat(post(path(r, token, "pay"), null, Map.of("phone", "0712345678", "tip", 300)).status()).isEqualTo(200);
        // The tip is recorded just after the payment lands.
        JsonNode paid = waitFor(r, token, t -> "PAID".equals(t.at("/order/status").asText()) && t.at("/order/tip").asDouble() > 0);
        assertThat(paid.at("/order/status").asText()).isEqualTo("PAID");
        assertThat(paid.at("/order/paid").asDouble()).as("the tip isn't part of the sale").isEqualTo(total);
        assertThat(paid.at("/order/tip").asDouble()).isEqualTo(300);

        // The food still has to reach the table: the waiter sees it paid and ready to serve.
        JsonNode mine = get("/waiter/tables", waiter).body();
        assertThat(mine.get("orders")).hasSize(1);
        assertThat(mine.at("/orders/0/ready").asInt()).isEqualTo(2);
        assertThat(get("/reports/tips", r.owner()).body().at("/totals/amount").asDouble()).isEqualTo(300);
        assertThat(get("/reports/tips", r.owner()).body().at("/totals/kitchen").asDouble()).isEqualTo(135);

        assertThat(post(path(r, token, "rating"), null, Map.of("stars", 5, "comment", "Lovely")).status()).isEqualTo(200);
        assertThat(post(path(r, token, "rating"), null, Map.of("stars", 4)).status()).isEqualTo(409);
    }

    @Test
    void aSplitPromptsOnePhoneAtATimeAndRetriesAFailedPhoneOnce() throws Exception {
        R r = restaurant();
        Res placed = order(r, 0);
        String token = placed.at("/token").asText();
        cookAll(r, placed.at("/number").asInt());
        double due = track(r, token).at("/payment/due").asDouble();
        long third = Math.round(Math.floor(due / 3));
        long first = Math.round(due - third * 2);

        // Shares must add up exactly.
        assertThat(post(path(r, token, "split"), null, Map.of("payers", List.of(
                Map.of("phone", "0711000001", "amount", 1), Map.of("phone", "0711000002", "amount", 1)))).status()).isEqualTo(400);

        // The middle phone always fails (the simulator fails numbers ending 9999).
        Res split = post(path(r, token, "split"), null, Map.of("payers", List.of(
                Map.of("phone", "0711000001", "amount", first),
                Map.of("phone", "0711119999", "amount", third),
                Map.of("phone", "0711000003", "amount", third))));
        assertThat(split.status()).as(split.body().toString()).isEqualTo(200);
        JsonNode settled = waitFor(r, token, t -> {
            for (JsonNode s : t.at("/payment/splits")) {
                if (List.of("WAITING", "PROMPTED").contains(s.get("status").asText())) {
                    return false;
                }
            }
            return true;
        });
        List<String> statuses = new ArrayList<>();
        settled.at("/payment/splits").forEach(s -> statuses.add(s.get("phone").asText().replace("••• ", "") + ":" + s.get("status").asText()));
        assertThat(statuses).containsExactlyInAnyOrder("001:PAID", "999:FAILED", "003:PAID");
        assertThat(settled.at("/order/status").asText()).isEqualTo("OPEN");
        assertThat(settled.at("/payment/due").asDouble()).isEqualTo(third);

        // The last share is paid by someone else.
        assertThat(post(path(r, token, "pay"), null, Map.of("phone", "0722000004")).status()).isEqualTo(200);
        assertThat(waitFor(r, token, t -> "PAID".equals(t.at("/order/status").asText())).at("/order/status").asText()).isEqualTo("PAID");
    }

    @Test
    void aPayFirstOrderGoesToTheKitchenOncePaid() throws Exception {
        R r = restaurant();
        call("PUT", "/settings", r.owner(), Map.of("settings", Map.of("ordering", Map.of("payWhen", "FIRST"))));
        Res placed = order(r, 0);
        String token = placed.at("/token").asText();
        assertThat(track(r, token).at("/payment/open").asBoolean()).as("pay first: open straight away").isTrue();
        assertThat(get("/kitchen", r.owner()).body().toString()).doesNotContain("\"number\":" + placed.at("/number").asInt() + ",");

        post(path(r, token, "pay"), null, Map.of("phone", "0712345678"));
        JsonNode paid = waitFor(r, token, t -> "RECEIVED".equals(t.at("/order/stage").asText()));
        assertThat(paid.at("/order/status").asText()).isEqualTo("PAID");
        assertThat(paid.at("/order/stage").asText()).isEqualTo("RECEIVED");
        boolean inKitchen = false;
        for (JsonNode t : get("/kitchen", r.owner()).body()) {
            inKitchen |= t.get("number").asInt() == placed.at("/number").asInt();
        }
        assertThat(inKitchen).isTrue();
    }

    @Test
    void onlyTheOrdersOwnLinkCanPayAndPromptsAreLimited() {
        R r = restaurant();
        Res placed = order(r, 0);
        cookAll(r, placed.at("/number").asInt());
        assertThat(post("/public/track/" + r.slug() + "/00000000-0000-0000-0000-000000000000/pay", null, Map.of("phone", "0712345678")).status()).isEqualTo(404);
        String token = placed.at("/token").asText();
        assertThat(post(path(r, token, "pay"), null, Map.of("phone", "12")).status()).isEqualTo(400);
        assertThat(post(path(r, token, "pay"), null, Map.of("phone", "0712345678")).status()).isEqualTo(200);
        // A second prompt while the first is on its way is refused.
        assertThat(post(path(r, token, "pay"), null, Map.of("phone", "0712345678")).status()).isIn(409, 429);
    }
}
