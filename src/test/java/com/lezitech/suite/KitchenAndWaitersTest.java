package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.publicapi.PublicController;
import com.lezitech.suite.support.IntegrationTest;
import com.lezitech.suite.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Package C: estimates, finished dishes, substitutes the diner answers, and waiters' own tables. */
class KitchenAndWaitersTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txm;

    @BeforeEach
    void freshLimits() {
        PublicController.PER_TABLE.clear();
        PublicController.PER_CLIENT.clear();
    }

    record R(String owner, String slug, String tenant, List<String> tables, List<JsonNode> dishes) {
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
        return new R(owner, slug, s.at("/tenant/id").asText(), tables, dishes);
    }

    private String dish(R r, int i) {
        return r.dishes().get(i).get("id").asText();
    }

    private Res order(R r, int table, String... itemIds) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String id : itemIds) {
            lines.add(Map.of("itemId", id, "qty", 1));
        }
        return post("/public/orders/" + r.slug(), null, Map.of("tableId", r.tables().get(table), "lines", lines));
    }

    private JsonNode track(R r, String token) {
        return get("/public/track/" + r.slug() + "/" + token, null).body().get("order");
    }

    private JsonNode ticket(R r, int number) {
        for (JsonNode t : get("/kitchen", r.owner()).body()) {
            if (t.get("number").asInt() == number) {
                return t;
            }
        }
        throw new AssertionError("No ticket #" + number);
    }

    private void sql(R r, String statement, Object... args) {
        TenantContext.runAs(UUID.fromString(r.tenant()), null, () -> {
            new TransactionTemplate(txm).executeWithoutResult(s -> jdbc.update(statement, args));
            return null;
        });
    }

    @Test
    void theKitchenSetsAnEstimateAndPastCookingTimesSuggestOne() {
        R r = restaurant();
        Res placed = order(r, 0, dish(r, 0));
        int number = placed.at("/number").asInt();
        assertThat(track(r, placed.at("/token").asText()).get("estimatedReadyAt").isNull()).as("nothing known yet").isTrue();

        String orderId = ticket(r, number).get("orderId").asText();
        assertThat(post("/kitchen/orders/" + orderId + "/estimate", r.owner(), Map.of("minutes", 15)).status()).isEqualTo(200);
        assertThat(track(r, placed.at("/token").asText()).get("estimatedReadyAt").isNull()).isFalse();
        assertThat(post("/kitchen/orders/" + orderId + "/estimate", r.owner(), Map.of("minutes", 0)).status()).isEqualTo(400);

        // With cooking minutes set on the dish, the next order gets an estimate by itself.
        sql(r, "UPDATE items SET prep_minutes = 12 WHERE id = ?::uuid", dish(r, 1));
        Res next = order(r, 1, dish(r, 1));
        assertThat(track(r, next.at("/token").asText()).get("estimatedReadyAt").isNull()).isFalse();
        assertThat(ticket(r, next.at("/number").asInt()).get("suggestedMinutes").asInt()).isEqualTo(12);

        // Moving a dish to ready records when, for future averages.
        String line = ticket(r, number).at("/lines/0/id").asText();
        post("/kitchen/lines/" + line, r.owner(), Map.of("status", "READY"));
        sql(r, "UPDATE order_lines SET sent_at = ready_at - interval '9 minutes' WHERE id = ?::uuid", line);
        Long withTime = TenantContext.runAs(UUID.fromString(r.tenant()), null, () -> new TransactionTemplate(txm).execute(s ->
                jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE id = ?::uuid AND ready_at IS NOT NULL", Long.class, line)));
        assertThat(withTime).isEqualTo(1);
    }

    @Test
    void aFinishedDishShowsAsFinishedAndCantBeOrderedUntilItsBackOn() {
        R r = restaurant();
        String id = dish(r, 0);
        assertThat(post("/kitchen/items/" + id + "/availability", r.owner(), Map.of("until", "RESTOCK")).status()).isEqualTo(200);
        assertThat(get("/kitchen/finished", r.owner()).body().toString()).contains(id);
        assertThat(order(r, 0, id).status()).isEqualTo(400);
        boolean soldOut = false;
        for (JsonNode i : get("/public/menu/" + r.slug(), null).body().get("items")) {
            if (i.get("id").asText().equals(id)) {
                soldOut = i.get("soldOut").asBoolean();
            }
        }
        assertThat(soldOut).isTrue();
        assertThat(post("/kitchen/items/" + id + "/availability", r.owner(), Map.of("until", "NOW")).status()).isEqualTo(200);
        assertThat(order(r, 0, id).status()).isEqualTo(200);
        // Kitchen staff can do it; nobody can mark it finished for a time in the past.
        String cook = staff(r.owner(), "KITCHEN");
        assertThat(post("/kitchen/items/" + id + "/availability", cook, Map.of("until", "TIME", "at", "2020-01-01T10:00:00Z")).status()).isEqualTo(400);
    }

    @Test
    void theDinerAcceptsASubstituteOnTheirPhone() {
        R r = restaurant();
        Res placed = order(r, 0, dish(r, 0), dish(r, 1));
        String token = placed.at("/token").asText();
        JsonNode t = ticket(r, placed.at("/number").asInt());
        String line = t.at("/lines/0/id").asText();
        String offered = dish(r, 2);

        assertThat(post("/kitchen/lines/" + line + "/reject", r.owner(), Map.of("reason", "finished", "substituteItemId", offered,
                "markFinished", true)).status()).isEqualTo(200);
        JsonNode asked = track(r, token);
        JsonNode first = null;
        for (JsonNode l : asked.get("lines")) {
            if (l.get("id").asText().equals(line)) {
                first = l;
            }
        }
        String rejectedName = first.get("name").asText();
        assertThat(first.get("substituteStatus").asText()).isEqualTo("OFFERED");
        assertThat(first.get("substituteName").asText()).isEqualTo(r.dishes().get(2).get("name").asText());

        // Another order's link can't answer it.
        Res other = order(r, 1, dish(r, 1));
        assertThat(post("/public/track/" + r.slug() + "/" + other.at("/token").asText() + "/substitute/" + line, null,
                Map.of("accept", true)).status()).isEqualTo(404);

        assertThat(post("/public/track/" + r.slug() + "/" + token + "/substitute/" + line, null, Map.of("accept", true)).status()).isEqualTo(200);
        JsonNode after = track(r, token);
        assertThat(after.get("lines").toString()).contains(r.dishes().get(2).get("name").asText())
                .doesNotContain("\"" + rejectedName + "\"");
        double kept = 0;
        for (JsonNode d : r.dishes().subList(0, 2)) {
            if (!d.get("name").asText().equals(rejectedName)) {
                kept = d.get("price").asDouble();
            }
        }
        double expected = kept + r.dishes().get(2).get("price").asDouble();
        assertThat(after.get("total").asDouble()).isEqualTo(expected);
        assertThat(ticket(r, placed.at("/number").asInt()).get("lines").toString()).contains(r.dishes().get(2).get("name").asText());
        // Answered once only; the finished dish is off the menu.
        assertThat(post("/public/track/" + r.slug() + "/" + token + "/substitute/" + line, null, Map.of("accept", true)).status()).isEqualTo(404);
        assertThat(get("/kitchen/finished", r.owner()).body().toString()).contains(rejectedName);
    }

    @Test
    void aDeclinedSwapComesOffTheBillAndTheWaiterIsTold() {
        R r = restaurant();
        String waiter = staff(r.owner(), "WAITER");
        Res placed = order(r, 0, dish(r, 0), dish(r, 1));
        String token = placed.at("/token").asText();
        String line = ticket(r, placed.at("/number").asInt()).at("/lines/0/id").asText();
        post("/kitchen/lines/" + line + "/reject", r.owner(), Map.of("substituteItemId", dish(r, 2)));
        assertThat(post("/public/track/" + r.slug() + "/" + token + "/substitute/" + line, null, Map.of("accept", false)).status()).isEqualTo(200);
        JsonNode left = track(r, token);
        assertThat(left.get("lines")).hasSize(1);
        assertThat(left.get("total").asDouble()).isEqualTo(left.at("/lines/0/lineTotal").asDouble());

        JsonNode mine = get("/waiter/tables", waiter).body();
        assertThat(mine.get("orders")).hasSize(1);
        assertThat(mine.get("notices")).hasSize(1);
        assertThat(mine.at("/notices/0/message").asText()).contains("check with the table");
        assertThat(post("/waiter/notices/" + mine.at("/notices/0/id").asText() + "/done", waiter, Map.of()).status()).isEqualTo(200);
        assertThat(get("/waiter/tables", waiter).body().get("notices")).isEmpty();
    }

    @Test
    void takingADishOffTellsTheDiner() {
        R r = restaurant();
        Res placed = order(r, 0, dish(r, 0), dish(r, 1));
        String line = ticket(r, placed.at("/number").asInt()).at("/lines/0/id").asText();
        assertThat(post("/kitchen/lines/" + line + "/reject", r.owner(), Map.of("reason", "finished")).status()).isEqualTo(200);
        JsonNode t = track(r, placed.at("/token").asText());
        assertThat(t.get("lines")).hasSize(1);
        assertThat(t.at("/notices/0/message").asText()).contains("taken off your bill");
        assertThat(get("/activity", r.owner()).body().get("entries").toString()).contains("KITCHEN_CHANGE");
    }

    @Test
    void waitersSeeTheirOwnTablesAndManagersSeeEveryonesLoad() {
        R r = restaurant();
        String a = staff(r.owner(), "WAITER");
        Res first = order(r, 0, dish(r, 0));
        String b = staff(r.owner(), "WAITER");
        String bId = get("/auth/me", b).body().at("/user/id").asText();
        Res second = order(r, 1, dish(r, 1));
        assertThat(get("/waiter/tables", a).body().get("orders")).hasSize(1);
        assertThat(get("/waiter/tables", b).body().get("orders")).hasSize(1);
        JsonNode all = get("/waiter/tables?everyone=true", r.owner()).body();
        assertThat(all.get("orders")).hasSize(2);
        assertThat(all.get("waiters")).hasSize(2);

        // A manager hands the first table to the second waiter; a waiter can't hand orders to others.
        String firstId = ticket(r, first.at("/number").asInt()).get("orderId").asText();
        assertThat(post("/waiter/orders/" + firstId + "/assign", a, Map.of("userId", bId)).status()).isEqualTo(403);
        assertThat(post("/waiter/orders/" + firstId + "/assign", r.owner(), Map.of("userId", bId)).status()).isEqualTo(200);
        assertThat(get("/waiter/tables", b).body().get("orders")).hasSize(2);
        assertThat(second.status()).isEqualTo(200);
    }
}
