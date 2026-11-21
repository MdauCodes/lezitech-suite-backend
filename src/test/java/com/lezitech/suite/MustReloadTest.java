package com.lezitech.suite;

import com.lezitech.suite.platform.PlatformSettings;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** LeziTech can ask open apps to reload; the app asks without signing in, and nothing a client sends changes anything. */
class MustReloadTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformSettings platform;

    private static final String KEY = "local-operator-key";

    private Res operator(String key, Map<String, Object> body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/platform/client-release"))
                .header("Content-Type", "application/json");
        if (key != null) {
            b.header("X-Operator-Key", key);
        }
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        return new Res(res.statusCode(), res.body().isBlank() ? json.nullNode() : json.readTree(res.body()));
    }

    private void reset() {
        jdbc.update("DELETE FROM platform_settings WHERE key IN ('minClientRelease', 'latestClientRelease', 'reloadMessage')");
        platform.forget();
    }

    private String latest() throws Exception {
        return get("/public/client-status", null).at("/latestRelease").asText();
    }

    @Test
    void statusAnswersWithoutSignInAndReportsLatest() throws Exception {
        reset();
        try {
            Res r = get("/public/client-status?release=20260101000000", null);
            assertThat(r.status()).isEqualTo(200);
            assertThat(PlatformSettings.isStamp(r.at("/latestRelease").asText())).isTrue();
            assertThat(r.at("/mustReload").asBoolean()).isFalse();
            assertThat(r.at("/mayReload").asBoolean()).isFalse();
            assertThat(r.at("/checkAgainSeconds").asInt()).isEqualTo(60);
        } finally {
            reset();
        }
    }

    @Test
    void aReleaseHeaderChangesNothing() throws Exception {
        reset();
        try {
            String before = latest();
            for (String auth : new String[]{null, "Bearer x"}) {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public/client-status"))
                        .header("X-App-Release", "20991231235959");
                if (auth != null) {
                    b.header("Authorization", auth);
                }
                assertThat(HttpClient.newHttpClient().send(b.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
                assertThat(latest()).isEqualTo(before);
            }
            assertThat(platform.latestClientRelease()).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_settings WHERE key = 'latestClientRelease'", Integer.class)).isZero();
        } finally {
            reset();
        }
    }

    @Test
    void mayReloadIsAlwaysFalse() throws Exception {
        reset();
        try {
            assertThat(get("/public/client-status?release=19990101000000", null).at("/mayReload").asBoolean()).isFalse();
            assertThat(operator(KEY, Map.of("latest", "20261121093000")).status()).isEqualTo(200);
            Res r = get("/public/client-status?release=19990101000000", null);
            assertThat(r.at("/mayReload").asBoolean()).isFalse();
            assertThat(r.at("/latestRelease").asText()).isEqualTo("20261121093000");
        } finally {
            reset();
        }
    }

    @Test
    void operatorMinimumForcesOlderClientsOnly() throws Exception {
        reset();
        try {
            Map<String, Object> set = new HashMap<>();
            set.put("min", "20261121000000");
            set.put("message", "Please reload.");
            assertThat(operator(KEY, set).status()).isEqualTo(200);
            Res old = get("/public/client-status?release=20261120000000", null);
            assertThat(old.at("/mustReload").asBoolean()).isTrue();
            assertThat(old.at("/message").asText()).isEqualTo("Please reload.");
            assertThat(get("/public/client-status?release=20261121093000", null).at("/mustReload").asBoolean()).isFalse();
            assertThat(get("/public/client-status?release=nonsense", null).at("/mustReload").asBoolean()).isFalse();
            assertThat(get("/public/client-status", null).at("/mustReload").asBoolean()).isFalse();
        } finally {
            reset();
        }
    }

    @Test
    void operatorEndpointNeedsTheKey() throws Exception {
        reset();
        try {
            assertThat(operator(null, Map.of("min", "20261121000000")).status()).isEqualTo(403);
            assertThat(operator("wrong-key", Map.of("min", "20261121000000")).status()).isEqualTo(403);
            assertThat(platform.minClientRelease()).isNull();
        } finally {
            reset();
        }
    }

    @Test
    void longMessageIsRefused() throws Exception {
        reset();
        try {
            assertThat(operator(KEY, Map.of("message", "x".repeat(201))).status()).isEqualTo(400);
            assertThat(platform.reloadMessage()).isNull();
        } finally {
            reset();
        }
    }

    @Test
    void impossibleDatesAreNotStamps() {
        assertThat(PlatformSettings.isStamp("20260231235959")).isFalse();
        assertThat(PlatformSettings.isStamp("20261121093000")).isTrue();
        assertThat(PlatformSettings.isStamp("2026112109300")).isFalse();
        assertThat(PlatformSettings.isStamp(null)).isFalse();
    }
}
