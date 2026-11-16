package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The business names its own jobs (Director, Operations manager, Store keeper...), invites staff by email to set a
 * password with a code, gives an operations manager owner powers, and LeziTech can send anyone a sign-in code.
 */
class JobTitlesTest extends IntegrationTest {

    private JsonNode pinIn(String slug, String name, String pin) {
        return post("/auth/staff-pin", null, Map.of("workspace", slug, "name", name, "pin", pin)).body();
    }

    @Test
    void jobsInvitesAndOwnerPowers() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();

        // Starting list for a shop, with what each does.
        JsonNode jobs = get("/team/jobs", owner).body();
        assertThat(jobs.at("/jobs").toString()).contains("Director").contains("Operations manager").contains("Store keeper").contains("STOREKEEPER");
        assertThat(call("PUT", "/team/jobs", owner, Map.of("jobs", List.of(Map.of("title", "Shop floor lead", "role", "WIZARD")))).status()).isEqualTo(400);
        Res saved = call("PUT", "/team/jobs", owner, Map.of("jobs", List.of(
                Map.of("title", "Director", "role", "OWNER", "description", "Runs it"),
                Map.of("title", "Operations manager", "role", "MANAGER", "description", "Runs the day"),
                Map.of("title", "Store keeper", "role", "STOREKEEPER", "description", "Stock"))));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.at("/jobs").size()).isEqualTo(3);

        // Email only: they get a code to set their password.
        String email = "ops-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
        Res added = post("/team", owner, Map.of("name", "Wambui Ops", "role", "MANAGER", "jobTitle", "Operations manager", "email", email, "pin", "4321"));
        assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
        String opsId = added.at("/id").asText();
        assertThat(added.at("/jobTitle").asText()).isEqualTo("Operations manager");
        Res invite = post("/team/" + opsId + "/invite", owner, Map.of());
        assertThat(invite.status()).as(invite.body().toString()).isEqualTo(200);
        assertThat(invite.at("/message").asText()).contains(email);
        Res emailOnly = post("/team", owner, Map.of("name", "Kamau", "role", "CASHIER", "email", "k-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local"));
        assertThat(emailOnly.status()).isEqualTo(200);

        // Without owner powers: no payments settings.
        String ops = pinIn(slug, "Wambui Ops", "4321").at("/token").asText();
        assertThat(get("/settings/payments", ops).status()).isEqualTo(403);
        // Only the owner gives owner powers.
        assertThat(call("PUT", "/team/" + opsId, ops, Map.of("ownerPowers", true)).status()).isEqualTo(403);
        assertThat(call("PUT", "/team/" + opsId, owner, Map.of("ownerPowers", true)).status()).isEqualTo(200);
        JsonNode again = pinIn(slug, "Wambui Ops", "4321");
        assertThat(again.at("/user/role").asText()).isEqualTo("OWNER");
        assertThat(again.at("/user/baseRole").asText()).isEqualTo("MANAGER");
        assertThat(again.at("/user/jobTitle").asText()).isEqualTo("Operations manager");
        String opsPowered = again.at("/token").asText();
        assertThat(get("/settings/payments", opsPowered).status()).isEqualTo(200);
        // ...but still can't change the owner's own account.
        String ownerId = s.at("/user/id").asText();
        assertThat(call("PUT", "/team/" + ownerId, opsPowered, Map.of("name", "Someone else")).status()).isEqualTo(403);

        // A store keeper: stock yes, sales reports no.
        assertThat(post("/team", owner, Map.of("name", "Otieno Store", "role", "STOREKEEPER", "jobTitle", "Store keeper", "pin", "5555")).status()).isEqualTo(200);
        String keeper = pinIn(slug, "Otieno Store", "5555").at("/token").asText();
        assertThat(get("/stock", keeper).status()).isNotEqualTo(403);
        assertThat(get("/reports/sales", keeper).status()).isEqualTo(403);

        // LeziTech can send anyone a code from the control panel.
        String admin = post("/admin/auth/demo", null, Map.of("role", "SUPPORT")).at("/token").asText();
        String tenantId = s.at("/tenant/id").asText();
        Res sent = post("/admin/tenants/" + tenantId + "/users/" + opsId + "/code", admin, Map.of());
        assertThat(sent.status()).as(sent.body().toString()).isEqualTo(200);
    }
}
