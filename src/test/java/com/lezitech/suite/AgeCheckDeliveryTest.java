package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Age check on deliveries: delivery-app orders are exempt, our own riders confirm at the door, the cash hand-in is never blocked. */
class AgeCheckDeliveryTest extends IntegrationTest {

    @Test
    void ridersCheckAtTheDoorAndAppOrdersAreExempt() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        // A restaurant that sells alcohol switches the check on and marks a drink.
        String slug = s.at("/tenant/slug").asText();
        assertThat(post("/delivery/settings", owner, Map.of("online", true, "payFirst", true, "payAtDoor", true)).at("/online").asBoolean()).isTrue();
        String area = post("/delivery/areas", owner, Map.of("name", "Kilimani", "fee", 150, "riderPay", 100, "minutes", 25)).at("/id").asText();
        // Mark the first public dish 18+ and switch the feature on, keeping the other switches.
        JsonNode dish = null;
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                dish = i;
                break;
            }
        }
        assertThat(dish).isNotNull();
        JsonNode full = item(owner, dish.get("name").asText());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.convertValue(full, Map.class);
        body.put("ageRestricted", true);
        // Features are replaced as a whole by Settings, so send the whole set with the check on.
        Map<String, Object> feats = new java.util.HashMap<>(json.convertValue(s.findValue("features"), Map.class));
        feats.put("ageCheck", true);
        assertThat(call("PUT", "/settings", owner, Map.of("settings", Map.of("features", feats))).status()).isEqualTo(200);
        assertThat(call("PUT", "/items/" + full.get("id").asText(), owner, body).status()).isEqualTo(200);
        String dishId = dish.get("id").asText();

        // A delivery-app order with an 18+ item: the app checks, so it simply closes.
        Res app = post("/delivery/app-orders", owner, Map.of("app", "GLOVO", "ref", "G-1", "lines", line(dishId, 1)));
        assertThat(app.status()).as(app.body().toString()).isEqualTo(200);
        assertThat(get("/orders/" + app.at("/orderId").asText(), owner).at("/status").asText()).isEqualTo("PAID");

        // Our own order, pay at the door: the rider cannot finish it without the age check, and then it works.
        Res placed = post("/public/orders/" + slug, null, Map.of("lines", List.of(Map.of("itemId", dishId, "qty", 1)),
                "delivery", Map.of("phone", "0722000111", "name", "Wanjiku", "areaId", area, "address", "Argwings Kodhek Rd",
                        "landmark", "Blue gate", "payAtDoor", true)));
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        int number = placed.at("/number").asInt();
        String rider = staff(owner, "RIDER");
        String id = null;
        for (JsonNode d : get("/delivery", owner).at("/deliveries")) {
            if (d.get("number").asInt() == number) {
                id = d.get("id").asText();
                assertThat(d.get("needsAgeCheck").asBoolean()).isTrue();
            }
        }
        for (JsonNode t : get("/kitchen", owner).body()) {
            post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", owner, Map.of());
            post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", owner, Map.of());
        }
        post("/delivery/" + id + "/rider", rider, Map.of());
        post("/delivery/" + id + "/picked", rider, Map.of());
        Res refused = post("/delivery/" + id + "/delivered", rider, Map.of("how", "CASH"));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().toString()).contains("18+ items");
        Res done = post("/delivery/" + id + "/delivered", rider, Map.of("how", "CASH", "ageConfirm", Map.of("idChecked", true)));
        assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
        assertThat(done.at("/collected").asDouble()).isGreaterThan(0);

        // The cash hand-in goes through.
        String cashier = staff(owner, "CASHIER");
        post("/shifts/open", cashier, Map.of("openingCash", 0));
        String riderId = get("/delivery/mine", rider).at("/id").asText();
        assertThat(post("/delivery/riders/" + riderId + "/hand-in", cashier, Map.of("counted", done.at("/collected").asDouble())).status()).isEqualTo(200);
    }
}
