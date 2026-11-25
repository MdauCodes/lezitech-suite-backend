package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The age check: a bill with an 18+ item cannot be paid until a staff member confirms the customer is 18 or older. */
class AgeCheckTest extends IntegrationTest {

    private String owner;
    private String cashier;
    private String signupBody;

    private void liquor() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Liquor " + email.substring(6, 10), "sector", "retail", "subSector", "liquor",
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        signupBody = r.body().toString();
        owner = token(r.body());
        cashier = staff(owner, "CASHIER");
        post("/shifts/open", cashier, Map.of("openingCash", 0));
    }

    private String open(String... itemNames) {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (String n : itemNames) {
            lines.add(Map.of("itemId", item(owner, n).get("id").asText(), "qty", 1));
        }
        Res r = post("/orders", cashier, Map.of("lines", lines));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private Res pay(String order, String method, Object amount, String ref) {
        Map<String, Object> p = new HashMap<>(Map.of("method", method, "amount", amount));
        if (ref != null) {
            p.put("reference", ref);
        }
        return post("/orders/" + order + "/pay", cashier, Map.of("payments", List.of(p)));
    }

    private void refused(Res r) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(409);
        assertThat(r.body().toString()).contains("18+ items");
    }

    @Test
    void startersAreMarkedAndTheFeatureIsOnForLiquorStores() {
        liquor();
        assertThat(item(owner, "Tusker 500ml").get("ageRestricted").asBoolean()).isTrue();
        assertThat(item(owner, "Tonic water").get("ageRestricted").asBoolean()).isFalse();
        assertThat(signupBody).contains("\"ageCheck\":true");
    }

    @Test
    void everyWayOfPayingIsRefusedUntilTheAgeIsConfirmed() {
        liquor();
        String order = open("Tusker 500ml", "Tonic water");
        assertThat(get("/orders/" + order, cashier).at("/needsAgeCheck").asBoolean()).isTrue();
        refused(pay(order, "CASH", 10000, null));
        refused(pay(order, "MPESA", 10000, "QWE123ABC"));
        refused(pay(order, "CARD", 10000, "1234"));
        refused(pay(order, "CASH", 100, null)); // a part payment of a split bill
        refused(post("/mpesa/stk", cashier, Map.of("orderId", order, "phone", "0712345678")));
        // A one-step counter sale is refused too, and leaves no sale behind.
        Res counter = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", item(owner, "Tusker 500ml").get("id").asText(), "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "amount", 5000))));
        refused(counter);
        assertThat(get("/orders/" + order, cashier).at("/status").asText()).isEqualTo("OPEN");

        // A cashier says yes: the bill closes and keeps who confirmed it and that ID was checked.
        assertThat(post("/orders/" + order + "/age-check", cashier, Map.of("confirmed", true, "idChecked", true)).status()).isEqualTo(200);
        Res paid = pay(order, "CASH", 10000, null);
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        assertThat(paid.at("/status").asText()).isEqualTo("PAID");
        assertThat(paid.at("/ageConfirmedAt").isNull()).isFalse();
        assertThat(paid.at("/ageConfirmedByName").asText()).isNotBlank();
        assertThat(paid.at("/ageIdChecked").asBoolean()).isTrue();
        assertThat(get("/reports/dashboard", owner).at("/kpis/age_checked_today").asInt()).isEqualTo(1);
    }

    @Test
    void theAnswerCanTravelWithTheCounterSale() {
        liquor();
        Res sale = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", item(owner, "Tusker 500ml").get("id").asText(), "qty", 2)),
                "payments", List.of(Map.of("method", "CASH", "amount", 5000)), "ageConfirm", Map.of("idChecked", false)));
        assertThat(sale.status()).as(sale.body().toString()).isEqualTo(200);
        assertThat(sale.at("/status").asText()).isEqualTo("PAID");
        assertThat(sale.at("/ageConfirmedAt").isNull()).isFalse();
        assertThat(sale.at("/ageIdChecked").asBoolean()).isFalse();
    }

    @Test
    void anItemAddedAfterTheConfirmationNeedsANewAnswer() {
        liquor();
        String order = open("Tusker 500ml");
        post("/orders/" + order + "/age-check", cashier, Map.of("confirmed", true));
        assertThat(post("/orders/" + order + "/lines", cashier, Map.of("lines", List.of(Map.of("itemId", item(owner, "Balozi 500ml").get("id").asText(), "qty", 1)))).status()).isEqualTo(200);
        refused(pay(order, "CASH", 10000, null));
        post("/orders/" + order + "/age-check", cashier, Map.of("confirmed", true));
        assertThat(pay(order, "CASH", 10000, null).at("/status").asText()).isEqualTo("PAID");
    }

    @Test
    void sayingNoTakesTheAgeItemsOffAndWritesItDown() {
        liquor();
        String order = open("Tusker 500ml", "Tonic water");
        Res no = post("/orders/" + order + "/age-check", cashier, Map.of("confirmed", false));
        assertThat(no.status()).as(no.body().toString()).isEqualTo(200);
        assertThat(no.at("/lines").size()).isEqualTo(1);
        assertThat(no.at("/lines/0/name").asText()).contains("Tonic");
        assertThat(no.at("/needsAgeCheck").asBoolean()).isFalse();
        assertThat(get("/activity", owner).body().toString()).contains("AGE_CHECK_NO");
        assertThat(pay(order, "CASH", 10000, null).at("/status").asText()).isEqualTo("PAID");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sellingByWeightIsCheckedToo() {
        liquor();
        JsonNode keg = item(owner, "Kenya Cane");
        Map<String, Object> body = json.convertValue(keg, Map.class);
        body.put("soldByWeight", true);
        body.put("ageRestricted", true);
        assertThat(call("PUT", "/items/" + keg.get("id").asText(), owner, body).status()).isEqualTo(200);
        Res r = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", keg.get("id").asText(), "qty", 0.5))));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        refused(pay(r.at("/id").asText(), "CASH", 100000, null));
    }

    @Test
    void withTheFeatureOffThereIsNoCheck() {
        liquor();
        String order = open("Tusker 500ml");
        assertThat(call("PUT", "/settings", owner, Map.of("settings", Map.of("features", Map.of("ageCheck", false)))).status()).isEqualTo(200);
        assertThat(get("/orders/" + order, cashier).at("/needsAgeCheck").asBoolean()).isFalse();
        assertThat(pay(order, "CASH", 10000, null).at("/status").asText()).isEqualTo("PAID");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aShopHasNoCheckUnlessItSwitchesItOn() {
        String shop = token(signup("retail"));
        JsonNode sugar = item(shop, "Sugar");
        Map<String, Object> body = json.convertValue(sugar, Map.class);
        body.put("ageRestricted", true);
        assertThat(call("PUT", "/items/" + sugar.get("id").asText(), shop, body).status()).isEqualTo(200);
        Map<String, Object> sale = Map.of("lines", List.of(Map.of("itemId", sugar.get("id").asText(), "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "amount", 5000)));
        assertThat(post("/orders", shop, sale).status()).isEqualTo(200);
        call("PUT", "/settings", shop, Map.of("settings", Map.of("features", Map.of("ageCheck", true))));
        refused(post("/orders", shop, sale));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyAManagerMarksItemsAndOtherBusinessesCannotTouchTheBill() {
        liquor();
        JsonNode tonic = item(owner, "Tonic water");
        Map<String, Object> body = json.convertValue(tonic, Map.class);
        body.put("ageRestricted", true);
        String storekeeper = staff(owner, "STOREKEEPER");
        assertThat(call("PUT", "/items/" + tonic.get("id").asText(), storekeeper, body).status()).isEqualTo(403);
        assertThat(call("PUT", "/items/" + tonic.get("id").asText(), owner, body).status()).isEqualTo(200);

        String order = open("Tusker 500ml");
        String other = token(signup("retail"));
        assertThat(post("/orders/" + order + "/age-check", other, Map.of("confirmed", true)).status()).isIn(404, 403);
        assertThat(get("/orders/" + order, cashier).at("/needsAgeCheck").asBoolean()).isTrue();
    }
}
