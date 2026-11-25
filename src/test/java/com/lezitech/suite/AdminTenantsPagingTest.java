package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The workspace list in the control panel pages by cursor, searches literally, and still answers old callers with an array. */
class AdminTenantsPagingTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    String admin() {
        return post("/admin/auth/demo", null, Map.of("role", "SUPERADMIN")).at("/token").asText();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    String tag() {
        return "pg" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** created_at is "now minus secondsAgo" plus 123456 microseconds, so it carries full microseconds. */
    UUID add(String name, String status, int secondsAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tenants (id, slug, name, sector, status, trial_ends_at, created_at)
                VALUES (?, ?, ?, 'retail', ?, now() + interval '10 days', date_trunc('second', now()) - make_interval(secs => ?) + interval '123456 microseconds')""",
                id, "s-" + id, name, status, (double) secondsAgo);
        return id;
    }

    void owner(UUID tenant, String email) {
        jdbc.update("INSERT INTO users (tenant_id, email, name, password_hash, role) VALUES (?, ?, 'Owner', 'x', 'OWNER')", tenant, email);
    }

    List<String> allPages(String admin, String query, int limit) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            Res r = get("/admin/tenants?limit=" + limit + query + (cursor == null ? "" : "&before=" + enc(cursor)), admin);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.at("/items").size()).isLessThanOrEqualTo(limit);
            r.at("/items").forEach(i -> ids.add(i.get("id").asText()));
            cursor = r.at("/next").isNull() ? null : r.at("/next").asText();
        } while (cursor != null && ++guard < 50);
        assertThat(cursor).isNull();
        return ids;
    }

    @Test
    void pagesThroughEveryBusinessOnceInOrderEvenWithEqualTimes() {
        String t = tag();
        for (int i = 0; i < 118; i++) {
            add(t + " biz " + i, "TRIAL", i + 1);
        }
        // two more with exactly the same created_at as business 0
        add(t + " twin a", "TRIAL", 1);
        add(t + " twin b", "TRIAL", 1);
        String admin = admin();
        List<String> seen = allPages(admin, "&q=" + t, 50);
        List<String> expected = jdbc.queryForList("SELECT id::text FROM tenants WHERE name LIKE ? ORDER BY created_at DESC, id DESC", String.class, t + "%");
        assertThat(expected).hasSize(120);
        assertThat(seen).hasSize(120).doesNotHaveDuplicates().containsExactlyElementsOf(expected);

        Res first = get("/admin/tenants?limit=50&q=" + t, admin);
        assertThat(first.at("/items").size()).isEqualTo(50);
        assertThat(first.at("/next").asText()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{6}Z~[0-9a-f-]{36}");
        // limit is clamped to 1..100
        assertThat(get("/admin/tenants?limit=500&q=" + t, admin).at("/items").size()).isEqualTo(100);
        assertThat(get("/admin/tenants?limit=0&q=" + t, admin).at("/items").size()).isEqualTo(1);
    }

    @Test
    void searchesNameAndOwnerEmailAndIgnoresShortTerms() {
        String t = tag();
        UUID a = add(t + " Mango Traders", "TRIAL", 5);
        UUID b = add(t + " Plain Shop", "TRIAL", 6);
        owner(b, "boss-" + t + "@zebra-mail.test");
        String admin = admin();
        assertThat(ids(get("/admin/tenants?limit=20&q=" + enc("mango trad"), admin))).containsExactly(a.toString());
        assertThat(ids(get("/admin/tenants?limit=20&q=" + enc("boss-" + t + "@zebra"), admin))).containsExactly(b.toString());
        assertThat(ids(get("/admin/tenants?limit=20&q=" + enc(t), admin))).containsExactlyInAnyOrder(a.toString(), b.toString());
        // one character is ignored: nothing is filtered by it
        assertThat(get("/admin/tenants?limit=100&q=z", admin).at("/items").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void percentAndUnderscoreAreTakenLiterally() {
        String t = tag();
        UUID sale = add(t + " 50%_off store", "TRIAL", 5);
        UUID abc = add(t + " abc store", "TRIAL", 6);
        String admin = admin();
        assertThat(ids(get("/admin/tenants?limit=20&q=" + enc("%_"), admin))).contains(sale.toString()).doesNotContain(abc.toString());
        assertThat(ids(get("/admin/tenants?limit=20&q=" + enc("a_c " + "store"), admin))).doesNotContain(abc.toString());
        assertThat(ids(get("/admin/tenants?limit=20&q=" + enc("abc"), admin))).contains(abc.toString());
        assertThat(ids(get("/admin/tenants?limit=20&q=" + enc("50%_off"), admin))).containsExactly(sale.toString());
        assertThat(get("/admin/tenants?q=" + enc("a\\c"), admin).status()).isEqualTo(200);
    }

    @Test
    void statusFilterWorksWithSearchAndPaging() {
        String t = tag();
        List<String> active = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            UUID id = add(t + " act " + i, "ACTIVE", i + 1);
            jdbc.update("UPDATE tenants SET paid_until = now() + interval '30 days' WHERE id = ?", id);
            active.add(id.toString());
        }
        for (int i = 0; i < 5; i++) {
            add(t + " trial " + i, "TRIAL", i + 20);
        }
        String admin = admin();
        List<String> got = allPages(admin, "&q=" + t + "&status=ACTIVE", 3);
        assertThat(got).hasSize(7).containsExactlyInAnyOrderElementsOf(active).doesNotHaveDuplicates();
        assertThat(allPages(admin, "&q=" + enc(t + " trial") + "&status=ACTIVE", 3)).isEmpty();
        assertThat(allPages(admin, "&q=" + t + "&status=TRIAL", 2)).hasSize(5);
    }

    @Test
    void refusesBusinessUsersAndKeepsOldArrayShape() {
        String owner = token(signup("retail"));
        assertThat(get("/admin/tenants", owner).status()).isIn(401, 403);
        assertThat(get("/admin/tenants?limit=5", owner).status()).isIn(401, 403);
        Res old = get("/admin/tenants", admin());
        assertThat(old.status()).isEqualTo(200);
        assertThat(old.body().isArray()).isTrue();
        assertThat(old.body().size()).isGreaterThan(0);
        assertThat(old.body().get(0).has("ownerEmail")).isTrue();
    }

    @Test
    void badCursorIsRejected() {
        String admin = admin();
        for (String bad : List.of("garbage", "2026-11-25T10:00:00Z", "nope~" + UUID.randomUUID(), "2026-11-25T10:00:00.123456Z~not-a-uuid")) {
            assertThat(get("/admin/tenants?limit=10&before=" + enc(bad), admin).status()).as(bad).isEqualTo(400);
        }
    }

    List<String> ids(Res r) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : r.at("/items")) {
            out.add(i.get("id").asText());
        }
        return out;
    }
}
