package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A sale can only be credited to someone on the same business; another business's staff never show up. */
class StaffCreditFenceTest extends IntegrationTest {

    private Res sell(String token, String itemId, String staffId) {
        Map<String, Object> line = staffId == null ? Map.of("itemId", itemId, "qty", 1)
                : Map.of("itemId", itemId, "qty", 1, "staffId", staffId);
        return post("/orders", token, Map.of("lines", List.of(line),
                "payments", List.of(Map.of("method", "CASH", "amount", 100000))));
    }

    @Test
    void saleCreditedToAnotherBusinessesStaffIsRefused() {
        String ownerA = token(signup("restaurant"));
        String ownerB = token(signup("restaurant"));
        String cashierA = staff(ownerA, "CASHIER");
        String victim = userId(ownerB, "OWNER");
        String itemId = get("/items", ownerA).body().get(0).get("id").asText();
        int before = get("/orders", ownerA).body().size();

        post("/shifts/open", cashierA, Map.of("openingCash", 0));
        Res bad = sell(cashierA, itemId, victim);
        assertThat(bad.status()).as(bad.body().toString()).isEqualTo(400);
        assertThat(bad.body().toString()).contains("isn't on this business");

        // Nothing was written, and the other business's person is not on A's dashboard.
        assertThat(get("/orders", ownerA).body().size()).isEqualTo(before);
        Res home = get("/reports/dashboard", ownerA);
        assertThat(home.at("/topStaff").toString()).doesNotContain("Owner");
        assertThat(home.at("/kpis/top_staff").isNull() || home.at("/kpis/top_staff").isMissingNode()).isTrue();
    }

    @Test
    void saleCreditedToOwnStaffWorksAndShowsInTopStaff() {
        String owner = token(signup("restaurant"));
        String cashier = staff(owner, "CASHIER");
        String ownStaff = userId(owner, "OWNER");
        String itemId = get("/items", owner).body().get(0).get("id").asText();

        post("/shifts/open", cashier, Map.of("openingCash", 0));
        Res ok = sell(cashier, itemId, ownStaff);
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);

        JsonNode top = get("/reports/dashboard", owner).body().get("topStaff");
        assertThat(top.size()).isEqualTo(1);
        assertThat(top.get(0).get("name").asText()).isEqualTo("Owner");
    }

    @Test
    void saleWithNoStaffIdStillWorks() {
        String owner = token(signup("restaurant"));
        String cashier = staff(owner, "CASHIER");
        String itemId = get("/items", owner).body().get(0).get("id").asText();

        post("/shifts/open", cashier, Map.of("openingCash", 0));
        Res ok = sell(cashier, itemId, null);
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);

        JsonNode top = get("/reports/dashboard", owner).body().get("topStaff");
        assertThat(top.get(0).get("name").asText()).isEqualTo("CASHIER person");
    }
}
