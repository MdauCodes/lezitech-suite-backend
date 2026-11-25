package com.lezitech.suite;

import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The control-panel menu shows how many setup requests are still open. */
class AdminCountsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    String admin(String role) {
        return post("/admin/auth/demo", null, Map.of("role", role)).at("/token").asText();
    }

    int open(String token) {
        Res r = get("/admin/counts", token);
        assertThat(r.status()).isEqualTo(200);
        return r.at("/openSetupRequests").asInt();
    }

    @Test
    void countsOnlyRequestsNobodyHasFinished() {
        String support = admin("SUPPORT");
        int before = open(support);
        // Straight into the table: the public form has its own per-address limit that other tests share.
        jdbc.update("INSERT INTO setup_requests (business_name, contact_name, phone) VALUES ('Count Me One', 'Ann', '0712345678'), ('Count Me Two', 'Ann', '0712345678')");
        assertThat(open(support)).isEqualTo(before + 2);
        jdbc.update("UPDATE setup_requests SET status = 'DONE' WHERE business_name = 'Count Me One'");
        jdbc.update("UPDATE setup_requests SET status = 'LOST' WHERE business_name = 'Count Me Two'");
        assertThat(open(support)).isEqualTo(before);
        assertThat(open(admin("SUPERADMIN"))).isEqualTo(before);
    }

    @Test
    void peopleWhoCannotReadRequestsAreRefused() {
        assertThat(get("/admin/counts", admin("DEVELOPER")).status()).isEqualTo(403);
        assertThat(get("/admin/counts", null).status()).isIn(401, 403);
        String owner = token(signup("retail"));
        assertThat(get("/admin/counts", owner).status()).isIn(401, 403);
    }

    /** MRR counts only workspaces that are paid up, the same ones the "paying" number counts. */
    @Test
    void mrrLeavesOutLapsedActiveWorkspacesAndAgreesWithPaying() {
        String admin = admin("ADMIN");
        Res base = get("/admin/overview", admin);
        long mrr0 = base.at("/mrr").asLong();
        long paying0 = base.at("/paying").asLong();
        String id = signup("retail").at("/tenant/id").asText();
        long lite = 2650;

        jdbc.update("UPDATE tenants SET status = 'ACTIVE', plan = 'LITE', paid_until = now() - interval '2 days' WHERE id = ?::uuid", id);
        Res lapsed = get("/admin/overview", admin);
        assertThat(lapsed.at("/mrr").asLong()).isEqualTo(mrr0);
        assertThat(lapsed.at("/paying").asLong()).isEqualTo(paying0);

        jdbc.update("UPDATE tenants SET paid_until = now() + interval '20 days' WHERE id = ?::uuid", id);
        Res paid = get("/admin/overview", admin);
        assertThat(paid.at("/mrr").asLong()).isEqualTo(mrr0 + lite);
        assertThat(paid.at("/paying").asLong()).isEqualTo(paying0 + 1);

        jdbc.update("UPDATE tenants SET paid_until = NULL WHERE id = ?::uuid", id);
        Res open = get("/admin/overview", admin);
        assertThat(open.at("/mrr").asLong()).isEqualTo(mrr0 + lite);
        assertThat(open.at("/paying").asLong()).isEqualTo(paying0 + 1);
    }
}
