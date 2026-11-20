package com.lezitech.suite;

import com.lezitech.suite.common.ClientIp;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A made-up X-Forwarded-For can no longer dodge a public limit: the address the proxy added is the one counted. */
class ClientAddressTest extends IntegrationTest {

    @Test
    void theLastForwardedAddressIsTheCallersOwn() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        assertThat(ClientIp.of(req)).isEqualTo("10.0.0.1");
        req.addHeader("X-Forwarded-For", "6.6.6.6, 7.7.7.7, 203.0.113.9");
        assertThat(ClientIp.of(req)).isEqualTo("203.0.113.9");
    }

    @Test
    void aBlankOrBrokenHeaderFallsBackWithoutError() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.2");
        req.addHeader("X-Forwarded-For", "   ");
        assertThat(ClientIp.of(req)).isEqualTo("10.0.0.2");
        MockHttpServletRequest odd = new MockHttpServletRequest();
        odd.setRemoteAddr("10.0.0.3");
        odd.addHeader("X-Forwarded-For", ", ,");
        assertThat(ClientIp.of(odd)).isEqualTo("10.0.0.3");
        MockHttpServletRequest trailing = new MockHttpServletRequest();
        trailing.addHeader("X-Forwarded-For", "1.2.3.4,");
        assertThat(ClientIp.of(trailing)).isEqualTo("1.2.3.4");
    }

    @Test
    void fakingTheFirstForwardedAddressDoesNotDodgeThePublicMessageLimit() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String real = "198.51.100." + (1 + (int) (Math.random() * 250)) + "-" + UUID.randomUUID();
        int tooMany = 0;
        for (int i = 0; i < 10; i++) {
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public/contact"))
                    .header("Content-Type", "application/json")
                    .header("X-Forwarded-For", "9.9.9." + i + ", " + real)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"CALLBACK\",\"name\":\"Achieng\",\"phone\":\"0712345678\"}"))
                    .build();
            if (http.send(r, HttpResponse.BodyHandlers.ofString()).statusCode() == 429) {
                tooMany++;
            }
        }
        assertThat(tooMany).as("limit is 6 an hour, so 4 of 10 must be refused").isEqualTo(4);
    }

    @Test
    void theLimitMapStaysBoundedWhenManyDifferentAddressesCome() {
        // A small cap, so this proves the clean-up in a moment instead of filling the real 50,000.
        RateLimit limit = new RateLimit(1, 3_600_000, 200);
        for (int i = 0; i < 500; i++) {
            assertThat(limit.allow("addr-" + i)).isTrue();
        }
        // Memory stays bounded however many made-up addresses turn up. Which ones are forgotten is deliberately
        // arbitrary, so a flood can cost an honest caller its count: that is the price of keeping memory capped.
        assertThat(limit.size()).isLessThanOrEqualTo(200);
    }

    @Test
    void anHonestCallerIsCountedUpToTheirLimit() {
        RateLimit limit = new RateLimit(1, 3_600_000);
        assertThat(limit.allow("honest")).isTrue();
        assertThat(limit.allow("honest")).isFalse();
    }
}
