package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The owner's "who can do what" choices: enforced on the server, defaults unchanged when nothing is set. */
class AccessPolicyTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private Res setAccess(String owner, Map<String, Object> access) {
        return call("PUT", "/settings", owner, Map.of("settings", Map.of("access", access)));
    }

    @Test
    void ownerCanGiveAndTakeAwayScreens() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        // Default: a cashier doesn't see reports.
        assertThat(get("/reports/sales", cashier).status()).isEqualTo(403);

        assertThat(setAccess(owner, Map.of("CASHIER", Map.of("modules", List.of("pos", "sales", "customers", "reports")))).status()).isEqualTo(200);
        assertThat(get("/reports/sales", cashier).status()).isEqualTo(200);

        // Take customers away: the cashier can't add customers, but the till can still look them up.
        assertThat(setAccess(owner, Map.of("CASHIER", Map.of("modules", List.of("pos", "sales")))).status()).isEqualTo(200);
        assertThat(post("/customers", cashier, Map.of("name", "Blocked", "phone", "0711000999")).status()).isEqualTo(403);
        assertThat(get("/customers", cashier).status()).isEqualTo(200);
        assertThat(get("/reports/sales", cashier).status()).isEqualTo(403);
    }

    @Test
    void refundsAndDiscountsFollowTheOwnersChoice() {
        String owner = token(signup("retail"));
        String cashier = staff(owner, "CASHIER");
        post("/shifts/open", cashier, Map.of("openingCash", 0));
        String sugar = item(owner, "Sugar").get("id").asText();

        Res discounted = post("/orders", cashier, Map.of("lines", line(sugar, 2), "discount", 10, "payments", List.of(Map.of("method", "CASH", "amount", 1000))));
        assertThat(discounted.status()).isEqualTo(403);
        assertThat(discounted.at("/code").asText()).isEqualTo("APPROVAL_NEEDED");
        String sale = post("/orders", cashier, Map.of("lines", line(sugar, 1), "payments", List.of(Map.of("method", "CASH", "amount", 1000)))).at("/id").asText();
        assertThat(post("/orders/" + sale + "/refund", cashier, Map.of("reason", "Wrong item")).status()).isEqualTo(403);

        assertThat(setAccess(owner, Map.of("CASHIER", Map.of("refunds", true, "discounts", true))).status()).isEqualTo(200);
        assertThat(post("/orders", cashier, Map.of("lines", line(sugar, 2), "discount", 10, "payments", List.of(Map.of("method", "CASH", "amount", 1000)))).status()).isEqualTo(200);
        assertThat(post("/orders/" + sale + "/refund", cashier, Map.of("reason", "Wrong item")).status()).isEqualTo(200);
    }

    @Test
    void staffAndSettingsCantBeHandedOutAndUnknownJobsAreRefused() {
        JsonNode s = signup("retail");
        String owner = token(s);
        assertThat(setAccess(owner, Map.of("CASHIER", Map.of("modules", List.of("pos", "team", "settings"))))).extracting(Res::status).isEqualTo(200);
        String stored = jdbc.queryForObject("SELECT settings -> 'access' ->> 'CASHIER' FROM tenants WHERE id = ?::uuid", String.class, s.at("/tenant/id").asText());
        assertThat(stored).contains("pos").doesNotContain("team").doesNotContain("settings");
        assertThat(setAccess(owner, Map.of("BOSS", Map.of("modules", List.of("pos")))).status()).isEqualTo(400);
        String manager = staff(owner, "MANAGER");
        assertThat(setAccess(manager, Map.of("CASHIER", Map.of("refunds", true))).status()).isEqualTo(403);
    }

    @Test
    void litePlanKeepsToOnePart() {
        JsonNode s = signup("retail");
        String owner = token(s);
        assertThat(call("PUT", "/settings", owner, Map.of("products", List.of("POS", "CRM"), "settings", Map.of())).status()).isEqualTo(200);
        jdbc.update("UPDATE tenants SET status = 'ACTIVE', plan = 'LITE', paid_until = now() + interval '30 days' WHERE id = ?::uuid", s.at("/tenant/id").asText());
        Res two = call("PUT", "/settings", owner, Map.of("products", List.of("POS", "ERP"), "settings", Map.of()));
        assertThat(two.status()).isEqualTo(403);
        assertThat(two.at("/code").asText()).isEqualTo("PLAN_LIMIT");
        assertThat(call("PUT", "/settings", owner, Map.of("products", List.of("POS"), "settings", Map.of())).status()).isEqualTo(200);
        assertThat(call("PUT", "/settings", owner, Map.of("products", List.of(), "settings", Map.of())).status()).isEqualTo(400);
    }
}
