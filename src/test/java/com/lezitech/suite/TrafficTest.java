package com.lezitech.suite;

import com.lezitech.suite.support.IntegrationTest;
import com.lezitech.suite.traffic.TrafficController;
import com.lezitech.suite.traffic.VisitSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Where visitors come from: page views are stored and sorted into sources, bots are dropped, and the report adds up. */
class TrafficTest extends IntegrationTest {

    static final String PHONE = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TrafficController traffic;

    @BeforeEach
    void fresh() {
        TrafficController.PER_CLIENT.clear();
        jdbc.update("DELETE FROM visits");
    }

    int beacon(String userAgent, Map<String, Object> body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public/visit"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (userAgent != null) {
                b.header("User-Agent", userAgent);
            }
            return HttpClient.newHttpClient().send(b.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> view(Object... kv) {
        Map<String, Object> m = new HashMap<>(Map.of("site", "website", "path", "/", "device", "phone", "visitor", UUID.randomUUID().toString(), "first", true));
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    String sourceOfLast() {
        return jdbc.queryForObject("SELECT source FROM visits ORDER BY id DESC LIMIT 1", String.class);
    }

    @Test
    void aVisitIsStoredAndSortedIntoASource() {
        assertThat(beacon(PHONE, view("referrer", "www.google.com"))).isEqualTo(204);
        assertThat(sourceOfLast()).isEqualTo("google_search");
        beacon(PHONE, view("utm_source", "google", "utm_medium", "cpc", "utm_campaign", "nov"));
        assertThat(sourceOfLast()).isEqualTo("google_ads");
        beacon(PHONE, view("gclid", true));
        assertThat(sourceOfLast()).isEqualTo("google_ads");
        beacon(PHONE, view("referrer", "https://wa.me/254700000000"));
        assertThat(sourceOfLast()).isEqualTo("whatsapp");
        beacon(PHONE, view("referrer", "l.facebook.com"));
        assertThat(sourceOfLast()).isEqualTo("facebook");
        beacon(PHONE, view("referrer", "t.co"));
        assertThat(sourceOfLast()).isEqualTo("x");
        beacon(PHONE, view("utm_medium", "email"));
        assertThat(sourceOfLast()).isEqualTo("email");
        beacon(PHONE, view("referrer", "www.tiktok.com"));
        assertThat(sourceOfLast()).isEqualTo("tiktok");
        beacon(PHONE, view());
        assertThat(sourceOfLast()).isEqualTo("direct");
        beacon(PHONE, view("referrer", "busynez.site"));
        assertThat(sourceOfLast()).isEqualTo("direct");
        beacon(PHONE, view("referrer", "https://www.someblog.co.ke/post?id=1"));
        Map<String, Object> last = jdbc.queryForMap("SELECT source, source_detail, referrer_host, device, first_visit FROM visits ORDER BY id DESC LIMIT 1");
        assertThat(last.get("source")).isEqualTo("other_site");
        assertThat(last.get("source_detail")).isEqualTo("someblog.co.ke");
        assertThat(last.get("referrer_host")).isEqualTo("someblog.co.ke");
        assertThat(last.get("device")).isEqualTo("phone");
        assertThat(last.get("first_visit")).isEqualTo(true);
        assertThat(VisitSource.classify(null, "newsletter", null, false, false).key()).isEqualTo("email");
    }

    @Test
    void botsAndOddViewsAreDropped() {
        assertThat(beacon("Googlebot/2.1 (+http://www.google.com/bot.html)", view())).isEqualTo(204);
        assertThat(beacon("curl/8.0", view())).isEqualTo(204);
        assertThat(beacon("Java-http-client/21", view())).isEqualTo(204);
        assertThat(beacon(PHONE, view("site", "somewhere-else"))).isEqualTo(204);
        assertThat(beacon(PHONE, view("device", "fridge"))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visits", Long.class)).isZero();
    }

    @Test
    void longFieldsAreCutAndNoAddressOrBrowserTextIsKept() {
        String big = "x".repeat(500);
        beacon(PHONE, view("path", "/" + big, "utm_campaign", big, "utm_source", big, "referrer", big + ".com", "visitor", "not valid"));
        Map<String, Object> r = jdbc.queryForMap("SELECT path, utm_campaign, utm_source, referrer_host, visitor_id FROM visits");
        assertThat(((String) r.get("path")).length()).isEqualTo(200);
        assertThat(((String) r.get("utm_campaign")).length()).isEqualTo(100);
        assertThat(((String) r.get("utm_source")).length()).isEqualTo(100);
        assertThat(((String) r.get("referrer_host")).length()).isLessThanOrEqualTo(100);
        assertThat(r.get("visitor_id")).isNull();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'visits'", String.class))
                .noneMatch(c -> c.contains("agent") || c.equals("ip") || c.contains("address"));
    }

    @Test
    void oneAddressCannotFloodTheTable() {
        int refused = 0;
        for (int i = 0; i < 305; i++) {
            if (beacon(PHONE, view()) == 429) {
                refused++;
            }
        }
        assertThat(refused).isEqualTo(5);
    }

    @Test
    void signupKeepsTheFirstTouchAndTheReportAddsUp() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res s = post("/auth/signup", null, Map.of("businessName", "Came From Google Shop", "sector", "retail", "name", "Owner", "email", email,
                "password", "password123", "withSamples", false,
                "acquisition", Map.of("referrer", "www.google.com", "landing", "/sectors/shops", "utm_campaign", "brand")));
        assertThat(s.status()).as(s.body().toString()).isEqualTo(200);
        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, email);
        Map<String, Object> src = jdbc.queryForMap("SELECT signup_source->>'source' AS source, signup_source->>'campaign' AS campaign FROM tenants WHERE id = ?", tenant);
        assertThat(src.get("source")).isEqualTo("google_search");
        assertThat(src.get("campaign")).isEqualTo("brand");

        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
        assertThat(get("/admin/tenants/" + tenant, admin).at("/signupSource/source").asText()).isEqualTo("google_search");

        String v1 = UUID.randomUUID().toString();
        String v2 = UUID.randomUUID().toString();
        beacon(PHONE, view("visitor", v1, "referrer", "google.com"));
        beacon(PHONE, view("visitor", v1, "referrer", "google.com", "first", false, "path", "/pricing"));
        beacon(PHONE, view("visitor", v2, "device", "desktop"));
        jdbc.update("INSERT INTO visits (created_at, site, device, source) VALUES (now() - interval '60 days', 'website', 'phone', 'direct')");

        for (String role : new String[]{"ADMIN", "SUPERADMIN", "SALES"}) {
            String t = post("/admin/auth/demo", null, Map.of("role", role)).at("/token").asText();
            Res r = get("/admin/traffic?range=7", t);
            assertThat(r.status()).as(role).isEqualTo(200);
            assertThat(r.at("/visits").asLong()).isEqualTo(3);
            assertThat(r.at("/visitors").asLong()).isEqualTo(2);
            assertThat(r.at("/signups").asLong()).isGreaterThanOrEqualTo(1);
        }
        Res r = get("/admin/traffic?range=7", admin);
        assertThat(row(r, "google_search", "visits")).isEqualTo(2);
        assertThat(row(r, "google_search", "visitors")).isEqualTo(1);
        assertThat(row(r, "google_search", "signups")).isEqualTo(1);
        assertThat(row(r, "direct", "visits")).isEqualTo(1);
        assertThat(r.at("/devices").toString()).contains("desktop").contains("phone");
        assertThat(r.at("/referrers/0/host").asText()).isEqualTo("google.com");
        assertThat(r.at("/perDay").size()).isEqualTo(7);
        long barTotal = 0;
        for (var d : r.at("/perDay")) {
            barTotal += d.get("visits").asLong();
        }
        assertThat(barTotal).isEqualTo(3);
        assertThat(get("/admin/traffic?range=90", admin).at("/visits").asLong()).isEqualTo(4);
        assertThat(get("/admin/traffic?range=today", admin).at("/visits").asLong()).isEqualTo(3);
    }

    static long row(Res r, String source, String field) {
        for (var n : r.at("/sources")) {
            if (n.get("source").asText().equals(source)) {
                return n.get(field).asLong();
            }
        }
        return -1;
    }

    @Test
    void rolesWithoutThePermissionAreRefused() {
        for (String role : new String[]{"SUPPORT", "DEVELOPER"}) {
            String t = post("/admin/auth/demo", null, Map.of("role", role)).at("/token").asText();
            assertThat(get("/admin/traffic?range=7", t).status()).as(role).isEqualTo(403);
        }
        assertThat(get("/admin/traffic?range=7", null).status()).isIn(401, 403);
        assertThat(get("/admin/traffic?range=7", token(signup("retail"))).status()).isIn(401, 403);
    }

    @Test
    void oldViewsAreDeletedAfter180Days() {
        jdbc.update("INSERT INTO visits (created_at, site, device, source) VALUES (now() - interval '181 days', 'website', 'phone', 'direct'), (now() - interval '179 days', 'website', 'phone', 'direct')");
        assertThat(traffic.purge()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visits", Long.class)).isEqualTo(1);
    }
}
