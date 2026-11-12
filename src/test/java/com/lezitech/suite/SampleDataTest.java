package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "Remove the samples": once the owner starts for real, the sample records go and their own stay. */
class SampleDataTest extends IntegrationTest {

    @Test
    void ownerStartsForRealThenRemovesTheSamples() {
        String owner = token(signup("restaurant"));
        JsonNode s0 = get("/samples", owner).body();
        assertThat(s0.get("present").asBoolean()).isTrue();
        assertThat(s0.get("startedOwn").asBoolean()).isFalse();

        JsonNode menu = get("/items", owner).body();
        int samples = menu.size();
        assertThat(samples).isGreaterThan(3);
        String sampleItem = menu.get(0).get("id").asText();
        String sampleName = menu.get(0).get("name").asText();

        // Their own dish, and a real sale of one of the sample dishes.
        Res made = post("/items", owner, Map.of("name", "Mama's pilau", "price", 450, "kind", "MENU"));
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        String own = made.body().get("id").asText();
        post("/shifts/open", owner, Map.of("openingCash", 0));
        Res sale = post("/orders", owner, Map.of("lines", line(sampleItem, 1),
                "payments", List.of(Map.of("method", "CASH", "amount", 5000))));
        assertThat(sale.status()).as(sale.body().toString()).isEqualTo(200);
        String saleId = sale.body().get("id").asText();
        assertThat(get("/samples", owner).body().get("startedOwn").asBoolean()).isTrue();

        // A cashier can't; the owner can.
        String cashier = staff(owner, "CASHIER");
        assertThat(post("/samples/remove", cashier, Map.of()).status()).isEqualTo(403);
        Res removed = post("/samples/remove", owner, Map.of());
        assertThat(removed.status()).as(removed.body().toString()).isEqualTo(200);

        JsonNode after = get("/items", owner).body();
        assertThat(after.size()).isEqualTo(1);
        assertThat(after.get(0).get("id").asText()).isEqualTo(own);
        // The sale of a sample dish stays, with the dish's name on it.
        JsonNode kept = get("/orders/" + saleId, owner).body();
        assertThat(kept.get("lines").get(0).get("name").asText()).isEqualTo(sampleName);
        assertThat(get("/samples", owner).body().get("present").asBoolean()).isFalse();
        // The cashier added after signup is real staff and can still work.
        assertThat(get("/items", cashier).status()).isEqualTo(200);
    }

    @Test
    void propertySamplesGoWithTheirRentBills() {
        String owner = token(signup("property"));
        assertThat(get("/customers", owner).body().size()).isGreaterThan(0);
        assertThat(post("/samples/remove", owner, Map.of()).status()).isEqualTo(200);
        assertThat(get("/customers", owner).body().size()).isEqualTo(0);
        assertThat(get("/samples", owner).body().get("present").asBoolean()).isFalse();
    }
}
