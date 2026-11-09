package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Each business's own Daraja / PayHero / SMS accounts: saved encrypted, shown masked, set by the owner or the LeziTech team. */
class PaymentSetupTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private static final Map<String, Object> DARAJA_TILL = Map.of(
            "enabled", true,
            "settings", Map.of("environment", "sandbox", "type", "TILL", "shortcode", "174379", "tillNumber", "5123456"),
            "secrets", Map.of("consumerKey", "ck-abcdefgh1234", "consumerSecret", "cs-secret-9876", "passkey", "pk-passkey-5555"));

    private JsonNode find(JsonNode overview, String kind) {
        for (JsonNode i : overview.at("/integrations")) {
            if (kind.equals(i.get("kind").asText())) {
                return i;
            }
        }
        throw new IllegalStateException("no " + kind);
    }

    @Test
    void ownerSavesDarajaKeysEncryptedAndOnlySeesTheLastFourCharacters() {
        JsonNode s = signup("retail");
        String owner = token(s);
        Res saved = call("PUT", "/settings/payments/daraja", owner, DARAJA_TILL);
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        assertThat(saved.at("/enabled").asBoolean()).isTrue();
        assertThat(saved.at("/status").asText()).isEqualTo("UNTESTED");
        assertThat(saved.at("/secrets/consumerKey").asText()).isEqualTo("••••1234");
        assertThat(saved.toString()).doesNotContain("ck-abcdefgh1234").doesNotContain("pk-passkey");

        String stored = jdbc.queryForObject("SELECT secrets FROM integrations WHERE tenant_id = ?::uuid AND kind = 'DARAJA'",
                String.class, s.at("/tenant/id").asText());
        assertThat(stored).startsWith("v1:").doesNotContain("ck-abcdefgh1234");

        // Saving again with blank keys keeps the stored ones.
        Res again = call("PUT", "/settings/payments/daraja", owner, Map.of("settings", Map.of("tillNumber", "5999999"),
                "secrets", Map.of("consumerKey", "")));
        assertThat(again.at("/secrets/consumerKey").asText()).isEqualTo("••••1234");
        assertThat(again.at("/settings/tillNumber").asText()).isEqualTo("5999999");

        // The till now knows prompts go through Daraja.
        assertThat(get("/mpesa/config", owner).at("/provider").asText()).isEqualTo("DARAJA");
    }

    @Test
    void switchingPayHeroOnSwitchesDarajaOff() {
        String owner = token(signup("retail"));
        call("PUT", "/settings/payments/daraja", owner, DARAJA_TILL);
        Res ph = call("PUT", "/settings/payments/payhero", owner, Map.of("enabled", true, "settings", Map.of("channelId", "911"),
                "secrets", Map.of("username", "ph-user", "password", "ph-pass-0042")));
        assertThat(ph.status()).as(ph.body().toString()).isEqualTo(200);
        JsonNode all = get("/settings/payments", owner).body();
        assertThat(find(all, "PAYHERO").at("/enabled").asBoolean()).isTrue();
        assertThat(find(all, "DARAJA").at("/enabled").asBoolean()).isFalse();
        assertThat(get("/mpesa/config", owner).at("/provider").asText()).isEqualTo("PAYHERO");
    }

    @Test
    void incompleteOrWrongDetailsAreRefusedWithAClearMessage() {
        String owner = token(signup("retail"));
        Res missing = call("PUT", "/settings/payments/daraja", owner, Map.of("enabled", true,
                "settings", Map.of("type", "TILL", "shortcode", "174379")));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.at("/message").asText()).contains("till number").contains("consumer key").contains("passkey");

        Res badChannel = call("PUT", "/settings/payments/payhero", owner, Map.of("settings", Map.of("channelId", "abc")));
        assertThat(badChannel.status()).isEqualTo(400);

        // Saved but never switched on: testing reports what's missing instead of failing.
        call("PUT", "/settings/payments/sms", owner, Map.of("settings", Map.of("environment", "sandbox")));
        Res test = post("/settings/payments/sms/test", owner, null);
        assertThat(test.at("/status").asText()).isEqualTo("FAILED");
        assertThat(test.at("/statusMessage").asText()).contains("username").contains("API key");
    }

    @Test
    void onlyTheOwnerCanSeeOrChangePaymentKeys() {
        String owner = token(signup("retail"));
        String manager = staff(owner, "MANAGER");
        String cashier = staff(owner, "CASHIER");
        assertThat(get("/settings/payments", manager).status()).isEqualTo(403);
        assertThat(get("/settings/payments", cashier).status()).isEqualTo(403);
        assertThat(call("PUT", "/settings/payments/daraja", manager, DARAJA_TILL).status()).isEqualTo(403);
    }

    @Test
    void theLeziTechTeamCanSetUpABusinessAndTheAuditLogHoldsNoKeys() {
        JsonNode s = signup("restaurant");
        String tenant = s.at("/tenant/id").asText();
        String support = post("/admin/auth/demo", null, Map.of("role", "SUPPORT")).at("/token").asText();
        String sales = post("/admin/auth/demo", null, Map.of("role", "SALES")).at("/token").asText();

        assertThat(call("PUT", "/admin/tenants/" + tenant + "/payments/daraja", sales, DARAJA_TILL).status()).isEqualTo(403);
        Res saved = call("PUT", "/admin/tenants/" + tenant + "/payments/daraja", support, DARAJA_TILL);
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        assertThat(saved.at("/updatedBy").asText()).startsWith("LeziTech:");

        // The owner sees what the team set up.
        assertThat(find(get("/settings/payments", token(s)).body(), "DARAJA").at("/enabled").asBoolean()).isTrue();

        String detail = jdbc.queryForObject("SELECT detail::text FROM platform_audit WHERE tenant_id = ?::uuid AND action = 'payments.save'",
                String.class, tenant);
        assertThat(detail).contains("consumerKey").doesNotContain("ck-abcdefgh1234").doesNotContain("pk-passkey");

        assertThat(get("/admin/tenants/" + UUID.randomUUID() + "/payments", support).status()).isEqualTo(404);
    }

    @Test
    void aBusinessWithNoAccountSetUpStillWorksInLocalSimulation() {
        String owner = token(signup("retail"));
        JsonNode cfg = get("/mpesa/config", owner).body();
        assertThat(cfg.at("/enabled").asBoolean()).isTrue();
        assertThat(cfg.at("/provider").asText()).isEqualTo("SIMULATED");
    }
}
