package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** A double tap, a retry, or two tills at once must record a payment only once. */
class DoublePaymentTest extends IntegrationTest {

    @Test
    void sameKeyRecordsOnePaymentEvenWhenSentTogether() throws Exception {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        String order = post("/orders", owner, Map.of("lines", line(sugar, 1))).at("/id").asText();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("idempotencyKey", key, "payments", List.of(Map.of("method", "MPESA", "amount", 190, "reference", "QWE")));

        List<Res> results = parallel(5, () -> post("/orders/" + order + "/pay", owner, body));
        assertThat(results).allMatch(r -> r.status() == 200);
        JsonNode detail = get("/orders/" + order, owner).body();
        assertThat(detail.get("payments")).hasSize(1);
        assertThat(detail.get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void retriedOneStepSaleCreatesOneSale() throws Exception {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        Map<String, Object> body = Map.of("idempotencyKey", UUID.randomUUID().toString(), "lines", line(sugar, 1),
                "payments", List.of(Map.of("method", "CASH", "amount", 200)));
        List<Res> results = parallel(4, () -> post("/orders", owner, body));
        assertThat(results.stream().map(r -> r.at("/id").asText()).distinct()).hasSize(1);
        assertThat(get("/orders", owner).body()).hasSize(1);
        assertThat(item(owner, "Sugar").get("onHand").asInt()).isEqualTo(5);
    }

    @Test
    void twoTillsPayingTheSameBillWithoutAKeyOnlyOneWins() throws Exception {
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        String order = post("/orders", owner, Map.of("lines", line(sugar, 1))).at("/id").asText();
        Map<String, Object> body = Map.of("payments", List.of(Map.of("method", "CASH", "amount", 190)));
        List<Res> results = parallel(4, () -> post("/orders/" + order + "/pay", owner, body));
        assertThat(results.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 409)).hasSize(3);
        assertThat(get("/orders/" + order, owner).at("/payments")).hasSize(1);
    }

    @Test
    void accountSalesCantSlipPastTheCreditLimitTogether() throws Exception {
        String owner = token(signup("retail"));
        JsonNode customer = post("/customers", owner, Map.of("name", "Credit Kamau", "creditLimit", 300)).body();
        String sugar = item(owner, "Sugar").get("id").asText();
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            orders.add(post("/orders", owner, Map.of("customerId", customer.get("id").asText(), "lines", line(sugar, 1))).at("/id").asText());
        }
        List<Callable<Res>> calls = new ArrayList<>();
        for (String o : orders) {
            calls.add(() -> post("/orders/" + o + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CREDIT", "amount", 190)))));
        }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        int ok = 0;
        for (Future<Res> f : pool.invokeAll(calls)) {
            if (f.get().status() == 200) {
                ok++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(get("/customers/" + customer.get("id").asText(), owner).at("/balance").decimalValue()).isEqualByComparingTo("190");
    }

    private static List<Res> parallel(int n, Callable<Res> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Callable<Res>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(call);
        }
        List<Res> out = new ArrayList<>();
        for (Future<Res> f : pool.invokeAll(calls)) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}
