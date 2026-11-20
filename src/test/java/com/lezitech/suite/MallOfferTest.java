package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.settings.PlanActivation;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase E: a mall offers its tenants BizNez at a discount; a tenant who joins through it pays less and the landlord is thanked. */
class MallOfferTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlanActivation activation;

    @Test
    void aTenantJoiningThroughTheMallGetsTheDiscountAndTheLandlordACredit() {
        JsonNode mall = signup("property");
        String landlord = token(mall);
        String mallId = mall.at("/tenant/id").asText();
        String slug = mall.at("/tenant/slug").asText();

        // Not set up by LeziTech yet: nothing to switch on, nothing shown to tenants.
        assertThat(get("/property/biznez-offer", landlord).at("/available").asBoolean()).isFalse();
        assertThat(call("PUT", "/property/biznez-offer", landlord, Map.of("on", true)).status()).isEqualTo(400);

        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
        assertThat(call("PUT", "/admin/tenants/" + mallId + "/mall-offer", admin, Map.of("percent", 20, "thankYou", 1000)).status()).isEqualTo(200);
        assertThat(get("/public/mall-offer/" + slug, null).at("/live").asBoolean()).isFalse();

        // The landlord switches it on; tenants now see it with a link carrying the mall's code.
        JsonNode on = call("PUT", "/property/biznez-offer", landlord, Map.of("on", true)).body();
        assertThat(on.at("/on").asBoolean()).isTrue();
        JsonNode shown = get("/public/mall-offer/" + slug, null).body();
        assertThat(shown.at("/live").asBoolean()).isTrue();
        assertThat(shown.at("/percent").asInt()).isEqualTo(20);
        String code = shown.at("/link").asText().replaceAll(".*\\?mall=", "");
        assertThat(code).hasSize(6);

        // A tenant's shop signs up through it.
        String email = "shop-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res shop = post("/auth/signup", null, new HashMap<>(Map.of("businessName", "Mall Tenant Shop", "sector", "retail", "name", "Akinyi",
                "email", email, "password", "password123", "acquisition", Map.of("mall", code.toLowerCase()))));
        assertThat(shop.status()).as(shop.body().toString()).isEqualTo(200);
        UUID shopId = UUID.fromString(shop.at("/tenant/id").asText());
        assertThat(jdbc.queryForObject("SELECT mall_id FROM tenants WHERE id = ?", UUID.class, shopId)).isEqualTo(UUID.fromString(mallId));
        JsonNode keep = get("/billing/keep", shop.at("/token").asText()).body();
        assertThat(keep.at("/pay").asLong()).isEqualTo(Math.round(keep.at("/full").asLong() * 0.8));
        assertThat(keep.at("/offer").asText()).contains("20% off");
        assertThat(get("/property/biznez-offer", landlord).at("/joined").asInt()).isEqualTo(1);

        // Its first payment thanks the landlord: KSh 1,000 off the landlord's own next payment.
        UUID req = jdbc.queryForObject("INSERT INTO subscription_requests (tenant_id, plan, billing_cycle, amount) VALUES (?, 'LITE', 'MONTHLY', 2120) RETURNING id",
                UUID.class, shopId);
        activation.activate(req, "MPESA", "TKMALL0001");
        assertThat(jdbc.queryForObject("SELECT billing_credit FROM tenants WHERE id = ?::uuid", BigDecimal.class, mallId)).isEqualByComparingTo("1000");
        JsonNode landlordKeep = get("/billing/keep", landlord).body();
        assertThat(landlordKeep.at("/full").asLong() - landlordKeep.at("/pay").asLong()).isEqualTo(1000);
        // Only once per tenant.
        UUID again = jdbc.queryForObject("INSERT INTO subscription_requests (tenant_id, plan, billing_cycle, amount) VALUES (?, 'LITE', 'MONTHLY', 2120) RETURNING id",
                UUID.class, shopId);
        activation.activate(again, "MPESA", "TKMALL0002");
        assertThat(jdbc.queryForObject("SELECT billing_credit FROM tenants WHERE id = ?::uuid", BigDecimal.class, mallId)).isEqualByComparingTo("1000");
    }
}
