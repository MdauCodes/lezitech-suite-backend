package com.lezitech.suite;

import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every table that holds a business's data must be fenced by row-level security, so one business can never read or
 * change another's. A new table without the fence fails this test.
 *
 * A few tables are deliberately not fenced: they are read before anyone is signed in (sign-in, M-Pesa callbacks),
 * or only by the LeziTech control panel. Code that touches them always filters by business itself.
 */
class RlsCoverageTest extends IntegrationTest {

    /** Not fenced on purpose, with the reason. */
    static final Map<String, String> UNFENCED = Map.ofEntries(
            Map.entry("tenants", "the businesses themselves; always read by id"),
            Map.entry("users", "sign-in looks people up by email before a business is known"),
            Map.entry("subscription_requests", "plan payments; worked by the LeziTech control panel"),
            Map.entry("mpesa_requests", "Safaricom's callback arrives without a sign-in; matched by secret token"),
            Map.entry("mpesa_c2b", "Safaricom's till payment arrives without a sign-in; matched by till number"),
            Map.entry("etims_queue", "background retry job works across businesses, then acts as each one"),
            Map.entry("trial_nudges", "trial reminder job and control panel only"),
            Map.entry("setup_requests", "website form and control panel only"),
            Map.entry("login_codes", "6-digit email codes, used before sign-in"),
            Map.entry("marketing_events", "ad conversion queue sent by a background job"),
            Map.entry("platform_audit", "LeziTech control panel's own log"),
            Map.entry("integrations", "encrypted payment and SMS keys; read by business id in code"),
            Map.entry("email_jobs", "background email sender works across businesses, then acts as each one"),
            Map.entry("problems", "shown to the business by its id and to the LeziTech control panel"));

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyBusinessTableIsFenced() {
        List<Map<String, Object>> tables = jdbc.queryForList("""
                SELECT c.relname AS name, c.relrowsecurity AS rls, c.relforcerowsecurity AS forced,
                       EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid) AS has_policy
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                  AND EXISTS (SELECT 1 FROM information_schema.columns col
                              WHERE col.table_schema = 'public' AND col.table_name = c.relname AND col.column_name = 'tenant_id')
                ORDER BY 1""");
        assertThat(tables).isNotEmpty();
        List<String> open = tables.stream()
                .filter(t -> !UNFENCED.containsKey((String) t.get("name")))
                .filter(t -> !(Boolean.TRUE.equals(t.get("rls")) && Boolean.TRUE.equals(t.get("forced")) && Boolean.TRUE.equals(t.get("has_policy"))))
                .map(t -> (String) t.get("name")).toList();
        assertThat(open).as("business tables without row-level security").isEmpty();
        // Keep the list honest: every name on it must be a real, unfenced table.
        List<String> unfencedNow = tables.stream().filter(t -> !Boolean.TRUE.equals(t.get("rls"))).map(t -> (String) t.get("name")).toList();
        List<String> withTenant = tables.stream().map(t -> (String) t.get("name")).toList();
        for (String name : UNFENCED.keySet()) {
            if (withTenant.contains(name)) {
                assertThat(unfencedNow).as(name + " is fenced now; take it off the list").contains(name);
            }
        }
        System.out.println("RLS: " + (tables.size() - unfencedNow.size()) + " fenced business tables, unfenced on purpose: " + unfencedNow);
    }

    @Test
    void theAppSignsInToTheDatabaseAsAnOrdinaryUser() {
        // Row-level security does not apply to the table owner or a superuser, so the app must not be either.
        Map<String, Object> me = jdbc.queryForMap("SELECT current_user AS name, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
        assertThat(me.get("name")).isEqualTo("suite_app");
        assertThat(me.get("rolsuper")).isEqualTo(false);
        assertThat(me.get("rolbypassrls")).isEqualTo(false);
        Set<String> owners = Set.copyOf(jdbc.queryForList("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'", String.class));
        assertThat(owners).doesNotContain("suite_app");
    }

    @Test
    void withoutABusinessSetTheFencedTablesShowNothing() {
        signup("retail");
        // Outside a request there is no business set, so a fenced table must come back empty, not show everyone's rows.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM items", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
    }
}
