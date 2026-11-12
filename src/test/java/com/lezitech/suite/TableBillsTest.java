package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.publicapi.PublicController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "Scan to pay" on the table card: the table's open bills, each with the link to pay it. */
class TableBillsTest extends IntegrationTest {

    @BeforeEach
    void freshLimits() {
        PublicController.PER_TABLE.clear();
        PublicController.PER_CLIENT.clear();
    }

    @Test
    void aTablesOpenBillsAreListedForTheDinerToPickTheirs() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        List<String> tables = new ArrayList<>();
        get("/floors", owner).body().forEach(f -> f.get("tables").forEach(t -> tables.add(t.get("id").asText())));
        String dish = null;
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (dish == null && !i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                dish = i.get("id").asText();
            }
        }
        String table = tables.get(0);
        assertThat(get("/public/tables/" + slug + "/" + table + "/bills", null).body().get("bills")).isEmpty();

        // One bill from the phone, and a second one on the same table (a group order gives each table a new bill).
        post("/public/orders/" + slug, null, Map.of("tableId", table, "customerLabel", "Achieng",
                "lines", List.of(Map.of("itemId", dish, "qty", 1))));
        post("/public/orders/" + slug + "/group", null, Map.of("tables", List.of(
                Map.of("tableId", table, "lines", List.of(Map.of("itemId", dish, "qty", 2))),
                Map.of("tableId", tables.get(1), "lines", List.of(Map.of("itemId", dish, "qty", 1))))));

        JsonNode res = get("/public/tables/" + slug + "/" + table + "/bills", null).body();
        assertThat(res.at("/table/label").asText()).isNotBlank();
        JsonNode bills = res.get("bills");
        assertThat(bills).hasSize(2);
        assertThat(bills.get(0).get("customerLabel").asText()).isEqualTo("Achieng");
        assertThat(bills.get(0).get("dishes").asText()).isNotBlank();
        assertThat(bills.get(0).get("due").asDouble()).isPositive();
        String path = bills.get(0).get("trackPath").asText();
        assertThat(path).startsWith("/track/" + slug + "/");
        assertThat(bills.get(0).has("token")).isFalse();
        // The link really is that bill's own page.
        assertThat(get("/public" + path, null).at("/order/customerLabel").asText()).isEqualTo("Achieng");

        // Another restaurant's slug can't read this table.
        JsonNode other = signup("restaurant");
        assertThat(get("/public/tables/" + other.at("/tenant/slug").asText() + "/" + table + "/bills", null).status()).isEqualTo(404);
    }
}
