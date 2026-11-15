package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reaching the BizNez team in one tap: the numbers, and a message from inside the app or from the sign-in page. */
class ContactTest extends IntegrationTest {

    @Test
    void anyoneCanReachTheTeam() {
        JsonNode nums = get("/public/contact", null).body();
        assertThat(nums.at("/whatsapp").asText()).isNotBlank();
        assertThat(nums.at("/display").asText()).isEqualTo("0796 814 154");
        assertThat(nums.at("/callDisplay").asText()).isEqualTo("0794 206 566");

        // A waiter inside BizNez: who and where are filled in.
        JsonNode s = signup("restaurant");
        String waiter = staff(token(s), "WAITER");
        Res sent = post("/contact", waiter, Map.of("kind", "PROBLEM", "message", "The kitchen screen isn't updating", "page", "/kitchen"));
        assertThat(sent.status()).as(sent.body().toString()).isEqualTo(200);
        assertThat(sent.at("/whatsapp").asText()).startsWith("https://wa.me/");
        assertThat(post("/contact", waiter, Map.of("kind", "QUESTION", "message", " ")).status()).isEqualTo(400);
        assertThat(post("/contact", waiter, Map.of("kind", "NONSENSE", "message", "hi")).status()).isEqualTo(400);

        // Someone on the sign-in page asks for a call back: a number is needed.
        assertThat(post("/public/contact", null, Map.of("kind", "CALLBACK", "name", "Achieng")).status()).isEqualTo(400);
        assertThat(post("/public/contact", null, Map.of("kind", "CALLBACK", "name", "Achieng", "phone", "0712345678", "business", "Achieng's Salon")).status()).isEqualTo(200);
    }
}
