package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LeziTech's offers reach the businesses they're meant for and change what they pay; extra free days land on trials.
 * Offers here are for tour companies only, so other tests' prices aren't touched.
 */
class OffersTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String admin() {
        return post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
    }

    @AfterEach
    void stopOffers() {
        jdbc.update("UPDATE platform_offers SET active = false WHERE sector = 'tours'");
    }

    private JsonNode plan(String token, String code) {
        for (JsonNode p : get("/billing/plans", token).body().get("plans")) {
            if (code.equals(p.path("code").asText())) {
                return p;
            }
        }
        throw new IllegalStateException("no " + code);
    }

    @Test
    void percentOffForSomeMonths() {
        String admin = admin();
        String support = post("/admin/auth/demo", null, Map.of("role", "SUPPORT")).at("/token").asText();
        JsonNode s = signup("tours");
        String owner = token(s);
        Map<String, Object> offer = Map.of("kind", "PERCENT", "title", "30% off your first 2 months", "percent", 30, "months", 2,
                "audience", "ALL", "sector", "tours", "endsOn", LocalDate.now().plusDays(10).toString());
        assertThat(post("/admin/offers", support, offer).status()).isEqualTo(403);
        Res made = post("/admin/offers", admin, offer);
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        assertThat(made.at("/reached").asInt()).isGreaterThanOrEqualTo(1);

        assertThat(plan(owner, "LITE").get("pay").asLong()).isEqualTo(1855);
        assertThat(plan(owner, "LITE").get("payYear").asLong()).isEqualTo(26500); // percent off is for monthly payments
        assertThat(get("/billing/keep", owner).at("/offers/0/title").asText()).isEqualTo("30% off your first 2 months");

        Res req = post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY"));
        assertThat(req.at("/amount").asLong()).isEqualTo(1855);
        UUID offerId = UUID.fromString(made.at("/id").asText());
        // Two paid months use the offer up.
        jdbc.update("UPDATE subscription_requests SET status = 'PAID' WHERE id = ?", UUID.fromString(req.at("/requestId").asText()));
        jdbc.update("INSERT INTO subscription_requests (tenant_id, plan, billing_cycle, amount, status, offer_id) VALUES (?::uuid, 'LITE', 'MONTHLY', 1855, 'PAID', ?)",
                s.at("/tenant/id").asText(), offerId);
        assertThat(plan(owner, "LITE").get("pay").asLong()).isEqualTo(2650);

        assertThat(post("/admin/offers/" + offerId + "/stop", admin, Map.of()).status()).isEqualTo(200);
    }

    @Test
    void setPriceForAPlan() {
        String admin = admin();
        String owner = token(signup("tours"));
        assertThat(post("/admin/offers", admin, Map.of("kind", "PRICE", "title", "Growth for KES 3,000", "plan", "GROWTH", "price", 3000,
                "audience", "TRIAL", "sector", "tours", "endsOn", LocalDate.now().plusDays(5).toString())).status()).isEqualTo(200);
        assertThat(plan(owner, "GROWTH").get("pay").asLong()).isEqualTo(3000);
        assertThat(plan(owner, "GROWTH").get("payYear").asLong()).isEqualTo(30000);
        assertThat(plan(owner, "LITE").get("pay").asLong()).isEqualTo(2650);
        assertThat(post("/admin/offers", admin, Map.of("kind", "PRICE", "title", "Old", "plan", "GROWTH", "price", 3000,
                "endsOn", LocalDate.now().minusDays(1).toString())).status()).isEqualTo(400);
    }

    @Test
    void extraFreeDaysLandOnTrials() {
        String admin = admin();
        JsonNode trial = signup("tours");
        JsonNode paying = signup("tours");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE', plan = 'LITE', paid_until = now() + interval '30 days' WHERE id = ?::uuid", paying.at("/tenant/id").asText());
        int before = trial.at("/tenant/trialDaysLeft").asInt();
        Res made = post("/admin/offers", admin, Map.of("kind", "DAYS", "title", "7 more free days", "days", 7, "sector", "tours",
                "endsOn", LocalDate.now().plusDays(3).toString()));
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        long ends = jdbc.queryForObject("SELECT ceil(extract(epoch FROM trial_ends_at - now()) / 86400) FROM tenants WHERE id = ?::uuid",
                Long.class, trial.at("/tenant/id").asText());
        assertThat(ends).isEqualTo(before + 7L);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?::uuid", String.class, paying.at("/tenant/id").asText())).isEqualTo("ACTIVE");
    }

    @Test
    void theSetupChosenAtSignUpDecidesThePlan() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Setup Shop", "sector", "retail", "name", "Owner", "email", email,
                "password", "password123", "withSamples", false, "tools", List.of("loyalty", "flying")));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        JsonNode keep = get("/billing/keep", r.body().get("token").asText()).body();
        assertThat(keep.get("needs").toString()).contains("loyalty").doesNotContain("flying");
        assertThat(keep.at("/plan/code").asText()).isEqualTo("GROWTH");
        assertThat(keep.get("pay").asLong()).isEqualTo(4500);
    }

    @Test
    void aShopThatPicksNoExtraToolsStaysOnLite() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Plain Shop", "sector", "retail", "name", "Owner", "email", email,
                "password", "password123", "withSamples", false, "tools", List.of()));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/tenant/settings/modules").toString()).doesNotContain("purchasing");
        assertThat(get("/billing/keep", r.body().get("token").asText()).at("/plan/code").asText()).isEqualTo("LITE");
    }
}
