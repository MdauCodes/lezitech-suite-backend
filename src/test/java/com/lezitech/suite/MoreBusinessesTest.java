package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** One owner, several businesses: add another one of a different kind, switch between them with one sign-in. */
class MoreBusinessesTest extends IntegrationTest {

    @Test
    void anOwnerAddsAnotherBusinessAndSwitches() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String firstId = s.at("/tenant/id").asText();
        assertThat(get("/businesses", owner).at("/businesses")).hasSize(1);
        assertThat(get("/businesses", owner).at("/canAdd").asBoolean()).isTrue();

        // A shop next door: its own kind of business, samples and all; the owner lands in it.
        Res added = post("/businesses", owner, Map.of("name", "Mama's Minimart", "sector", "retail", "withSamples", true));
        assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
        String shop = added.at("/token").asText();
        String shopId = added.at("/tenant/id").asText();
        assertThat(shopId).isNotEqualTo(firstId);
        assertThat(added.at("/tenant/name").asText()).isEqualTo("Mama's Minimart");
        assertThat(get("/items", shop).body().toString()).doesNotContain("Tilapia");
        JsonNode both = get("/businesses", shop).body();
        assertThat(both.at("/businesses")).hasSize(2);
        assertThat(both.at("/current").asText()).isEqualTo(shopId);

        // Back to the restaurant without signing in again; signing in with the email still opens the first one.
        Res back = post("/businesses/" + firstId + "/switch", shop, Map.of());
        assertThat(back.status()).isEqualTo(200);
        assertThat(back.at("/tenant/id").asText()).isEqualTo(firstId);
        assertThat(post("/auth/login", null, Map.of("email", s.get("email").asText(), "password", "password123")).at("/tenant/id").asText()).isEqualTo(firstId);

        // Staff can't add businesses, and nobody can open someone else's.
        String waiter = staff(owner, "WAITER");
        assertThat(post("/businesses", waiter, Map.of("name", "X", "sector", "retail")).status()).isEqualTo(403);
        String stranger = token(signup("retail"));
        assertThat(post("/businesses/" + firstId + "/switch", stranger, Map.of()).status()).isEqualTo(403);
        assertThat(post("/businesses", owner, Map.of("name", " ", "sector", "retail")).status()).isEqualTo(400);
    }
}
