package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What customers owe: every sale on account, payment and refund on a statement; who owes you; the customer's link. */
class StatementsTest extends IntegrationTest {

    @Test
    void statementWhoOwesAndTheCustomersLink() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String sugar = item(owner, "Sugar").get("id").asText();
        double price = item(owner, "Sugar").get("price").asDouble();
        String cid = post("/customers", owner, Map.of("name", "Mama Akinyi", "creditLimit", 5000)).at("/id").asText();

        // Two sales on account, one refunded, then a part payment.
        String first = post("/orders", owner, Map.of("customerId", cid, "lines", line(sugar, 2),
                "payments", List.of(Map.of("method", "CREDIT", "amount", price * 2)))).at("/id").asText();
        String second = post("/orders", owner, Map.of("customerId", cid, "lines", line(sugar, 1),
                "payments", List.of(Map.of("method", "CREDIT", "amount", price)))).at("/id").asText();
        assertThat(post("/orders/" + second + "/refund", owner, Map.of("reason", "Returned", "method", "CASH")).status()).isEqualTo(200);
        assertThat(post("/customers/" + cid + "/settle", owner, Map.of("amount", 100, "method", "MPESA", "reference", "QK12AB")).status()).isEqualTo(200);

        JsonNode st = get("/customers/" + cid + "/statement", owner).body();
        assertThat(st.at("/lines").size()).isEqualTo(4);
        assertThat(st.at("/opening").asDouble()).isEqualTo(0.0);
        assertThat(st.at("/charged").asDouble()).isEqualTo(price * 3);
        assertThat(st.at("/paid").asDouble()).isEqualTo(price + 100);
        assertThat(st.at("/closing").asDouble()).isEqualTo(price * 2 - 100);
        assertThat(st.at("/lines/3/what").asText()).contains("Payment received").contains("M-Pesa").contains("QK12AB");
        assertThat(st.at("/ageing/upTo30").asDouble()).isEqualTo(price * 2 - 100);
        assertThat(first).isNotBlank();

        // Who owes you.
        JsonNode owing = get("/customers/owing", owner).body();
        assertThat(owing.at("/total").asDouble()).isGreaterThanOrEqualTo(price * 2 - 100);
        assertThat(owing.at("/customers").toString()).contains("Mama Akinyi");

        // The customer's own link, without signing in, in the business's name; a wrong link finds nothing.
        String url = post("/customers/" + cid + "/statement/link", owner, Map.of()).at("/url").asText();
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertThat(post("/customers/" + cid + "/statement/link", owner, Map.of()).at("/url").asText()).isEqualTo(url); // kept
        JsonNode pub = get("/public/account-statement/" + slug + "/" + token, null).body();
        assertThat(pub.at("/customer/name").asText()).isEqualTo("Mama Akinyi");
        assertThat(pub.at("/closing").asDouble()).isEqualTo(price * 2 - 100);
        assertThat(pub.at("/customer/creditLimit").isMissingNode()).isTrue();
        assertThat(get("/public/account-statement/" + slug + "/" + "0".repeat(48), null).status()).isEqualTo(404);

        // Email needs an email address.
        assertThat(post("/customers/" + cid + "/statement/email", owner, Map.of()).status()).isEqualTo(400);
    }
}
