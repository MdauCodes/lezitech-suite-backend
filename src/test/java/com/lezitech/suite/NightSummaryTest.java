package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R8: the owner's night summary (sales, tills short or over, food cost, voids and discounts, things worth a look, best
 * sellers), kept and downloadable as a PDF; theft alerts such as items taken off after the bill was printed; and the
 * plain-words menu report.
 */
class NightSummaryTest extends IntegrationTest {

    @Test
    void theNightSummaryShowsTheDayAndWhatNeedsAnEye() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String cashier = staff(owner, "CASHIER");
        String waiter = staff(owner, "WAITER");
        JsonNode dish = null;
        for (JsonNode i : get("/items", owner).body()) {
            if (dish == null && "MENU".equals(i.get("kind").asText()) && i.get("options").isEmpty()) {
                dish = i;
            }
        }
        double price = dish.get("price").asDouble();
        String dishId = dish.get("id").asText();

        // Two bills paid (cash and M-Pesa).
        post("/shifts/open", cashier, Map.of("openingCash", 1000));
        String a = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", dishId, "qty", 2)))).at("/id").asText();
        post("/orders/" + a + "/pay", cashier, Map.of("payments", List.of(Map.of("method", "CASH", "amount", price * 2))));
        String b = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", dishId, "qty", 1)))).at("/id").asText();
        post("/orders/" + b + "/pay", cashier, Map.of("payments", List.of(Map.of("method", "MPESA", "amount", price, "reference", "QX12AB34CD"))));

        // A waiter prints a bill, then takes an item off it: a warning sign.
        String c = post("/orders", waiter, Map.of("lines", List.of(Map.of("itemId", dishId, "qty", 3)))).at("/id").asText();
        assertThat(post("/orders/" + c + "/printed", waiter, Map.of()).status()).isEqualTo(200);
        String line = get("/orders/" + c, owner).at("/lines/0/id").asText();
        assertThat(call("PATCH", "/orders/" + c + "/lines/" + line, waiter, Map.of("qty", 2)).status()).isEqualTo(200);

        // The cashier closes the till KES 100 short.
        String shift = get("/shifts/current", cashier).at("/id").asText();
        post("/shifts/" + shift + "/close", cashier, Map.of("countedCash", 1000 + price * 2 - 100));

        // The waiter can't see the summary; the owner can, before it's sent.
        assertThat(get("/night", waiter).status()).isEqualTo(403);
        String today = get("/night", owner).at("/today").asText();
        JsonNode day = get("/night/" + today, owner).body();
        assertThat(day.at("/notSentYet").asBoolean()).isTrue();
        assertThat(day.at("/sales/total").asDouble()).isEqualTo(price * 3);
        assertThat(day.at("/sales/bills").asInt()).isEqualTo(2);
        assertThat(day.at("/sales/openBills").asInt()).isEqualTo(1);
        assertThat(day.at("/tills/0/difference").asDouble()).isEqualTo(-100);
        assertThat(day.at("/voidsAndDiscounts/removed").asInt()).isEqualTo(1);
        assertThat(day.at("/alerts").toString()).contains("after the bill was printed").contains("short");
        assertThat(day.at("/topDishes/0/name").asText()).isEqualTo(dish.get("name").asText());
        assertThat(day.at("/headline").asText()).contains("Cash short KES 100");

        // Sent: kept, listed, and downloadable as a PDF.
        assertThat(post("/night/" + today + "/send", owner, Map.of()).status()).isEqualTo(200);
        assertThat(get("/night", owner).at("/recent/0/day").asText()).isEqualTo(today);
        assertThat(get("/night/" + today, owner).body().has("notSentYet")).isFalse();
        Raw pdf = raw("/night/" + today + "/pdf", owner);
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(new String(pdf.bytes(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // Settings: the latest time, and only real emails.
        assertThat(post("/night/settings", owner, Map.of("latest", "21:30", "emails", List.of("owner@example.com"))).at("/latest").asText()).isEqualTo("21:30");
        assertThat(post("/night/settings", owner, Map.of("emails", List.of("not-an-email"))).status()).isEqualTo(400);

        // The menu report sorts every dish into keep, promote, raise the price, or think about dropping.
        JsonNode menu = get("/menu-report", owner).body();
        assertThat(menu.at("/dishes").size()).isGreaterThan(3);
        for (JsonNode d : menu.at("/dishes")) {
            assertThat(List.of("STAR", "PROMOTE", "FIX_PRICE", "DROP")).contains(d.get("group").asText());
            assertThat(d.get("advice").asText()).isNotBlank();
        }
    }
}
