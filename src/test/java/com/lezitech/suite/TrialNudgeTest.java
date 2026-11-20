package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.TrialNudgeService;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Trial owners get a reminder 3 days before, the day before and on the last day, once each; WhatsApp is ready as a fallback. */
class TrialNudgeTest extends IntegrationTest {

    @Autowired
    TrialNudgeService nudges;

    @Autowired
    JdbcTemplate jdbc;

    private void endsIn(String tenantId, String interval) {
        jdbc.update("UPDATE tenants SET trial_ends_at = now() + cast(? AS interval), phone = '0712345678' WHERE id = ?::uuid", interval, tenantId);
    }

    private Map<String, Object> nudge(String tenantId, String kind) {
        return jdbc.queryForMap("SELECT * FROM trial_nudges WHERE tenant_id = ?::uuid AND kind = ?", tenantId, kind);
    }

    @Test
    void threeDaysThenTomorrowThenLastDayEachSentOnce() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String email = s.get("email").asText();
        EmailSender.LogSender log = new EmailSender.LogSender();
        nudges.useSender(log);

        endsIn(tenant, "3 days");
        nudges.run();
        nudges.run();
        assertThat(log.sent.stream().filter(e -> e.to().equals(email))).hasSize(1)
                .allSatisfy(e -> {
                    assertThat(e.subject()).contains("in 3 days");
                    // Their own setup's price, and how to pay.
                    assertThat(e.text()).contains("/billing").contains("M-Pesa").contains("plan: KSh").contains("a month");
                });
        assertThat(nudge(tenant, "LEFT3").get("email_status")).isEqualTo("SENT");
        assertThat((String) nudge(tenant, "LEFT3").get("whatsapp_text")).contains("choose a plan");

        endsIn(tenant, "1 day");
        nudges.run();
        nudges.run();
        assertThat(log.sent.stream().filter(e -> e.to().equals(email) && e.subject().contains("tomorrow"))).hasSize(1);

        endsIn(tenant, "-1 hour");
        nudges.run();
        nudges.run();
        assertThat(log.sent.stream().filter(e -> e.to().equals(email) && e.subject().contains("have ended"))).hasSize(1)
                .allSatisfy(e -> assertThat(e.text()).contains("you can still see it"));
        assertThat(log.sent.stream().filter(e -> e.to().equals(email))).hasSize(3);
    }

    @Test
    void whenEmailFailsTheWhatsAppMessageIsStillReady() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        nudges.useSender(new EmailSender() {
            public void send(Email email) {
                throw new IllegalStateException("brevo answered 401");
            }

            public String name() {
                return "broken";
            }
        });
        try {
            endsIn(tenant, "-1 hour");
            nudges.run();
            Map<String, Object> n = nudge(tenant, "DAY7");
            assertThat(n.get("email_status")).isEqualTo("FAILED");
            assertThat(n.get("whatsapp_to")).isEqualTo("0712345678");
            // Joining late on the last day doesn't also send the earlier ones.
            assertThat(nudge(tenant, "LEFT3").get("email_status")).isEqualTo("SKIPPED");
            assertThat(nudge(tenant, "LEFT1").get("email_status")).isEqualTo("SKIPPED");
        } finally {
            nudges.useSender(new EmailSender.LogSender());
        }
    }

    @Test
    void payingWorkspacesAndLongTrialsAreLeftAlone() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        endsIn(tenant, "6 days");
        nudges.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trial_nudges WHERE tenant_id = ?::uuid", Long.class, tenant)).isZero();
        endsIn(tenant, "10 hours");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?::uuid", tenant);
        nudges.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trial_nudges WHERE tenant_id = ?::uuid", Long.class, tenant)).isZero();
    }
}
