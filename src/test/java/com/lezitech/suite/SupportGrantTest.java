package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Granting LeziTech access twice never leaves two active grants. */
class SupportGrantTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    long active(String tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM support_grants WHERE tenant_id = ?::uuid AND stopped_at IS NULL AND expires_at > now()", Long.class, tenant);
    }

    @Test
    void aSecondGrantWhileOneIsActiveExtendsItInsteadOfAddingARow() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String tenant = s.at("/tenant/id").asText();
        assertThat(post("/support-access", owner, Map.of("note", "first")).at("/active").asBoolean()).isTrue();
        assertThat(active(tenant)).isEqualTo(1);
        assertThat(post("/support-access", owner, Map.of("note", "second")).at("/active").asBoolean()).isTrue();
        assertThat(active(tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_grants WHERE tenant_id = ?::uuid", Long.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT note FROM support_grants WHERE tenant_id = ?::uuid", String.class, tenant)).isEqualTo("second");
    }

    @Test
    void twoGrantsAtOnceLeaveOneActiveRow() throws Exception {
        JsonNode s = signup("retail");
        String owner = token(s);
        String tenant = s.at("/tenant/id").asText();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> go = () -> post("/support-access", owner, Map.of("note", "race")).status();
            List<Future<Integer>> done = pool.invokeAll(List.of(go, go));
            for (Future<Integer> f : done) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(active(tenant)).isEqualTo(1);
    }
}
