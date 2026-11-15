package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6, menu tools: a combo is one price and its parts go to the kitchen and bar; happy hour prices a dish when it's
 * ordered; a bundle and buy-one-get-one are worked out on the bill; meal times put what's served now first.
 */
class MenuToolsTest extends IntegrationTest {

    private List<JsonNode> plainDishes(String owner) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode i : get("/items", owner).body()) {
            if ("MENU".equals(i.get("kind").asText()) && i.get("options").isEmpty() && i.get("price").asDouble() > 0) {
                out.add(i);
            }
        }
        assertThat(out.size()).isGreaterThanOrEqualTo(6);
        return out;
    }

    private static Map<String, Object> line(JsonNode item, int qty) {
        return Map.of("itemId", item.get("id").asText(), "qty", qty);
    }

    private static String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    @Test
    void combosHappyHourBundlesAndBuyOneGetOne() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String waiter = staff(owner, "WAITER");
        List<JsonNode> d = plainDishes(owner);
        JsonNode main = d.get(0), cola = d.get(1), juice = d.get(2), side = d.get(3), beer = d.get(4), other = d.get(5);

        // A combo: the main is fixed, the drink is a choice (juice costs KES 20 more).
        Res combo = post("/menu-tools/combos", owner, Map.of("name", "Lunch meal", "price", 500, "parts", List.of(
                Map.of("name", "Main", "choices", List.of(Map.of("itemId", main.get("id").asText()))),
                Map.of("name", "Drink", "choices", List.of(Map.of("itemId", cola.get("id").asText()), Map.of("itemId", juice.get("id").asText(), "extra", 20))))));
        assertThat(combo.status()).as(combo.body().toString()).isEqualTo(200);
        String comboId = combo.at("/id").asText();
        assertThat(post("/menu-tools/combos", waiter, Map.of("name", "X", "price", 1, "parts", List.of())).status()).isEqualTo(403);

        post("/shifts/open", waiter, Map.of("openingCash", 0));
        // Choosing the drink is a must.
        assertThat(post("/orders", waiter, Map.of("lines", List.of(Map.of("itemId", comboId, "qty", 1)))).status()).isEqualTo(400);
        Res order = post("/orders", waiter, Map.of("lines", List.of(Map.of("itemId", comboId, "qty", 1,
                "options", List.of(Map.of("group", "Drink", "name", juice.get("name").asText()))))));
        assertThat(order.status()).as(order.body().toString()).isEqualTo(200);
        JsonNode bill = get("/orders/" + order.at("/id").asText(), owner).body();
        assertThat(bill.at("/total").asDouble()).isEqualTo(520);
        String lines = bill.at("/lines").toString();
        assertThat(lines).contains(main.get("name").asText()).contains(juice.get("name").asText()).contains("Part of Lunch meal");

        // Happy hour, all day today: half price on the beer. The till shows it and the bill charges it.
        Res happy = post("/menu-tools/deals", owner, Map.of("name", "Happy beers", "kind", "PERCENT", "percent", 50,
                "items", List.of(Map.of("itemId", beer.get("id").asText()))));
        assertThat(happy.status()).as(happy.body().toString()).isEqualTo(200);
        assertThat(happy.at("/onNow").asBoolean()).isTrue();
        double half = Math.round(beer.get("price").asDouble() * 0.5);
        for (JsonNode i : get("/items", owner).body()) {
            if (i.get("id").asText().equals(beer.get("id").asText())) {
                assertThat(i.get("happyPrice").asDouble()).isEqualTo(half);
            }
        }
        String beerBill = post("/orders", waiter, Map.of("lines", List.of(line(beer, 2)))).at("/id").asText();
        assertThat(get("/orders/" + beerBill, owner).at("/total").asDouble()).isEqualTo(half * 2);

        // A happy hour that isn't on now changes nothing.
        LocalTime now = LocalTime.now(ZoneId.of("Africa/Nairobi"));
        post("/menu-tools/deals", owner, Map.of("name", "Later", "kind", "PRICE", "timeFrom", hhmm(now.plusHours(3)), "timeTo", hhmm(now.plusHours(4)),
                "items", List.of(Map.of("itemId", other.get("id").asText(), "price", 1))));
        String otherBill = post("/orders", waiter, Map.of("lines", List.of(line(other, 1)))).at("/id").asText();
        assertThat(get("/orders/" + otherBill, owner).at("/total").asDouble()).isEqualTo(other.get("price").asDouble());

        // A bundle: main + side for a set price, worked out on the bill; a second main alone isn't a second set.
        double bundlePrice = main.get("price").asDouble() + side.get("price").asDouble() - 100;
        assertThat(post("/menu-tools/deals", owner, Map.of("name", "Main and side", "kind", "BUNDLE", "dealPrice", bundlePrice, "items", List.of(
                Map.of("itemId", main.get("id").asText(), "qty", 1), Map.of("itemId", side.get("id").asText(), "qty", 1)))).status()).isEqualTo(200);
        String bundleBill = post("/orders", waiter, Map.of("lines", List.of(line(main, 2), line(side, 1)))).at("/id").asText();
        JsonNode b = get("/orders/" + bundleBill, owner).body();
        assertThat(b.at("/total").asDouble()).isEqualTo(bundlePrice + main.get("price").asDouble());
        assertThat(b.at("/lines").toString()).contains("Deal: Main and side");

        // Buy one get one on the cola: 3 colas, one free.
        assertThat(post("/menu-tools/deals", owner, Map.of("name", "Cola 1+1", "kind", "BOGO", "buy", 1, "get", 1,
                "items", List.of(Map.of("itemId", cola.get("id").asText())))).status()).isEqualTo(200);
        String colaBill = post("/orders", waiter, Map.of("lines", List.of(line(cola, 3)))).at("/id").asText();
        assertThat(get("/orders/" + colaBill, owner).at("/total").asDouble()).isEqualTo(cola.get("price").asDouble() * 2);

        // Paying keeps the deal price.
        double due = get("/orders/" + colaBill, owner).at("/total").asDouble();
        Res paid = post("/orders/" + colaBill + "/pay", waiter, Map.of("payments", List.of(Map.of("method", "CASH", "amount", due))));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        assertThat(paid.at("/status").asText()).isEqualTo("PAID");
        assertThat(paid.at("/total").asDouble()).isEqualTo(due);

        // Switched off, the deal stops applying.
        String dealId = happy.at("/id").asText();
        assertThat(post("/menu-tools/deals", owner, Map.of("name", "Bad", "kind", "PERCENT", "percent", 95, "items", List.of(line(beer, 1)))).status()).isEqualTo(400);
        call("PUT", "/menu-tools/deals/" + dealId, owner, Map.of("name", "Happy beers", "kind", "PERCENT", "percent", 50, "active", false,
                "items", List.of(Map.of("itemId", beer.get("id").asText()))));
        String fullBill = post("/orders", waiter, Map.of("lines", List.of(line(beer, 1)))).at("/id").asText();
        assertThat(get("/orders/" + fullBill, owner).at("/total").asDouble()).isEqualTo(beer.get("price").asDouble());
    }

    @Test
    void mealTimesPutWhatIsServedNowFirstAndHideNothing() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        JsonNode cats = get("/categories", owner).body();
        assertThat(cats.size()).isGreaterThanOrEqualTo(2);
        String first = cats.get(0).get("id").asText();
        String second = cats.get(1).get("id").asText();
        LocalTime now = LocalTime.now(ZoneId.of("Africa/Nairobi"));
        // The first category is served later today; the second right now.
        assertThat(call("PUT", "/menu-tools/categories/" + first + "/meal-time", owner, Map.of("from", hhmm(now.plusHours(4)), "to", hhmm(now.plusHours(6)))).status()).isEqualTo(200);
        assertThat(call("PUT", "/menu-tools/categories/" + second + "/meal-time", owner, Map.of("from", hhmm(now.minusHours(1)), "to", hhmm(now.plusHours(1)))).status()).isEqualTo(200);
        assertThat(call("PUT", "/menu-tools/categories/" + second + "/meal-time", owner, Map.of("from", "07:00")).status()).isEqualTo(400);

        JsonNode menu = get("/public/menu/" + slug, null).body();
        JsonNode list = menu.get("categories");
        assertThat(list.get(0).get("id").asText()).isEqualTo(second);
        assertThat(list.get(0).get("servingNow").asBoolean()).isTrue();
        assertThat(list.get(list.size() - 1).get("id").asText()).isEqualTo(first);
        assertThat(list.get(list.size() - 1).get("servingNow").asBoolean()).isFalse();
        // Its dishes are still on the menu.
        boolean found = false;
        for (JsonNode i : menu.get("items")) {
            found |= first.equals(i.path("categoryId").asText());
        }
        assertThat(found).isTrue();
    }
}
