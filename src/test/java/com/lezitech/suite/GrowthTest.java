package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.growth.MetaConversions;
import com.lezitech.suite.settings.PlanActivation;
import com.lezitech.suite.support.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Ad attribution, Meta conversions and "bring a business, both get a month free". */
class GrowthTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlanActivation activation;

    private Res signupWith(Map<String, Object> extra) {
        String email = "grow-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Map<String, Object> body = new HashMap<>(Map.of("businessName", "Grow " + email.substring(5, 9), "sector", "retail",
                "name", "Owner", "email", email, "password", "password123", "phone", "0712 345 678"));
        body.putAll(extra);
        return post("/auth/signup", null, body);
    }

    private UUID tenantOf(Res session) {
        return UUID.fromString(session.at("/tenant/id").asText());
    }

    private UUID paidPlan(UUID tenant) {
        UUID req = jdbc.queryForObject("INSERT INTO subscription_requests (tenant_id, plan, billing_cycle, amount) VALUES (?, 'GROWTH', 'MONTHLY', 4500) RETURNING id",
                UUID.class, tenant);
        activation.activate(req, "MPESA", "TKL" + req.toString().substring(0, 7).toUpperCase());
        return req;
    }

    @Test
    void signupFromAMetaAdIsRecordedAndReportedWithoutRawPersonalData() {
        Res s = signupWith(Map.of("eventId", "evt-trial-1", "acquisition", Map.of(
                "utm_source", "facebook", "utm_medium", "paid", "utm_campaign", "nov-restaurants", "fbclid", "IwAR123",
                "fbc", "fb.1.1762700000000.IwAR123", "fbp", "fb.1.1762700000000.987", "page", "https://lezitech.site/suite/sectors/restaurants",
                "nonsense", "dropped")));
        assertThat(s.status()).as(s.body().toString()).isEqualTo(200);
        UUID tenant = tenantOf(s);

        String acq = jdbc.queryForObject("SELECT acquisition::text FROM tenants WHERE id = ?", String.class, tenant);
        assertThat(acq).contains("nov-restaurants").contains("IwAR123").doesNotContain("nonsense");

        Map<String, Object> e = jdbc.queryForMap("SELECT event_name, event_id, payload::text AS payload, status FROM marketing_events WHERE tenant_id = ?", tenant);
        assertThat(e.get("event_name")).isEqualTo("StartTrial");
        assertThat(e.get("event_id")).isEqualTo("evt-trial-1");
        assertThat(e.get("status")).isEqualTo("OFF"); // no pixel configured in tests
        String payload = (String) e.get("payload");
        assertThat(payload).contains("fb.1.1762700000000.IwAR123").contains("\"currency\": \"KES\"")
                .doesNotContain("@test.local").doesNotContain("0712").doesNotContain("254712345678");

        // Paying: Subscribe and Purchase, with the amount, credited to the same visitor.
        paidPlan(tenant);
        assertThat(jdbc.queryForList("SELECT event_name FROM marketing_events WHERE tenant_id = ? ORDER BY event_name", String.class, tenant))
                .containsExactly("Purchase", "StartTrial", "Subscribe");
        assertThat(jdbc.queryForObject("SELECT payload->'custom_data'->>'value' FROM marketing_events WHERE tenant_id = ? AND event_name = 'Purchase'",
                String.class, tenant)).isEqualTo("4500.00");

        // A second payment is a Purchase only.
        paidPlan(tenant);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketing_events WHERE tenant_id = ? AND event_name = 'Subscribe'", Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketing_events WHERE tenant_id = ? AND event_name = 'Purchase'", Integer.class, tenant)).isEqualTo(2);
    }

    @Test
    void setupRequestFromAnAdIsALeadAndItsWorkspaceKeepsTheSource() {
        Res r = post("/public/setup-requests", null, Map.of("businessName", "Ad Lead Shop", "contactName", "Wanjiru", "phone", "0711 000 111",
                "sector", "retail", "eventId", "evt-lead-1", "acquisition", Map.of("utm_source", "facebook", "utm_campaign", "nov-shops")));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT acquisition->>'utm_campaign' FROM setup_requests WHERE id = ?::uuid", String.class, r.at("/id").asText()))
                .isEqualTo("nov-shops");
        assertThat(jdbc.queryForObject("SELECT event_name FROM marketing_events WHERE event_id = 'evt-lead-1'", String.class)).isEqualTo("Lead");

        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
        Res ws = post("/admin/workspaces", admin, Map.of("requestId", r.at("/id").asText(), "businessName", "Ad Lead Shop", "sector", "retail",
                "name", "Wanjiru", "email", "adlead-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local", "withSamples", false));
        assertThat(ws.status()).as(ws.body().toString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT acquisition->>'utm_campaign' FROM tenants WHERE id = ?::uuid", String.class, ws.at("/tenantId").asText()))
                .isEqualTo("nov-shops");

        JsonNode report = get("/admin/acquisition?days=7", admin).body();
        assertThat(report.at("/rows").toString()).contains("nov-shops").contains("facebook");
    }

    @Test
    void aReferredBusinessPayingGivesBothAMonthFree() {
        JsonNode sessionA = signup("retail");
        String ownerA = token(sessionA);
        JsonNode card = get("/billing/referral", ownerA).body();
        String code = card.at("/code").asText();
        assertThat(code).hasSize(6);
        assertThat(card.at("/link").asText()).endsWith("?ref=" + code);
        UUID a = UUID.fromString(sessionA.at("/tenant/id").asText());
        Timestamp trialBefore = jdbc.queryForObject("SELECT trial_ends_at FROM tenants WHERE id = ?", Timestamp.class, a);

        Res b = signupWith(Map.of("acquisition", Map.of("ref", code.toLowerCase())));
        UUID bId = tenantOf(b);
        assertThat(jdbc.queryForObject("SELECT referred_by FROM tenants WHERE id = ?", UUID.class, bId)).isEqualTo(a);
        assertThat(get("/billing/referral", ownerA).at("/joined").asInt()).isEqualTo(1);

        paidPlan(bId);
        // B: one paid month plus one free; A (still on trial): a month added to the trial.
        Timestamp paidUntil = jdbc.queryForObject("SELECT paid_until FROM tenants WHERE id = ?", Timestamp.class, bId);
        assertThat(paidUntil.toInstant()).isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(55)));
        Timestamp trialAfter = jdbc.queryForObject("SELECT trial_ends_at FROM tenants WHERE id = ?", Timestamp.class, a);
        assertThat(trialAfter.toInstant()).isAfter(trialBefore.toInstant().plus(java.time.Duration.ofDays(27)));
        assertThat(get("/billing/referral", ownerA).at("/paid").asInt()).isEqualTo(1);

        // Only the first payment rewards.
        paidPlan(bId);
        assertThat(jdbc.queryForObject("SELECT trial_ends_at FROM tenants WHERE id = ?", Timestamp.class, a)).isEqualTo(trialAfter);
    }

    @Test
    void queuedConversionsAreSentToMetaInOneBatch() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        HttpServer graph = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        graph.createContext("/", ex -> {
            sent.set(ex.getRequestURI() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{\"events_received\":1}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        graph.start();
        try {
            MetaConversions meta = new MetaConversions(jdbc, json, "1234567890", "test-token", "TEST42",
                    "http://127.0.0.1:" + graph.getAddress().getPort());
            meta.track("Lead", "evt-send-1", null, "Someone@Example.com", "0722 111 222", null, BigDecimal.ZERO);
            meta.sendPending();
            assertThat(sent.get()).startsWith("/1234567890/events?access_token=test-token")
                    .contains("evt-send-1").contains("\"test_event_code\":\"TEST42\"")
                    // sha256("someone@example.com")
                    .contains("72497f475e4f76d0b28f57c73a084ece576d170874eba3ee2609d9afe4b71aab").doesNotContain("Someone@Example.com");
            assertThat(jdbc.queryForObject("SELECT status FROM marketing_events WHERE event_id = 'evt-send-1'", String.class)).isEqualTo("SENT");
        } finally {
            graph.stop(0);
        }
    }
}
