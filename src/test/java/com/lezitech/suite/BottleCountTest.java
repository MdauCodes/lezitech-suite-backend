package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The nightly bottle count at a bar: stock corrected, count kept, concern logged, closing reminder or hard stop, roles, tenants. */
class BottleCountTest extends IntegrationTest {

    private String barOwner() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Bar " + email.substring(6, 10), "sector", "restaurant", "subSector", "bar",
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        String owner = token(r.body());
        // Sample bar dishes don't count stock, so stock two drinks the way an owner would: in a Beer category.
        String cat = post("/categories", owner, Map.of("name", "Beer", "color", "#ca8a04")).at("/id").asText();
        for (Object[] b : new Object[][]{{"Zz Tusker", 210, 96}, {"Zz Guinness", 235, 72}}) {
            Res made = post("/items", owner, Map.of("name", b[0], "kind", "PRODUCT", "categoryId", cat, "price", 300, "cost", b[1], "trackStock", true,
                    "unit", "pc", "openingStock", b[2]));
            assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        }
        return owner;
    }

    private JsonNode sheetItem(String token, String name) {
        for (JsonNode i : get("/bottle-counts/sheet", token).at("/items")) {
            if (i.get("name").asText().contains(name)) return i;
        }
        throw new IllegalStateException("not on the sheet: " + name);
    }

    private Res count(String token, String itemId, Object whole) {
        return post("/bottle-counts", token, Map.of("lines", List.of(Map.of("itemId", itemId, "whole", whole))));
    }

    @Test
    void aShortageCorrectsStockIsRecordedAndLogged() {
        String owner = barOwner();
        JsonNode tusker = sheetItem(owner, "Zz Tusker");
        String id = tusker.get("itemId").asText();
        assertThat(tusker.get("expected").asInt()).isEqualTo(96);
        assertThat(tusker.has("costPerBottle")).isTrue(); // the owner sees costs

        Res r = count(owner, id, 94);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/missingBottles").asInt()).isEqualTo(2);
        assertThat(r.at("/missingValue").asInt()).isEqualTo(420); // 2 x cost 210
        assertThat(r.at("/lines/0/text").asText()).isEqualTo("2 bottles of Zz Tusker missing, worth KES 420");
        assertThat(item(owner, "Zz Tusker").get("onHand").asInt()).isEqualTo(94);

        JsonNode history = get("/bottle-counts/history", owner).body();
        assertThat(history.at("/nights/0/missingValue").asInt()).isEqualTo(420);
        assertThat(history.at("/nights/0/counts").asInt()).isEqualTo(1);
        assertThat(history.at("/nights/0/countedBy").asText()).isEqualTo("Owner");
        assertThat(history.at("/missingBottles").asInt()).isEqualTo(2);

        assertThat(get("/activity", owner).body().toString()).contains("Bottle count: 2 bottles of Zz Tusker missing")
                .contains("BOTTLE_COUNT").contains("CONCERN");
        String today = get("/night", owner).at("/today").asText();
        assertThat(get("/night/" + today, owner).at("/bottleCount/text").asText()).isEqualTo("Bottle count: 2 missing, KES 420");
    }

    @Test
    void noShortageMeansNoConcern() {
        String owner = barOwner();
        JsonNode guinness = sheetItem(owner, "Zz Guinness");
        Res r = count(owner, guinness.get("itemId").asText(), guinness.get("expected").asInt());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/allThere").asBoolean()).isTrue();
        assertThat(r.at("/summary").asText()).isEqualTo("All the bottles are there.");
        assertThat(get("/activity", owner).body().toString()).doesNotContain("BOTTLE_COUNT");
        String today = get("/night", owner).at("/today").asText();
        assertThat(get("/night/" + today, owner).at("/bottleCount/text").asText()).isEqualTo("Bottle count: all there");
    }

    @Test
    void closingIsRemindedByDefaultAndStoppedWhenTheOwnerSaysSo() {
        String owner = barOwner();
        String cashier = staff(owner, "CASHIER");
        assertThat(get("/bottle-counts/status", cashier).at("/due").asBoolean()).isTrue();
        JsonNode till = post("/shifts/open", cashier, Map.of("openingCash", 0)).body();
        // Default: only a reminder, the till still closes.
        assertThat(post("/shifts/" + till.get("id").asText() + "/close", cashier, Map.of("countedCash", 0)).status()).isEqualTo(200);

        assertThat(post("/bottle-counts/settings", cashier, Map.of("hardStop", true)).status()).isEqualTo(403);
        assertThat(post("/bottle-counts/settings", owner, Map.of("hardStop", true)).status()).isEqualTo(200);
        JsonNode second = post("/shifts/open", cashier, Map.of("openingCash", 0)).body();
        Res stopped = post("/shifts/" + second.get("id").asText() + "/close", cashier, Map.of("countedCash", 0));
        assertThat(stopped.status()).isEqualTo(409);
        assertThat(stopped.at("/code").asText()).isEqualTo("BOTTLE_COUNT_FIRST");
        assertThat(stopped.at("/message").asText()).startsWith("Count the bottles first");

        // The cashier counts (no costs shown), then closing works.
        JsonNode tusker = sheetItem(cashier, "Zz Tusker");
        assertThat(tusker.has("costPerBottle")).isFalse();
        Res r = count(cashier, tusker.get("itemId").asText(), 95);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.body().has("missingValue")).isFalse();
        assertThat(r.at("/lines/0/text").asText()).isEqualTo("1 bottle of Zz Tusker missing");
        assertThat(get("/bottle-counts/status", cashier).at("/due").asBoolean()).isFalse();
        assertThat(post("/shifts/" + second.get("id").asText() + "/close", cashier, Map.of("countedCash", 0)).status()).isEqualTo(200);
        assertThat(get("/bottle-counts/history", cashier).status()).isEqualTo(403);
    }

    @Test
    void anotherBusinessSeesNothingAndCannotCountOurBottles() {
        String ours = barOwner();
        String theirs = barOwner();
        String ourTusker = sheetItem(ours, "Zz Tusker").get("itemId").asText();
        assertThat(count(ours, ourTusker, 90).status()).isEqualTo(200);
        assertThat(get("/bottle-counts/history", theirs).at("/nightsCounted").asInt()).isZero();
        assertThat(count(theirs, ourTusker, 1).status()).isEqualTo(400);
        assertThat(item(theirs, "Zz Tusker").get("onHand").asInt()).isEqualTo(96);
    }

    @Test
    void itemsCanBeMarkedAndTheSwitchIsLimitedToTheKindsThatHaveIt() {
        String owner = barOwner();
        Res made = post("/items", owner, Map.of("name", "Zz Mixer crate", "kind", "PRODUCT", "price", 100, "cost", 50, "trackStock", true, "unit", "pc", "openingStock", 4));
        assertThat(made.status()).isEqualTo(200);
        String id = made.at("/id").asText();
        assertThat(get("/bottle-counts/sheet", owner).body().toString()).doesNotContain("Zz Mixer crate");
        assertThat(call("PUT", "/items/" + id, owner, Map.of("name", "Zz Mixer crate", "kind", "PRODUCT", "price", 100, "cost", 50, "trackStock", true, "countNightly", true)).status())
                .isEqualTo(200);
        assertThat(sheetItem(owner, "Zz Mixer crate").get("expected").asInt()).isEqualTo(4);

        String shop = token(signup("retail"));
        assertThat(get("/bottle-counts/sheet", shop).status()).isEqualTo(403);
        assertThat(get("/bottle-counts/status", shop).at("/enabled").asBoolean()).isFalse();
    }

    @Test
    void signUpStartersAreReadyToCountAndAnEmptySheetOffersAPickList() {
        for (String[] kind : new String[][]{{"restaurant", "bar"}, {"retail", "liquor"}}) {
            String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
            Res r = post("/auth/signup", null, Map.of("businessName", "Starter " + email.substring(6, 10), "sector", kind[0], "subSector", kind[1],
                    "name", "Owner", "email", email, "password", "password123", "withSamples", true));
            assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
            String owner = token(r.body());
            JsonNode sheet = get("/bottle-counts/sheet", owner).body();
            assertThat(sheet.at("/items").size()).as(kind[1]).isGreaterThanOrEqualTo(3);
            assertThat(sheet.toString()).contains("Tusker");
            assertThat(sheet.toString()).doesNotContain("Nyama choma");
        }
        // A bar whose drinks are not ticked: the sheet is empty but offers the drinks to pick.
        String owner = token(signup("restaurant"));
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Plain bar", "sector", "restaurant", "subSector", "bar", "name", "Owner",
                "email", email, "password", "password123", "withSamples", false));
        String bare = token(r.body());
        String cat = post("/categories", bare, Map.of("name", "Spirits")).at("/id").asText();
        String vodka = post("/items", bare, Map.of("name", "Zz Vodka", "kind", "MENU", "categoryId", cat, "price", 300)).at("/id").asText();
        JsonNode sheet = get("/bottle-counts/sheet", bare).body();
        assertThat(sheet.at("/items").size()).isZero();
        assertThat(sheet.at("/pickable").toString()).contains("Zz Vodka");
        String cashier = staff(bare, "CASHIER");
        assertThat(post("/bottle-counts/pick", cashier, Map.of("itemIds", List.of(vodka))).status()).isEqualTo(403);
        assertThat(post("/bottle-counts/pick", bare, Map.of("itemIds", List.of(vodka))).status()).isEqualTo(200);
        assertThat(get("/bottle-counts/sheet", bare).at("/items/0/name").asText()).isEqualTo("Zz Vodka");
        assertThat(owner).isNotNull();
    }
}
