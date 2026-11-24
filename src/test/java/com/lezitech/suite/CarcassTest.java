package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Carcasses: receive one whole, cut it up into by-weight items, spread its cost, and keep every kilo accounted for. */
class CarcassTest extends IntegrationTest {

    private String owner;
    private String storekeeper;

    private void setUp() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Butchery " + email.substring(6, 10), "sector", "retail", "subSector", "butchery",
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        owner = token(r.body());
        storekeeper = staff(owner, "STOREKEEPER");
    }

    private String bones(String token) {
        Res r = post("/items", token, Map.of("name", "Bones", "kind", "PRODUCT", "unit", "kg", "price", 100, "cost", 0, "trackStock", true, "soldByWeight", true));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private BigDecimal onHand(String token, String name) {
        return item(token, name).get("onHand").decimalValue();
    }

    private Map<String, Object> cuts(String steak, String mince, String bones, Object bonesKg, Object wasteKg) {
        return Map.of("cuts", List.of(Map.of("itemId", steak, "kg", 60), Map.of("itemId", mince, "kg", 20), Map.of("itemId", bones, "kg", bonesKg)), "wasteKg", wasteKg);
    }

    private JsonNode receive(String token, int kg, int cost) {
        Res r = post("/carcasses", token, Map.of("kind", "beef", "supplier", "Kamau Slaughter", "weightKg", kg, "cost", cost));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    @Test
    void aCarcassIsCutIntoStockWithItsCostSpread() {
        setUp();
        String steak = item(owner, "Steak").get("id").asText();
        String mince = item(owner, "Minced").get("id").asText();
        String bones = bones(owner);
        BigDecimal steakBefore = onHand(owner, "Steak");
        BigDecimal minceBefore = onHand(owner, "Minced");

        JsonNode c = receive(storekeeper, 100, 50000);
        assertThat(c.get("status").asText()).isEqualTo("RECEIVED");
        // Nothing is on the shelf until it is cut up.
        assertThat(onHand(owner, "Steak")).isEqualByComparingTo(steakBefore);

        Res cut = post("/carcasses/" + c.get("id").asText() + "/cut", storekeeper, cuts(steak, mince, bones, 15, 4));
        assertThat(cut.status()).as(cut.body().toString()).isEqualTo(200);
        assertThat(onHand(owner, "Steak")).isEqualByComparingTo(steakBefore.add(new BigDecimal("60")));
        assertThat(onHand(owner, "Minced")).isEqualByComparingTo(minceBefore.add(new BigDecimal("20")));
        assertThat(onHand(owner, "Bones")).isEqualByComparingTo("15");
        assertThat(cut.at("/unaccountedKg").decimalValue()).isEqualByComparingTo("1");
        assertThat(cut.at("/wasteKg").decimalValue()).isEqualByComparingTo("4");
        assertThat(cut.at("/cutKg").decimalValue()).isEqualByComparingTo("95");
        assertThat(cut.at("/yieldPercent").decimalValue()).isEqualByComparingTo("95");
        BigDecimal spread = BigDecimal.ZERO;
        for (JsonNode k : cut.body().get("cuts")) {
            spread = spread.add(k.get("costShare").decimalValue());
        }
        assertThat(spread).isEqualByComparingTo("50000");
        // 50000 over 95 kg is about 526.32 a kg for every cut, so the item cost moves towards it.
        assertThat(cut.at("/cuts/0/costPerKg").decimalValue()).isBetween(new BigDecimal("526"), new BigDecimal("527"));
        assertThat(item(owner, "Bones").get("cost").decimalValue()).isBetween(new BigDecimal("526"), new BigDecimal("527"));

        // A carcass is cut up once.
        assertThat(post("/carcasses/" + c.get("id").asText() + "/cut", storekeeper, cuts(steak, mince, bones, 15, 4)).status()).isEqualTo(409);

        // It is in the list with this week's summary, and the Activity log says what happened.
        Res list = get("/carcasses", owner);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.at("/carcasses/0/kind").asText()).isEqualTo("beef");
        assertThat(list.at("/week/carcassKgIn").decimalValue()).isEqualByComparingTo("100");
        assertThat(list.at("/week/carcassCost").decimalValue()).isEqualByComparingTo("50000");
        assertThat(list.at("/week/kgWasted").decimalValue()).isEqualByComparingTo("4");
        assertThat(get("/activity", owner).body().toString()).contains("not accounted for");
    }

    @Test
    void cutsAndWasteOverTheCarcassWeightAreRefused() {
        setUp();
        String steak = item(owner, "Steak").get("id").asText();
        String mince = item(owner, "Minced").get("id").asText();
        String bones = bones(owner);
        JsonNode c = receive(owner, 100, 50000);
        BigDecimal before = onHand(owner, "Steak");
        // 60 + 20 + 20 + 1 waste = 101 kg from a 100 kg carcass.
        Res over = post("/carcasses/" + c.get("id").asText() + "/cut", owner, cuts(steak, mince, bones, 20, 1));
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().toString()).contains("only 100 kg");
        assertThat(onHand(owner, "Steak")).isEqualByComparingTo(before);
        // A piece item is not a cut.
        String sausages = item(owner, "Sausages").get("id").asText();
        assertThat(post("/carcasses/" + c.get("id").asText() + "/cut", owner, Map.of("cuts", List.of(Map.of("itemId", sausages, "kg", 5)), "wasteKg", 0)).status()).isEqualTo(400);
    }

    @Test
    void anotherBusinessCannotSeeItAndACashierIsRefused() {
        setUp();
        JsonNode c = receive(owner, 100, 50000);
        String cashier = staff(owner, "CASHIER");
        assertThat(get("/carcasses", cashier).status()).isEqualTo(403);
        assertThat(post("/carcasses", cashier, Map.of("kind", "goat", "weightKg", 20, "cost", 10000)).status()).isEqualTo(403);

        String email = "other-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res other = post("/auth/signup", null, Map.of("businessName", "Other " + email.substring(6, 10), "sector", "retail", "subSector", "butchery",
                "name", "Other", "email", email, "password", "password123", "withSamples", true));
        String otherToken = token(other.body());
        assertThat(get("/carcasses", otherToken).at("/carcasses").size()).isZero();
        assertThat(get("/carcasses/" + c.get("id").asText(), otherToken).status()).isEqualTo(404);
    }

    @Test
    void wasteOfMeatSoldByWeightIsKeptWithAReasonAndLogged() {
        setUp();
        String steak = item(owner, "Steak").get("id").asText();
        BigDecimal before = onHand(owner, "Steak");
        Res w = post("/recipes/waste", owner, Map.of("itemId", steak, "qty", 0.5, "reason", "TRIM", "note", "Fat trimmed"));
        assertThat(w.status()).as(w.body().toString()).isEqualTo(200);
        assertThat(onHand(owner, "Steak")).isEqualByComparingTo(before.subtract(new BigDecimal("0.5")));
        assertThat(post("/recipes/waste", owner, Map.of("itemId", steak, "qty", 0.5, "reason", "")).status()).isEqualTo(400);
        assertThat(get("/activity", owner).body().toString()).contains("Waste: 0.5 kg Steak");
    }

    @Test
    void wasteWeightIsLimitedToThreeDecimals() {
        setUp();
        String steak = item(owner, "Steak").get("id").asText();
        Res r = post("/recipes/waste", owner, Map.of("itemId", steak, "qty", 0.1234, "reason", "TRIM"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().toString()).contains("3 decimals");
    }

    @Test
    void otherKindsOfBusinessHaveNoCarcassesAndTheirByWeightWasteIsNotAlwaysLogged() {
        String email = "grill-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Grill " + email.substring(6, 10), "sector", "restaurant", "subSector", "grill",
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        String grill = token(r.body());
        Res off = get("/carcasses", grill);
        assertThat(off.status()).isEqualTo(403);
        assertThat(off.body().toString()).contains("switched off");
        assertThat(post("/carcasses", grill, Map.of("kind", "goat", "weightKg", 20, "cost", 10000)).status()).isEqualTo(403);
        // Small by-weight waste outside a butchery behaves as before: no Activity log line.
        String goat = item(grill, "Goat choma").get("id").asText();
        assertThat(post("/recipes/waste", grill, Map.of("itemId", goat, "qty", 0.2, "reason", "BURNT")).status()).isEqualTo(200);
        assertThat(get("/activity", grill).body().toString()).doesNotContain("Waste: 0.2 kg");
    }
}
