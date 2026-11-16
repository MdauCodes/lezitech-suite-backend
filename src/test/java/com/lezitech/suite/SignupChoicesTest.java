package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Sign-up: the screens the owner unticks stay off, the basics never go, extras can be added; plans are public. */
class SignupChoicesTest extends IntegrationTest {

    @Test
    void ownerChoosesWhatTheyUse() {
        assertThat(get("/public/plans", null).at("/plans").size()).isEqualTo(3);

        String email = "choices-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Choices Cafe", "sector", "restaurant", "name", "Owner",
                "email", email, "password", "password123", "products", List.of("POS", "CRM"),
                "modulesOff", List.of("kitchen", "stock", "pos"), "modulesOn", List.of("crm", "nonsense")));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        JsonNode t = r.at("/tenant");
        String modules = t.at("/settings/modules").toString();
        assertThat(modules).doesNotContain("\"kitchen\"").contains("\"stock\"").contains("\"pos\"").contains("\"crm\"").doesNotContain("nonsense");
        assertThat(t.at("/settings/features/kitchen").asBoolean()).isFalse();
        JsonNode plans = get("/billing/plans", r.at("/token").asText()).body();
        assertThat(plans.at("/parts").toString()).contains("POS").contains("CRM");
    }
}
