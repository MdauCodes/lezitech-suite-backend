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

    /** Seeded views give exact numbers in every part of the report. */
    @Test
    void reportNumbersOnSeededVisits() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String ins = "INSERT INTO visits (created_at, site, path, referrer_host, device, visitor_id, first_visit, source) VALUES ";
        jdbc.update(ins + "(now(), 'website', '/', 'google.com', 'phone', ?, true, 'google_search'),"
                + "(now(), 'website', '/pricing', 'google.com', 'phone', ?, false, 'google_search'),"
                + "(now(), 'website', '/', NULL, 'desktop', ?, true, 'direct'),"
                + "(now(), 'website', NULL, NULL, 'desktop', ?, true, 'direct'),"
                + "(now() - interval '60 days', 'website', '/', NULL, 'phone', ?, true, 'direct')", a, a, b, b, a);
        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
        Res r = get("/admin/traffic?range=7", admin);
        assertThat(r.at("/visits").asLong()).isEqualTo(4);
        assertThat(r.at("/visitors").asLong()).isEqualTo(2);
        assertThat(row(r, "google_search", "visits")).isEqualTo(2);
        assertThat(row(r, "google_search", "visitors")).isEqualTo(1);
        assertThat(row(r, "direct", "visits")).isEqualTo(2);
        assertThat(row(r, "direct", "visitors")).isEqualTo(1);
        // Pages count first visits only; a missing path shows as "/".
        assertThat(r.at("/pages").size()).isEqualTo(1);
        assertThat(r.at("/pages/0/path").asText()).isEqualTo("/");
        assertThat(r.at("/pages/0/visits").asLong()).isEqualTo(3);
        assertThat(r.at("/pages/0/visitors").asLong()).isEqualTo(2);
        assertThat(r.at("/referrers").size()).isEqualTo(1);
        assertThat(r.at("/referrers/0/host").asText()).isEqualTo("google.com");
        assertThat(r.at("/referrers/0/visits").asLong()).isEqualTo(2);
        assertThat(r.at("/referrers/0/visitors").asLong()).isEqualTo(1);
        assertThat(r.at("/devices").size()).isEqualTo(2);
        assertThat(r.at("/devices/0/visits").asLong()).isEqualTo(2);
        assertThat(r.at("/devices/1/visits").asLong()).isEqualTo(2);
        assertThat(r.at("/perDay").size()).isEqualTo(7);
        assertThat(r.at("/perDay/6/visits").asLong()).isEqualTo(4);
        assertThat(r.at("/perDay/0/visits").asLong()).isZero();
        assertThat(get("/admin/traffic?range=90", admin).at("/visits").asLong()).isEqualTo(5);
    }

    String[] kindOfLast() {
        Map<String, Object> m = jdbc.queryForMap("SELECT sector, sub_sector FROM visits ORDER BY id DESC LIMIT 1");
        return new String[]{(String) m.get("sector"), (String) m.get("sub_sector")};
    }

    /** A real business with a given kind, sub-kind and payment state; returns its slug. */
    String business(String sector, String subName, boolean paying, String createdAgo) {
        var s = signup(sector);
        UUID id = jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, s.get("email").asText());
        jdbc.update("UPDATE tenants SET sub_sector = ?, status = ?, paid_until = ?, created_at = now() - cast(? as interval) WHERE id = ?",
                subName, paying ? "ACTIVE" : "TRIAL", java.sql.Timestamp.from(java.time.Instant.now().plus(paying ? 20 : -1, java.time.temporal.ChronoUnit.DAYS)), createdAgo, id);
        return jdbc.queryForObject("SELECT slug FROM tenants WHERE id = ?", String.class, id);
    }

    @Test
    void aSectorPageIdIsStoredAsTheKeyAndUnknownValuesAreDropped() {
        beacon(PHONE, view("path", "/sectors/shops/minimart-supermarket", "sector", "shops", "sub", "minimart-supermarket"));
        assertThat(kindOfLast()).containsExactly("retail", "minimart");
        beacon(PHONE, view("path", "/sectors/safaris", "sector", "safaris"));
        assertThat(kindOfLast()).containsExactly("tours", null);
        beacon(PHONE, view("site", "app", "path", "/start", "sector", "retail", "sub", "boutique"));
        assertThat(kindOfLast()).containsExactly("retail", "boutique");
        beacon(PHONE, view("sector", "<script>"));
        assertThat(kindOfLast()).containsExactly(null, null);
        beacon(PHONE, view("sector", "shops", "sub", "no-such-thing"));
        assertThat(kindOfLast()).containsExactly("retail", null);
        beacon(PHONE, view("sector", "shops", "sub", "cafe"));
        assertThat(kindOfLast()).containsExactly("retail", null);
        beacon(PHONE, view("sub", "minimart"));
        assertThat(kindOfLast()).containsExactly(null, null);
    }

    @Test
    void aBusinessPageVisitTakesTheBusinessOwnKindNotTheClients() {
        String slug = business("retail", "Boutique & fashion", true, "1 day");
        beacon(PHONE, view("site", "business-page", "path", "/t/" + slug, "sector", "hotel", "sub", "lodge"));
        assertThat(kindOfLast()).containsExactly("retail", "boutique");
        beacon(PHONE, view("site", "business-page", "path", "/t/no-such-business-here", "sector", "hotel"));
        assertThat(kindOfLast()).containsExactly(null, null);
    }

    @Test
    void theReportGroupsByBusinessTypeAndCountsSignupsAndPaying() {
        // Counts are compared before and after, so businesses made by other tests do not matter.
        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
        Res before = get("/admin/traffic?range=7", admin);
        long signupsBefore = kind(before, "retail", "signups");
        long payingBefore = kind(before, "retail", "payingNow");
        long hotelSignupsBefore = kind(before, "hotel", "signups");
        long boutiqueBefore = subOr0(before, "retail", "boutique", "signups");
        long minimartBefore = subOr0(before, "retail", "minimart", "signups");

        String slug = business("retail", "Boutique & fashion", true, "1 day");
        business("retail", "Minimart / supermarket", false, "2 days");
        business("retail", "Minimart / supermarket", true, "100 days");
        business("hotel", null, false, "1 day");
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        beacon(PHONE, view("visitor", a, "path", "/sectors/shops", "sector", "shops"));
        beacon(PHONE, view("visitor", a, "path", "/sectors/shops/boutique", "sector", "shops", "sub", "boutique"));
        beacon(PHONE, view("visitor", b, "path", "/sectors/shops/boutique", "sector", "shops", "sub", "boutique"));
        beacon(PHONE, view("visitor", b, "site", "app", "path", "/start", "sector", "retail", "sub", "boutique"));
        beacon(PHONE, view("visitor", b, "site", "business-page", "path", "/t/" + slug));
        beacon(PHONE, view("visitor", a, "site", "business-page", "path", "/t/" + slug));
        beacon(PHONE, view("visitor", a, "site", "business-page", "path", "/t/" + slug + "/menu"));

        Res r = get("/admin/traffic?range=7", admin);
        assertThat(r.status()).isEqualTo(200);
        assertThat(kind(r, "retail", "websiteVisits")).isEqualTo(3);
        assertThat(kind(r, "retail", "websiteVisitors")).isEqualTo(2);
        assertThat(kind(r, "retail", "signupPageOpens")).isEqualTo(1);
        assertThat(kind(r, "retail", "signups") - signupsBefore).isEqualTo(2);
        assertThat(kind(r, "retail", "payingNow") - payingBefore).isEqualTo(2);
        assertThat(kind(r, "hotel", "signups") - hotelSignupsBefore).isEqualTo(1);
        assertThat(kind(r, "hotel", "websiteVisits")).isZero();
        var retail = row(r, "retail");
        double rate = retail.get("rate").asDouble();
        assertThat(rate).isEqualTo(Math.round(retail.get("signups").asLong() * 1000.0 / 2) / 10.0);
        var boutique = sub(retail, "boutique");
        assertThat(boutique.get("websiteVisits").asLong()).isEqualTo(2);
        assertThat(boutique.get("websiteVisitors").asLong()).isEqualTo(2);
        assertThat(boutique.get("signupPageOpens").asLong()).isEqualTo(1);
        assertThat(boutique.get("signups").asLong() - boutiqueBefore).isEqualTo(1);
        assertThat(boutique.get("payingNow").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(sub(retail, "minimart").get("signups").asLong() - minimartBefore).isEqualTo(1);
        // Business pages: three visits, two different visitors, all in retail; this business is the top one.
        assertThat(r.at("/businessPages/bySector/0/key").asText()).isEqualTo("retail");
        assertThat(r.at("/businessPages/bySector/0/visits").asLong()).isEqualTo(3);
        assertThat(r.at("/businessPages/bySector/0/visitors").asLong()).isEqualTo(2);
        assertThat(r.at("/businessPages/top/0/visits").asLong()).isEqualTo(3);
        assertThat(r.at("/businessPages/top/0/sector").asText()).isEqualTo("Shops & minimarts");
        assertThat(r.at("/businessPages/top").size()).isEqualTo(1);
        assertThat(get("/admin/traffic?range=today", admin).at("/byKind").isArray()).isTrue();
    }

    static com.fasterxml.jackson.databind.JsonNode row(Res r, String sector) {
        for (var n : r.at("/byKind")) {
            if (n.get("key").asText().equals(sector)) {
                return n;
            }
        }
        throw new AssertionError("no sector " + sector);
    }

    static com.fasterxml.jackson.databind.JsonNode sub(com.fasterxml.jackson.databind.JsonNode sector, String key) {
        for (var n : sector.get("subs")) {
            if (n.get("key").asText().equals(key)) {
                return n;
            }
        }
        throw new AssertionError("no sub " + key);
    }

    static long subOr0(Res r, String sector, String key, String field) {
        for (var n : row(r, sector).get("subs")) {
            if (n.get("key").asText().equals(key)) {
                return n.get(field).asLong();
            }
        }
        return 0;
    }

    static long kind(Res r, String sector, String field) {
        return row(r, sector).get(field).asLong();
    }

    /** The V83 backfill, run again on old-style rows: website sector pages and business pages get their sector. */
    @Test
    void theV83BackfillGivesOldRowsTheirSector() throws Exception {
        String slug = business("hotel", null, true, "1 day");
        String ins = "INSERT INTO visits (site, path, device, source) VALUES ";
        jdbc.update(ins + "('website', '/sectors/shops', 'phone', 'direct'), ('website', '/sectors/safaris/lodge', 'phone', 'direct'),"
                + "('website', '/pricing', 'phone', 'direct'), ('business-page', '/t/" + slug + "', 'phone', 'direct'),"
                + "('business-page', '/t/nobody-here', 'phone', 'direct')");
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V83__visits_by_kind.sql"));
        try (var c = java.sql.DriverManager.getConnection(jdbc.getDataSource().getConnection().getMetaData().getURL(), "postgres", ""); var st = c.createStatement()) {
            st.execute(sql);
        }
        Map<String, String> got = new HashMap<>();
        jdbc.query("SELECT path, sector FROM visits", rs -> {
            got.put(rs.getString("path"), rs.getString("sector"));
        });
        assertThat(got.get("/sectors/shops")).isEqualTo("retail");
        assertThat(got.get("/sectors/safaris/lodge")).isEqualTo("tours");
        assertThat(got.get("/pricing")).isNull();
        assertThat(got.get("/t/" + slug)).isEqualTo("hotel");
        assertThat(got.get("/t/nobody-here")).isNull();
    }

    /** V82 never fails on the extension; if pg_trgm is there, the search indexes are too. */
    @Test
    void theSearchIndexesExistWhenTheExtensionIs() {
        boolean ext = Boolean.TRUE.equals(jdbc.queryForObject("SELECT exists(SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm')", Boolean.class));
        System.out.println("PG_TRGM_CREATED=" + ext);
        long idx = jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('ix_tenants_name_trgm', 'ix_tenants_slug_trgm', 'ix_users_email_trgm')", Long.class);
        assertThat(idx).isEqualTo(ext ? 3 : 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('ix_tenants_created_id', 'ix_subreq_offer_tenant_status', 'ix_offers_active_ends', 'ix_audit_tenant_time')", Long.class)).isEqualTo(4);
    }
}
