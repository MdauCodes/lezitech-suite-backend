package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Restaurants, package R3: the waste log (and who may record it), selling a bottle by the tot, and the bar count. */
class WasteAndBarTest extends IntegrationTest {

    private double onHand(String owner, String name) {
        for (JsonNode i : get("/recipes", owner).at("/ingredients")) {
            if (name.equals(i.get("name").asText())) {
                return i.get("onHand").asDouble();
            }
        }
        throw new AssertionError(name);
    }

    @Test
    void aBottleSoldByTheTotAndTheBarCountShowsTheShortfallInTotsAndMoney() {
        String owner = token(signup("restaurant"));
        // A 750 ml bottle of Jameson at KES 2,250 (3 a ml). Two bottles on the shelf.
        String jameson = post("/recipes/ingredients", owner, Map.of("name", "Jameson", "unit", "ml", "buyUnit", "bottle", "buySize", 750,
                "buyPrice", 2250, "area", "BAR", "startCount", 1500)).at("/id").asText();
        assertThat(get("/recipes", owner).at("/ingredients/0/area").asText()).isEqualTo("BAR");

        // Sold by the tot (25 ml at KES 300) and by the bottle.
        JsonNode drinks = post("/recipes/ingredients/" + jameson + "/sell-by", owner, Map.of("servings", List.of(
                Map.of("name", "tot", "qty", 25, "price", 300), Map.of("name", "bottle", "qty", 750, "price", 7500)))).body();
        assertThat(drinks).hasSize(2);
        String tot = drinks.get(0).get("id").asText();
        assertThat(drinks.get(0).get("name").asText()).isEqualTo("Jameson (tot)");

        // 4 tots sold: 100 ml out.
        String order = post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", tot, "qty", 4)))).at("/id").asText();
        post("/orders/" + order + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", 1200))));
        assertThat(onHand(owner, "Jameson")).isEqualTo(1400);

        // At closing: 1 full bottle and ½ of the open one = 1125 ml. 275 ml missing = 11 tots, KES 3,300 in lost sales,
        // KES 825 at what the bar paid.
        JsonNode count = post("/recipes/count", owner, Map.of("counts", List.of(Map.of("itemId", jameson, "counted", 1125)))).body();
        assertThat(count.at("/lines/0/servingsShort").asDouble()).as(count.toString()).isEqualTo(11);
        assertThat(count.at("/lines/0/serving").asText()).isEqualTo("Jameson (tot)");
        assertThat(count.at("/lostSalesValue").asDouble()).isEqualTo(3300);
        assertThat(count.at("/missingValue").asDouble()).isEqualTo(825);
        assertThat(get("/activity", owner).body().toString()).contains("11 servings").contains("3300");
    }

    @Test
    void wasteComesOutOfStockAtCostAndTheOwnerDecidesWhoRecordsIt() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String waiter = staff(owner, "WAITER");
        String beef = post("/recipes/ingredients", owner, Map.of("name", "Beef", "unit", "g", "buyUnit", "kg", "buySize", 1000,
                "buyPrice", 800, "startCount", 3000)).at("/id").asText();
        String fry = post("/items", owner, Map.of("name", "Beef fry", "kind", "MENU", "price", 600, "trackStock", false)).at("/id").asText();
        post("/recipes/items/" + fry + "/recipe", owner, Map.of("lines", List.of(Map.of("ingredientId", beef, "qty", 250))));

        // A waiter drops a plate (250 g beef, KES 200); the kitchen throws out 2 kg of beef that went off (KES 1,600).
        Res dropped = post("/recipes/waste", waiter, Map.of("itemId", fry, "qty", 1, "reason", "dropped"));
        assertThat(dropped.status()).as(dropped.body().toString()).isEqualTo(200);
        assertThat(dropped.at("/value").asDouble()).isEqualTo(200);
        assertThat(post("/recipes/waste", owner, Map.of("itemId", beef, "qty", 2000, "reason", "EXPIRED", "note", "Fridge off overnight")).status()).isEqualTo(200);
        assertThat(onHand(owner, "Beef")).isEqualTo(750);
        assertThat(post("/recipes/waste", owner, Map.of("itemId", beef, "qty", 1, "reason", "lost it")).status()).isEqualTo(400);

        // The owner sees all of it, by reason; the waiter sees their own.
        JsonNode report = get("/recipes/waste", owner).body();
        assertThat(report.at("/total").asDouble()).isEqualTo(1800);
        assertThat(report.at("/byReason/0/reason").asText()).isEqualTo("EXPIRED");
        assertThat(get("/recipes/waste", waiter).at("/entries")).hasSize(1);
        // Big waste goes to the Activity log.
        assertThat(get("/activity", owner).body().toString()).contains("Fridge off overnight").doesNotContain("Beef fry (dropped)");

        // The owner switches to managers only: the waiter can't record waste any more.
        assertThat(post("/recipes/waste/settings", waiter, Map.of("managersOnly", true)).status()).isEqualTo(403);
        assertThat(post("/recipes/waste/settings", owner, Map.of("managersOnly", true)).at("/managersOnly").asBoolean()).isTrue();
        assertThat(post("/recipes/waste", waiter, Map.of("itemId", fry, "qty", 1, "reason", "DROPPED")).status()).isEqualTo(403);
        assertThat(post("/recipes/waste", owner, Map.of("itemId", fry, "qty", 1, "reason", "STAFF_MEAL")).status()).isEqualTo(200);
    }
}
