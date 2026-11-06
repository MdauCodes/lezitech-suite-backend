package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Sales made while the till was offline arrive later, keep their real time, and are never doubled. */
class OfflineSyncTest extends IntegrationTest {

    @Test
    void offlineSaleKeepsItsTimeAndIsSentOnlyOnce() {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        Instant madeAt = Instant.now().minus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("lines", line(sugar, 1), "idempotencyKey", key, "offlineAt", madeAt.toString(),
                "payments", List.of(Map.of("method", "CASH", "amount", 500)));

        Res first = post("/orders", owner, body);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.at("/offline").asBoolean()).isTrue();
        assertThat(first.at("/status").asText()).isEqualTo("PAID");
        assertThat(when(first.at("/closedAt"))).isEqualTo(madeAt);
        assertThat(when(first.at("/payments/0/createdAt"))).isEqualTo(madeAt);

        // The till retries after a timeout: same sale back, nothing doubled.
        Res again = post("/orders", owner, body);
        assertThat(again.at("/number").asInt()).isEqualTo(first.at("/number").asInt());
        long sales = 0;
        for (JsonNode o : get("/orders", owner).body()) {
            if (o.get("number").asInt() == first.at("/number").asInt()) {
                sales++;
            }
        }
        assertThat(sales).isEqualTo(1);
    }

    private static Instant when(JsonNode n) {
        return n.isNumber() ? Instant.ofEpochMilli(n.asLong()) : java.time.OffsetDateTime.parse(n.asText()).toInstant();
    }

    @Test
    void salesTooFarBackOrInTheFutureAreRefused() {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        for (Instant at : List.of(Instant.now().minus(Duration.ofDays(10)), Instant.now().plus(Duration.ofHours(1)))) {
            Res r = post("/orders", owner, Map.of("lines", line(sugar, 1), "idempotencyKey", UUID.randomUUID().toString(),
                    "offlineAt", at.toString(), "payments", List.of(Map.of("method", "CASH", "amount", 500))));
            assertThat(r.status()).isEqualTo(400);
        }
    }
}
