package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.property.FoodCourtController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Malls & property, package M9: one QR, many stalls, one M-Pesa payment, each stall's screen, the TV, paying stalls. */
class FoodCourtTest extends IntegrationTest {

    @BeforeEach
    void freshLimits() {
        FoodCourtController.ORDERS.clear();
        FoodCourtController.PAY.clear();
    }

    private JsonNode waitPaid(String slug, String token) throws InterruptedException {
        JsonNode t = null;
        for (int i = 0; i < 60; i++) {
            t = get("/public/fc/" + slug + "/orders/" + token, null).body();
            String p = t.at("/payment/status").asText();
            if (!"PENDING".equals(p) && !p.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        return t;
    }

    @Test
    void oneBasketFromTwoStallsOnePaymentEachStallSeesItsPart() throws Exception {
        JsonNode s = signup("property");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String fish = post("/property/foodcourt/stalls", owner, Map.of("name", "Mama Oliech Fish", "feePct", 10)).at("/id").asText();
        String java = post("/property/foodcourt/stalls", owner, Map.of("name", "Java Corner", "feePct", 0)).at("/id").asText();
        String tilapia = post("/property/foodcourt/stalls/" + fish + "/items", owner, Map.of("name", "Fried tilapia & ugali", "price", 900)).at("/id").asText();
        String latte = post("/property/foodcourt/stalls/" + java + "/items", owner, Map.of("name", "Latte", "price", 350)).at("/id").asText();
        String soldOut = post("/property/foodcourt/stalls/" + java + "/items", owner, Map.of("name", "Muffin", "price", 200)).at("/id").asText();
        JsonNode overview = get("/property/foodcourt", owner).body();
        String fishLink = null;
        String javaLink = null;
        for (JsonNode k : overview.at("/links/kitchens")) {
            if ("Mama Oliech Fish".equals(k.get("name").asText())) {
                fishLink = k.get("link").asText();
            } else {
                javaLink = k.get("link").asText();
            }
        }
        String fishKey = fishLink.substring(fishLink.indexOf("k=") + 2);
        String javaKey = javaLink.substring(javaLink.indexOf("k=") + 2);
        String boardKey = overview.at("/links/board").asText().replaceAll(".*k=", "");

        // A stall marks a dish as finished: it can't be ordered.
        post("/public/fc/" + slug + "/kitchen/" + java + "/items/" + soldOut + "?k=" + javaKey, null, Map.of("available", false));
        assertThat(post("/public/fc/" + slug + "/orders", null, Map.of("phone", "0712345678", "lines", List.of(Map.of("itemId", soldOut, "qty", 1)))).status()).isEqualTo(409);

        // The diner orders from both stalls (a price sent by the phone is ignored) and pays once.
        Res placed = post("/public/fc/" + slug + "/orders", null, Map.of("phone", "0712345678", "table", "T7",
                "lines", List.of(Map.of("itemId", tilapia, "qty", 2, "price", 1), Map.of("itemId", latte, "qty", 1))));
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        assertThat(placed.at("/total").decimalValue()).isEqualByComparingTo("2150");
        String token = placed.at("/token").asText();
        int number = placed.at("/number").asInt();
        assertThat(waitPaid(slug, token).at("/status").asText()).isEqualTo("PAID");

        // Each stall's screen shows only its part; a wrong key shows nothing.
        JsonNode fishScreen = get("/public/fc/" + slug + "/kitchen/" + fish + "?k=" + fishKey, null).body();
        assertThat(fishScreen.at("/orders/0/lines").toString()).contains("Fried tilapia").doesNotContain("Latte");
        assertThat(get("/public/fc/" + slug + "/kitchen/" + fish + "?k=" + javaKey, null).status()).isEqualTo(404);
        String orderId = fishScreen.at("/orders/0/id").asText();

        // Fish is ready: the TV and the diner's page say so; latte still being made.
        post("/public/fc/" + slug + "/kitchen/" + fish + "/orders/" + orderId + "?k=" + fishKey, null, Map.of("state", "READY"));
        JsonNode board = get("/public/fc/" + slug + "/board?k=" + boardKey, null).body();
        assertThat(board.at("/ready/0/number").asInt()).isEqualTo(number);
        assertThat(board.at("/ready/0/stall").asText()).isEqualTo("Mama Oliech Fish");
        assertThat(board.at("/preparing").toString()).contains(String.valueOf(number));
        assertThat(get("/public/fc/" + slug + "/board?k=wrong", null).status()).isEqualTo(404);
        String parts = get("/public/fc/" + slug + "/orders/" + token, null).at("/parts").toString();
        assertThat(parts).contains("\"stall\":\"Mama Oliech Fish\"").contains("READY").contains("PREPARING");

        // Both collected: the order is done.
        post("/public/fc/" + slug + "/kitchen/" + fish + "/orders/" + orderId + "?k=" + fishKey, null, Map.of("state", "COLLECTED"));
        post("/public/fc/" + slug + "/kitchen/" + java + "/orders/" + orderId + "?k=" + javaKey, null, Map.of("state", "COLLECTED"));
        assertThat(get("/public/fc/" + slug + "/orders/" + token, null).at("/status").asText()).isEqualTo("DONE");

        // The mall pays each stall its sales less the mall's fee: fish 1,800 − 10% = 1,620; Java 350 with no fee.
        JsonNode stalls = get("/property/foodcourt", owner).at("/stalls");
        for (JsonNode st : stalls) {
            if ("Mama Oliech Fish".equals(st.get("name").asText())) {
                assertThat(st.get("salesAll").decimalValue()).isEqualByComparingTo("1800");
                assertThat(st.get("toPayStall").decimalValue()).isEqualByComparingTo("1620");
            } else {
                assertThat(st.get("toPayStall").decimalValue()).isEqualByComparingTo("350");
            }
        }
        JsonNode after = post("/property/foodcourt/stalls/" + fish + "/payouts", owner, Map.of("amount", 1000, "method", "MPESA", "reference", "QX1")).body();
        assertThat(after.get("toPayStall").decimalValue()).isEqualByComparingTo("620");
        assertThat(get("/property/foodcourt/stalls/" + fish + "/days", owner).at("/0/stallShare").decimalValue()).isEqualByComparingTo("1620");
    }

    @Test
    void anUnpaidOrderNeverReachesTheStalls() throws Exception {
        JsonNode s = signup("property");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String stall = post("/property/foodcourt/stalls", owner, Map.of("name", "Chips Point")).at("/id").asText();
        String chips = post("/property/foodcourt/stalls/" + stall + "/items", owner, Map.of("name", "Chips masala", "price", 250)).at("/id").asText();
        String link = get("/property/foodcourt", owner).at("/links/kitchens/0/link").asText();
        String key = link.substring(link.indexOf("k=") + 2);
        // Phones ending 9999 fail in the simulator: the diner cancelled.
        String token = post("/public/fc/" + slug + "/orders", null, Map.of("phone", "0712349999", "lines", List.of(Map.of("itemId", chips, "qty", 1)))).at("/token").asText();
        JsonNode t = waitPaid(slug, token);
        assertThat(t.at("/status").asText()).isEqualTo("WAITING_PAYMENT");
        assertThat(get("/public/fc/" + slug + "/kitchen/" + stall + "?k=" + key, null).at("/orders")).isEmpty();
        // Paying again from another phone works.
        assertThat(post("/public/fc/" + slug + "/orders/" + token + "/pay", null, Map.of("phone", "0712345678")).status()).isEqualTo(200);
        assertThat(waitPaid(slug, token).at("/status").asText()).isEqualTo("PAID");
        assertThat(get("/public/fc/" + slug + "/kitchen/" + stall + "?k=" + key, null).at("/orders")).hasSize(1);
    }
}
