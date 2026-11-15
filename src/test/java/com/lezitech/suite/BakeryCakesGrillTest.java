package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7: the bakery's day (baked, sold, the closing count with what's missing in shillings, leftovers to yesterday's
 * shelf), cake orders (price list, deposit by M-Pesa or none, pickup with the deposit on the bill), grill by the kg,
 * and the new business types.
 */
class BakeryCakesGrillTest extends IntegrationTest {

    private JsonNode signupAs(String sector, String sub) {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Test " + sub + " " + email.substring(6, 10), "sector", sector, "subSector", sub,
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    private JsonNode itemNamed(String owner, String name) {
        for (JsonNode i : get("/items", owner).body()) {
            if (i.get("name").asText().equals(name)) {
                return i;
            }
        }
        throw new AssertionError("no " + name);
    }

    private JsonNode row(JsonNode day, String itemId) {
        for (JsonNode i : day.get("items")) {
            if (i.get("id").asText().equals(itemId)) {
                return i;
            }
        }
        throw new AssertionError("not on the sheet");
    }

    @Test
    void theBakerysDayAndTheClosingCount() {
        JsonNode s = signupAs("restaurant", "bakery");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        JsonNode bread = itemNamed(owner, "White bread 400g");
        assertThat(bread.get("bakedDaily").asBoolean()).isTrue();
        String id = bread.get("id").asText();
        double before = get("/bakery", owner).body().get("items").size();
        assertThat(before).isGreaterThan(0);
        double startStock = row(get("/bakery", owner).body(), id).get("onHand").asDouble();

        // 100 loaves out of the oven; 30 sold at the till.
        post("/bakery/baked", owner, Map.of("itemId", id, "qty", 100));
        post("/shifts/open", cashier, Map.of("openingCash", 0));
        String bill = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", id, "qty", 30)))).at("/id").asText();
        assertThat(post("/orders/" + bill + "/pay", cashier, Map.of("payments", List.of(Map.of("method", "CASH", "amount", 30 * 65)))).at("/status").asText()).isEqualTo("PAID");
        JsonNode r = row(get("/bakery", owner).body(), id);
        assertThat(r.get("baked").asDouble()).isEqualTo(100);
        assertThat(r.get("sold").asDouble()).isEqualTo(30);
        assertThat(r.get("onHand").asDouble()).isEqualTo(startStock + 70);

        // At closing 60 are counted: 10 + the starting stock are missing, in shillings; the 60 go to yesterday's shelf.
        double expected = startStock + 70;
        Res counted = post("/bakery/count", cashier, Map.of("lines", List.of(Map.of("itemId", id, "left", 60, "action", "SELL_TOMORROW"))));
        assertThat(counted.status()).as(counted.body().toString()).isEqualTo(200);
        r = row(counted.body(), id);
        assertThat(r.get("missing").asDouble()).isEqualTo(expected - 60);
        assertThat(r.get("missingValue").asDouble()).isEqualTo((expected - 60) * 65);
        assertThat(r.get("onHand").asDouble()).isZero();
        JsonNode dayOld = counted.body().get("dayOld").get(0);
        assertThat(dayOld.get("name").asText()).isEqualTo("White bread 400g (yesterday's)");
        assertThat(dayOld.get("price").asDouble()).isEqualTo(50);
        assertThat(dayOld.get("onHand").asDouble()).isEqualTo(60);

        // Yesterday's price must be lower; only items on the sheet can be baked.
        assertThat(post("/bakery/items/" + id, owner, Map.of("bakedDaily", true, "dayOldPrice", 80)).status()).isEqualTo(400);
        String cola = itemNamed(owner, "Croissant").get("id").asText();
        assertThat(post("/bakery/items/" + cola, owner, Map.of("bakedDaily", false)).status()).isEqualTo(200);
        assertThat(post("/bakery/baked", owner, Map.of("itemId", cola, "qty", 5)).status()).isEqualTo(400);
    }

    @Test
    void aCakeOrderWithADepositAndOneWithout() throws Exception {
        JsonNode s = signupAs("restaurant", "bakery");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String cashier = staff(owner, "CASHIER");
        JsonNode page = get("/public/cakes/" + slug, null).body();
        assertThat(page.at("/rules/depositPct").asInt()).isEqualTo(50);
        String date = LocalDate.now(ZoneId.of("Africa/Nairobi")).plusDays(3).toString();

        // 2 kg black forest with writing: 2 x 2,200 = 4,400; deposit half, by the simulated M-Pesa prompt.
        Res placed = post("/public/cakes/" + slug, null, Map.of("name", "Wanjiru Kamau", "phone", "0712345678", "email", "wanjiru@example.com",
                "flavour", "Black forest", "sizeKg", 2, "message", "Happy 30th Wanjiru", "date", date, "time", "15:00", "fulfil", "PICKUP"));
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        assertThat(placed.at("/price").asDouble()).isEqualTo(4400);
        assertThat(placed.at("/depositAmount").asDouble()).isEqualTo(2200);
        String token = placed.at("/token").asText();
        JsonNode mine = null;
        for (int i = 0; i < 80; i++) {
            mine = get("/public/cakes/" + slug + "/o/" + token, null).at("/order");
            if ("CONFIRMED".equals(mine.at("/status").asText())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(mine.at("/status").asText()).isEqualTo("CONFIRMED");
        assertThat(mine.at("/depositStatus").asText()).isEqualTo("PAID");
        assertThat(mine.has("designFileId")).isFalse();

        // Too soon is refused (24 hours' notice by default).
        assertThat(post("/public/cakes/" + slug, null, Map.of("name", "X", "phone", "0712345678", "flavour", "Vanilla", "sizeKg", 1,
                "date", LocalDate.now(ZoneId.of("Africa/Nairobi")).toString(), "time", "23:59")).status()).isEqualTo(400);

        // Staff see it, bake it, and it's collected: the bill opens with the deposit already on it.
        JsonNode list = get("/cakes", cashier).body();
        assertThat(list.at("/unseen").asInt()).isEqualTo(1);
        String id = list.at("/orders/0/id").asText();
        assertThat(post("/cakes/" + id + "/status", cashier, Map.of("status", "READY")).at("/status").asText()).isEqualTo("READY");
        JsonNode got = post("/cakes/" + id + "/collect", cashier, Map.of()).body();
        assertThat(got.at("/status").asText()).isEqualTo("COLLECTED");
        JsonNode bill = get("/orders/" + got.at("/orderId").asText(), owner).body();
        assertThat(bill.at("/total").asDouble()).isEqualTo(4400);
        assertThat(bill.at("/paid").asDouble()).isEqualTo(2200);

        // The owner sets no deposit for now: the next order is confirmed straight away.
        assertThat(post("/cakes/settings", owner, Map.of("depositPct", 0, "leadHours", 24, "sizes", List.of(1, 2),
                "flavours", List.of(Map.of("name", "Vanilla", "perKg", 1800)))).at("/depositPct").asInt()).isZero();
        Res free = post("/public/cakes/" + slug, null, Map.of("name", "Otieno", "phone", "0722000111", "flavour", "Vanilla", "sizeKg", 1,
                "date", date, "time", "10:00"));
        assertThat(free.at("/status").asText()).isEqualTo("CONFIRMED");
        assertThat(free.at("/depositAmount").asDouble()).isZero();
        // The customer can cancel more than a day ahead.
        assertThat(post("/public/cakes/" + slug + "/o/" + free.at("/token").asText() + "/cancel", null, Map.of()).at("/status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void grillMeatIsSoldByTheKgAndTheNewBusinessTypesHaveTheirMenus() {
        JsonNode s = signupAs("restaurant", "grill");
        String owner = token(s);
        String waiter = staff(owner, "WAITER");
        JsonNode goat = itemNamed(owner, "Goat choma (per kg)");
        assertThat(goat.get("soldByWeight").asBoolean()).isTrue();
        assertThat(goat.get("unit").asText()).isEqualTo("kg");
        // 0.75 kg from the scale.
        String bill = post("/orders", waiter, Map.of("lines", List.of(Map.of("itemId", goat.get("id").asText(), "qty", 0.75)))).at("/id").asText();
        assertThat(get("/orders/" + bill, owner).at("/total").asDouble()).isEqualTo(1050);
        assertThat(itemNamed(owner, "Goat choma ½ kg").get("soldByWeight").asBoolean()).isFalse();

        assertThat(signupAs("restaurant", "canteen")).isNotNull();
        String cloud = token(signupAs("restaurant", "cloud-kitchen"));
        assertThat(itemNamed(cloud, "Chicken rice bowl").get("price").asDouble()).isEqualTo(700);
    }
}
