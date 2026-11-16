package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stock for each kind of business: the full stock-take (blind for counters, shortage at cost, uncounted left alone),
 * stock used in-house, a salon service using up its products, and use-by dates on kitchen deliveries.
 */
class StockTakeTest extends IntegrationTest {

    private String product(String owner, String name, int cost, int opening) {
        Res r = post("/items", owner, Map.of("name", name, "kind", "PRODUCT", "price", cost * 2, "cost", cost, "trackStock", true, "unit", "pc", "openingStock", opening));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private double onHand(String token, String id) {
        for (JsonNode i : get("/items", token).body()) if (i.at("/id").asText().equals(id)) return i.at("/onHand").asDouble();
        throw new IllegalStateException("not found");
    }

    @Test
    void fullStockTakeAndInHouseUse() {
        String owner = token(signup("retail"));
        String soap = product(owner, "Zz Bar soap " + UUID.randomUUID().toString().substring(0, 4), 50, 10);
        String tissue = product(owner, "Zz Tissue " + UUID.randomUUID().toString().substring(0, 4), 30, 5);
        String cashier = staff(owner, "CASHIER");

        JsonNode sheet = post("/stock/counts", owner, Map.of("blind", true, "note", "Month end")).body();
        String id = sheet.at("/id").asText();
        assertThat(post("/stock/counts", owner, Map.of()).status()).isEqualTo(409); // one open count per place
        // The cashier counts without seeing what the records say.
        JsonNode blind = get("/stock/counts/" + id, cashier).body();
        assertThat(blind.at("/showExpected").asBoolean()).isFalse();
        assertThat(blind.at("/lines/0/expected").isMissingNode()).isTrue();
        assertThat(post("/stock/counts/" + id + "/line", cashier, Map.of("itemId", soap, "counted", 8)).status()).isEqualTo(200);
        assertThat(post("/stock/counts/" + id + "/line", cashier, Map.of("itemId", soap, "counted", -1)).status()).isEqualTo(400);
        assertThat(post("/stock/counts/" + id + "/finish", cashier, Map.of()).status()).isEqualTo(403);
        // The owner sees the shortage so far, then finishes.
        assertThat(get("/stock/counts/" + id, owner).at("/missingSoFar").asDouble()).isEqualTo(100.0);
        JsonNode done = post("/stock/counts/" + id + "/finish", owner, Map.of()).body();
        assertThat(done.at("/status").asText()).isEqualTo("DONE");
        assertThat(done.at("/missingValue").asDouble()).isEqualTo(100.0);
        assertThat(onHand(owner, soap)).isEqualTo(8.0);
        assertThat(onHand(owner, tissue)).isEqualTo(5.0); // not counted: left as it was
        assertThat(get("/stock/counts", owner).at("/0/status").asText()).isEqualTo("DONE");

        // Used in-house: out of stock, with what it was for, in the report.
        assertThat(post("/stock/use", cashier, Map.of("itemId", tissue, "qty", 2, "purpose", "Cleaning")).status()).isEqualTo(200);
        assertThat(post("/stock/use", cashier, Map.of("itemId", tissue, "qty", 1, "purpose", " ")).status()).isEqualTo(400);
        assertThat(onHand(owner, tissue)).isEqualTo(3.0);
        JsonNode used = get("/stock/used", owner).body();
        assertThat(used.at("/total").asDouble()).isEqualTo(60.0);
        assertThat(used.at("/recentPurposes").toString()).contains("Cleaning");
    }

    @Test
    void aSalonServiceUsesUpItsProducts() {
        String owner = token(signup("beauty"));
        String relaxer = product(owner, "Zz Relaxer tub " + UUID.randomUUID().toString().substring(0, 4), 2, 1000);
        Res svc = post("/items", owner, Map.of("name", "Zz Relaxer & set", "kind", "SERVICE", "price", 1800, "trackStock", false));
        String service = svc.at("/id").asText();
        Res bad = post("/recipes/items/" + service + "/recipe", owner, Map.of("lines", List.of(Map.of("ingredientId", service, "qty", 1))));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(post("/recipes/items/" + service + "/recipe", owner, Map.of("lines", List.of(Map.of("ingredientId", relaxer, "qty", 50)))).status()).isEqualTo(200);
        assertThat(get("/recipes/items/" + service + "/recipe", owner).at("/lines/0/qty").asDouble()).isEqualTo(50.0);

        String bill = post("/orders", owner, Map.of("lines", line(service, 2))).at("/id").asText();
        assertThat(post("/orders/" + bill + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", 3600)))).at("/status").asText()).isEqualTo("PAID");
        assertThat(onHand(owner, relaxer)).isEqualTo(900.0);
    }

    @Test
    void kitchenDeliveriesCanCarryAUseByDate() {
        String owner = token(signup("restaurant"));
        Res ing = post("/recipes/ingredients", owner, Map.of("name", "Zz Milk " + UUID.randomUUID().toString().substring(0, 4), "unit", "ml", "buyUnit", "packet", "buySize", 500, "buyPrice", 60));
        assertThat(ing.status()).as(ing.body().toString()).isEqualTo(200);
        String milk = ing.at("/id").asText();
        String useBy = LocalDate.now().plusDays(3).toString();
        assertThat(post("/recipes/ingredients/" + milk + "/received", owner, Map.of("buyQty", 4, "useBy", useBy)).status()).isEqualTo(200);
        JsonNode soon = get("/stock/expiring?days=7", owner).body();
        assertThat(soon.toString()).contains(useBy);
    }
}
