package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restaurants, package R2: ingredients bought by the bag and used by the gram, batch recipes, dishes using up their
 * ingredients (with choices), food cost, finished dishes when something runs out, and the kitchen count in money.
 */
class RecipesTest extends IntegrationTest {

    private String owner;

    private String ingredient(Map<String, Object> body) {
        Res r = post("/recipes/ingredients", owner, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private Map<String, JsonNode> byName(String list) {
        Map<String, JsonNode> out = new HashMap<>();
        get("/recipes", owner).body().get(list).forEach(n -> out.put(n.get("name").asText(), n));
        return out;
    }

    private double onHand(String name) {
        JsonNode n = byName("ingredients").get(name);
        if (n == null) {
            n = byName("batches").get(name);
        }
        return n.get("onHand").asDouble();
    }

    private Res sell(Map<String, Object> line) {
        String order = post("/orders", owner, Map.of("lines", List.of(line))).at("/id").asText();
        double total = get("/orders/" + order, owner).at("/total").asDouble();
        Res paid = post("/orders/" + order + "/pay", owner, Map.of("payments", List.of(Map.of("method", "MPESA", "amount", total, "reference", "QX" + System.nanoTime()))));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        return paid;
    }

    @Test
    void dishesUseTheirIngredientsAndShowTheirFoodCost() {
        owner = token(signup("restaurant"));
        String rice = ingredient(Map.of("name", "Rice", "unit", "g", "buyUnit", "bag", "buySize", 2000, "buyPrice", 260, "startCount", 4000));
        String beef = ingredient(Map.of("name", "Beef", "unit", "g", "buyUnit", "kg", "buySize", 1000, "buyPrice", 800, "startCount", 2000));
        String chicken = ingredient(Map.of("name", "Chicken", "unit", "g", "buyUnit", "kg", "buySize", 1000, "buyPrice", 600, "startCount", 1000));
        String onion = ingredient(Map.of("name", "Onion", "unit", "pc", "buyPrice", 10, "startCount", 20));
        assertThat(post("/recipes/ingredients", owner, Map.of("name", "Oil", "unit", "ml", "buyUnit", "jerrycan")).status()).isEqualTo(400);

        // Ingredients never reach the till or the menu.
        assertThat(get("/items", owner).body().toString()).doesNotContain(rice);
        assertThat(post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", rice, "qty", 1)))).status()).isEqualTo(400);

        // A pot of stew: 10 L from 1 kg beef and 10 onions (KES 900, so 0.09 a ml).
        String stew = post("/recipes/batches", owner, Map.of("name", "Beef stew", "unit", "ml", "batchYield", 10000)).at("/id").asText();
        assertThat(post("/recipes/items/" + stew + "/recipe", owner, Map.of("lines", List.of(
                Map.of("ingredientId", beef, "qty", 1000), Map.of("ingredientId", onion, "qty", 10)))).status()).isEqualTo(200);
        assertThat(post("/recipes/batches/" + stew + "/made", owner, Map.of("qty", 5000)).status()).isEqualTo(200);
        assertThat(onHand("Beef stew")).isEqualTo(5000);
        assertThat(onHand("Beef")).isEqualTo(1500);
        assertThat(onHand("Onion")).isEqualTo(15);

        // The dish: 150 g rice and 300 ml stew; "Chicken instead" swaps the stew for 150 g chicken.
        Res dish = post("/items", owner, Map.of("name", "Stew & rice", "kind", "MENU", "price", 450, "trackStock", false,
                "options", List.of(Map.of("name", "Meat", "required", false, "multi", false,
                        "choices", List.of(Map.of("name", "Chicken instead", "price", 50))))));
        String plate = dish.at("/id").asText();
        assertThat(post("/recipes/items/" + plate + "/recipe", owner, Map.of(
                "lines", List.of(Map.of("ingredientId", rice, "qty", 150), Map.of("ingredientId", stew, "qty", 300)),
                "choices", List.of(Map.of("group", "Meat", "choice", "Chicken instead", "ingredientId", chicken, "qty", 150, "replacesId", stew))))
                .status()).isEqualTo(200);
        assertThat(post("/recipes/items/" + plate + "/recipe", owner, Map.of("lines", List.of(Map.of("ingredientId", rice, "qty", 150)),
                "choices", List.of(Map.of("group", "Meat", "choice", "Lamb", "ingredientId", beef, "qty", 1)))).status()).isEqualTo(400);
        // Restore the good recipe (the bad save above changed nothing: it failed in one go).
        JsonNode d = byName("dishes").get("Stew & rice");
        assertThat(d.get("lines")).hasSize(3);
        // 150 g × 0.13 + 300 ml × 0.09 = 19.50 + 27.00 = 46.50, 10.3% of 450.
        assertThat(d.get("cost").asDouble()).isEqualTo(46.5);
        assertThat(d.get("foodCostPct").asDouble()).isEqualTo(10.3);

        // Two plates: one as it comes, one with chicken instead.
        sell(Map.of("itemId", plate, "qty", 1));
        sell(Map.of("itemId", plate, "qty", 1, "options", List.of(Map.of("group", "Meat", "name", "Chicken instead"))));
        assertThat(onHand("Rice")).isEqualTo(3700);
        assertThat(onHand("Beef stew")).isEqualTo(4700);
        assertThat(onHand("Chicken")).isEqualTo(850);

        // A delivery of 2 bags of rice at a new price.
        assertThat(post("/recipes/ingredients/" + rice + "/received", owner, Map.of("buyQty", 2, "buyPrice", 280)).at("/onHand").asDouble()).isEqualTo(7700);
        assertThat(byName("ingredients").get("Rice").get("unitCost").asDouble()).isEqualTo(0.14);

        // The kitchen counts: 200 g of chicken missing, worth KES 120 at cost.
        JsonNode count = post("/recipes/count", owner, Map.of("counts", List.of(
                Map.of("itemId", chicken, "counted", 650), Map.of("itemId", rice, "counted", 7700)))).body();
        assertThat(count.at("/missingValue").asDouble()).isEqualTo(120);
        assertThat(count.at("/lines/0/difference").asDouble()).isEqualTo(-200);
        assertThat(onHand("Chicken")).isEqualTo(650);
    }

    @Test
    void aDishShowsFinishedWhenSomethingItUsesRunsOutAndComesBackWithStock() {
        owner = token(signup("restaurant"));
        String slug = get("/auth/me", owner).at("/tenant/slug").asText();
        String beef = ingredient(Map.of("name", "Beef", "unit", "g", "buyUnit", "kg", "buySize", 1000, "buyPrice", 800, "startCount", 400));
        String salt = ingredient(Map.of("name", "Salt", "unit", "g"));
        String plate = post("/items", owner, Map.of("name", "Beef fry", "kind", "MENU", "price", 600, "trackStock", false)).at("/id").asText();
        post("/recipes/items/" + plate + "/recipe", owner, Map.of("lines", List.of(
                Map.of("ingredientId", beef, "qty", 250), Map.of("ingredientId", salt, "qty", 5))));

        // Salt was never counted, so running "below zero" on it doesn't finish the dish.
        sell(Map.of("itemId", plate, "qty", 1));
        assertThat(byName("dishes").get("Beef fry").get("finished").asBoolean()).isFalse();

        // The second plate takes beef below zero (the sale still goes through): the dish shows finished everywhere.
        sell(Map.of("itemId", plate, "qty", 1));
        assertThat(onHand("Beef")).isEqualTo(-100);
        JsonNode fry = byName("dishes").get("Beef fry");
        assertThat(fry.get("finished").asBoolean()).isTrue();
        assertThat(fry.get("finishedByStock").asBoolean()).isTrue();
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if ("Beef fry".equals(i.get("name").asText())) {
                assertThat(i.get("soldOut").asBoolean()).isTrue();
            }
        }

        // A delivery brings it back.
        post("/recipes/ingredients/" + beef + "/received", owner, Map.of("buyQty", 2));
        assertThat(byName("dishes").get("Beef fry").get("finished").asBoolean()).isFalse();

        // A refund puts the ingredients back.
        String order = post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", plate, "qty", 1)))).at("/id").asText();
        post("/orders/" + order + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", 600))));
        double before = onHand("Beef");
        assertThat(post("/orders/" + order + "/refund", owner, Map.of("reason", "Sent back")).status()).isEqualTo(200);
        assertThat(onHand("Beef")).isEqualTo(before + 250);
    }
}
