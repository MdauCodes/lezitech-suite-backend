package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The example shows a filled-in business of the owner's own kind, and writes nothing into theirs. */
class ExampleTest extends IntegrationTest {

    @Test
    void exampleShowsAMenuAndWritesNothing() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res up = post("/auth/signup", null, Map.of("businessName", "Clean Start " + email.substring(6, 10), "sector", "restaurant",
                "name", "Owner", "email", email, "password", "password123", "withSamples", false));
        assertThat(up.status()).as(up.body().toString()).isEqualTo(200);
        String owner = token(up.body());
        assertThat(get("/items", owner).body().size()).isZero();

        Res ex = get("/example", owner);
        assertThat(ex.status()).as(ex.body().toString()).isEqualTo(200);
        JsonNode menu = ex.body().get("menu");
        assertThat(menu.size()).isGreaterThan(0);
        assertThat(menu.get(0).get("items").size()).isGreaterThan(0);
        assertThat(ex.body().get("staff").size()).isGreaterThan(0);
        assertThat(ex.at("/takings/salesToday").asLong()).isGreaterThan(0);
        // Same sector, same figures.
        assertThat(get("/example", owner).body().get("takings")).isEqualTo(ex.body().get("takings"));

        // Nothing was written into the owner's own business.
        assertThat(get("/items", owner).body().size()).isZero();
        // Any signed-in role may look.
        assertThat(get("/example", staff(owner, "CASHIER")).status()).isEqualTo(200);
    }
}
