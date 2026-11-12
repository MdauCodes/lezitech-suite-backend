package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The website hands a visitor's details to signup under a one-time code; read once, then gone. */
class HandoverTest extends IntegrationTest {

    @Test
    void detailsPassOnceUnderACode() {
        Res given = post("/public/handover", null, Map.of("businessName", "Java Corner Café", "contactName", "Mary Kamau",
                "phone", "0712 345 678", "somethingElse", "ignored"));
        assertThat(given.status()).as(given.body().toString()).isEqualTo(200);
        String code = given.body().get("code").asText();
        assertThat(code).hasSizeGreaterThanOrEqualTo(20);

        JsonNode d = get("/public/handover/" + code, null).body();
        assertThat(d.get("businessName").asText()).isEqualTo("Java Corner Café");
        assertThat(d.get("contactName").asText()).isEqualTo("Mary Kamau");
        assertThat(d.has("somethingElse")).isFalse();

        assertThat(get("/public/handover/" + code, null).status()).isEqualTo(404);
        assertThat(post("/public/handover", null, Map.of()).status()).isEqualTo(400);
    }
}
