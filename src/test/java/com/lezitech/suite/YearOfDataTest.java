package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B: a year of a busy business. Seeds a café with 50,000 paid sales over the last year and a mall with
 * 200 units and two years of rent bills, then times every list and report a phone opens. Anything over a second
 * fails. Slow (a few minutes), so it only runs on request:
 * {@code mvnw -q test -Dtest=YearOfDataTest -Dyear=true}.
 */
@EnabledIfSystemProperty(named = "year", matches = "true")
class YearOfDataTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private static final long LIMIT_MS = 1000;

    /** Run an SQL script as the business (row-level security on), in one connection. */
    private void asTenant(String tenantId, String... sql) {
        jdbc.execute((ConnectionCallback<Void>) c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant', '" + tenantId + "', false)");
                for (String q : sql) {
                    s.execute(q);
                }
                s.execute("SELECT set_config('app.tenant', '', false)");
            }
            return null;
        });
    }

    /**
     * Fresh table statistics, as a running database keeps them by itself (autovacuum). Only the tables' owner may do
     * this, so it runs as the migrations' user, not the app's.
     */
    private void analyze() {
        String url = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getURL());
        try (var c = java.sql.DriverManager.getConnection(url, "postgres", ""); Statement s = c.createStatement()) {
            s.execute("ANALYZE");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Print how the database runs a query for this business (when -Dexplain=true), to find what's slow. */
    private void explain(String tenantId, String title, String sql) {
        if (!Boolean.getBoolean("explain")) {
            return;
        }
        jdbc.execute((ConnectionCallback<Void>) c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant', '" + tenantId + "', false)");
                StringBuilder plan = new StringBuilder("--- EXPLAIN " + title + System.lineSeparator());
                try (var rs = s.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql)) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append(System.lineSeparator());
                    }
                }
                s.execute("SELECT set_config('app.tenant', '', false)");
                System.out.println(plan);
            }
            return null;
        });
    }

    private long count(String tenantId, String sql) {
        return jdbc.execute((ConnectionCallback<Long>) c -> {
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant', '" + tenantId + "', false)");
                try (var rs = s.executeQuery(sql)) {
                    rs.next();
                    long n = rs.getLong(1);
                    s.execute("SELECT set_config('app.tenant', '', false)");
                    return n;
                }
            }
        });
    }

    /** The slowest of three calls (the first warms the cache), in milliseconds. */
    private long time(String path, String token) {
        long worst = 0;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            Res r = get(path, token);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.status()).as(path + " " + r.body()).isEqualTo(200);
            if (i > 0) {
                worst = Math.max(worst, ms);
            }
        }
        return worst;
    }

    private void report(String title, Map<String, Long> times) {
        System.out.println("=== " + title + " (ms, slowest of two warm calls) ===");
        times.forEach((k, v) -> System.out.printf("%6d  %s%s%n", v, k, v > LIMIT_MS ? "   <-- over 1 second" : ""));
    }

    @Test
    void aBusyCafeYear() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String tenant = s.at("/tenant/id").asText();
        String user = s.at("/user/id").asText();
        String location = "(SELECT id FROM locations WHERE tenant_id = '" + tenant + "' ORDER BY is_default DESC LIMIT 1)";
        // 50,000 paid sales spread over the last year, each with two lines and one payment; 2,000 customers.
        asTenant(tenant,
                "INSERT INTO customers (tenant_id, name, phone) SELECT '" + tenant + "', 'Customer ' || g, '07' || lpad(g::text, 8, '0') FROM generate_series(1, 2000) g",
                "INSERT INTO orders (tenant_id, number, kind, channel, status, location_id, user_id, customer_id, subtotal, total, paid, created_at, closed_at) "
                        + "SELECT '" + tenant + "', 100000 + g, 'SALE', 'TAKEAWAY', 'PAID', " + location + ", '" + user + "', "
                        + "CASE WHEN g % 5 = 0 THEN (SELECT id FROM customers WHERE tenant_id = '" + tenant + "' OFFSET (g % 2000) LIMIT 1) END, "
                        + "650, 650, 650, ts, ts FROM (SELECT g, now() - (random() * 365 || ' days')::interval AS ts FROM generate_series(1, 50000) g) x",
                "INSERT INTO order_lines (tenant_id, order_id, item_id, name, qty, unit_price, line_total, created_at) "
                        + "SELECT o.tenant_id, o.id, i.id, i.name, 1, 325, 325, o.created_at FROM orders o "
                        + "JOIN LATERAL (SELECT id, name FROM items WHERE tenant_id = o.tenant_id AND kind <> 'INGREDIENT' ORDER BY md5(o.id::text || id::text) LIMIT 2) i ON true "
                        + "WHERE o.number > 100000",
                "INSERT INTO payments (tenant_id, order_id, method, amount, user_id, created_at) "
                        + "SELECT tenant_id, id, CASE WHEN number % 5 < 3 THEN 'MPESA' ELSE 'CASH' END, total, user_id, created_at FROM orders WHERE number > 100000");
        analyze();
        assertThat(count(tenant, "SELECT count(*) FROM orders")).isGreaterThanOrEqualTo(50_000);

        String sold = "o.status IN ('PAID','CREDIT') AND o.kind <> 'QUOTE' AND o.channel <> 'DEPOSIT' AND o.closed_at >= date_trunc('month', now()) AND o.closed_at < now() + interval '1 day'";
        explain(tenant, "totals", "SELECT coalesce(sum(o.total), 0), count(*) FROM orders o WHERE " + sold);
        explain(tenant, "cost", "SELECT sum(l.qty * coalesce(l.pack_size, 1) * i.cost) FROM order_lines l JOIN orders o ON o.id = l.order_id JOIN items i ON i.id = l.item_id WHERE " + sold);
        explain(tenant, "byItem", "SELECT l.name, sum(l.qty), sum(l.line_total) AS amount FROM order_lines l JOIN orders o ON o.id = l.order_id WHERE " + sold + " GROUP BY l.name ORDER BY amount DESC LIMIT 50");
        explain(tenant, "byStaff", "SELECT u.name, count(DISTINCT o.id), sum(l.line_total) FROM order_lines l JOIN orders o ON o.id = l.order_id JOIN users u ON u.id = coalesce(l.staff_id, o.user_id) WHERE " + sold + " GROUP BY u.name");
        explain(tenant, "byMethod", "SELECT p.method, sum(p.amount) FROM payments p WHERE p.created_at >= date_trunc('month', now()) GROUP BY p.method");
        explain(tenant, "ordersQ", "SELECT o.* FROM orders o LEFT JOIN customers c ON c.id = o.customer_id WHERE (c.name ILIKE '%1001%' OR o.number::text = '1001') ORDER BY o.created_at DESC LIMIT 500");

        LocalDate today = LocalDate.now();
        String year = "from=" + today.minusDays(365) + "&to=" + today;
        String month = "from=" + today.withDayOfMonth(1) + "&to=" + today;
        Map<String, Long> t = new LinkedHashMap<>();
        for (String path : List.of(
                "/reports/dashboard", "/orders?status=PAID&kind=SALE", "/orders?status=OPEN&kind=SALE", "/orders?q=1001",
                "/reports/sales?" + month, "/reports/sales?" + year, "/reports/profit-loss?" + year, "/reports/back-office?" + year,
                "/reports/customers?" + year, "/reports/commissions?" + month, "/customers", "/customers?q=Customer%201999",
                "/customers/owing", "/items", "/shifts", "/first-steps", "/expenses?" + year)) {
            t.put(path, time(path, owner));
        }
        report("Café, 50,000 sales in a year", t);
        assertThat(t).allSatisfy((path, ms) -> assertThat(ms).as(path).isLessThanOrEqualTo(LIMIT_MS));
    }

    @Test
    void aMallWith200UnitsAndTwoYearsOfRent() {
        JsonNode s = signup("property");
        String owner = token(s);
        LocalDate start = YearMonth.now().minusMonths(23).atDay(1);
        for (int i = 1; i <= 200; i++) {
            String name = String.format("U%03d", i);
            Res unit = post("/spaces", owner, Map.of("name", name, "kind", "SHOP", "rate", 20000 + i * 100, "capacity", 1));
            assertThat(unit.status()).as(unit.body().toString()).isEqualTo(200);
            Map<String, Object> lease = new HashMap<>(Map.of("spaceId", unit.at("/id").asText(), "tenantName", "Tenant " + name,
                    "rent", 20000 + i * 100, "serviceCharge", 2000, "startsOn", start.toString()));
            Res l = post("/property/leases", owner, lease);
            assertThat(l.status()).as(l.body().toString()).isEqualTo(200);
        }
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.now()); m = m.plusMonths(1)) {
            Res r = post("/property/invoices/generate", owner, Map.of("period", m.toString()));
            assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        }
        String tenant = s.at("/tenant/id").asText();
        analyze();
        assertThat(count(tenant, "SELECT count(*) FROM orders WHERE channel = 'RENT'")).isGreaterThanOrEqualTo(4_000);

        String lease = get("/property/units", owner).body().get(0).path("leaseId").asText();
        String period = YearMonth.now().toString();
        Map<String, Long> t = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>(List.of(
                "/property/units", "/property/rent-roll?period=" + period, "/property/arrears", "/property/attention",
                "/property/paybill", "/property/maintenance?show=open", "/property/landlords?period=" + period,
                "/property/directory", "/property/to-let", "/property/map", "/reports/dashboard",
                "/property/service-charge/" + YearMonth.now().getYear()));
        if (!lease.isBlank()) {
            paths.add("/property/leases/" + lease + "/statement?from=" + start + "&to=" + LocalDate.now());
            paths.add("/property/leases/" + lease + "/history");
        }
        for (String path : paths) {
            t.put(path, time(path, owner));
        }
        report("Mall, 200 units, two years of rent", t);
        assertThat(t).allSatisfy((path, ms) -> assertThat(ms).as(path).isLessThanOrEqualTo(LIMIT_MS));
    }
}
