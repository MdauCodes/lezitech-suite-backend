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

/** Trial owners get a reminder on day 5 and day 7, once each; a WhatsApp message is always ready as a fallback. */
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
    void dayFiveThenDaySevenEachSentOnce() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String email = s.get("email").asText();
        EmailSender.LogSender log = new EmailSender.LogSender();
        nudges.useSender(log);

        endsIn(tenant, "36 hours");
        nudges.run();
        nudges.run();
        assertThat(log.sent.stream().filter(e -> e.to().equals(email))).hasSize(1)
                .allSatisfy(e -> {
                    assertThat(e.subject()).contains("2 days");
                    assertThat(e.text()).contains("/billing").contains("M-Pesa");
                });
        assertThat(nudge(tenant, "DAY5").get("email_status")).isEqualTo("SENT");
        assertThat((String) nudge(tenant, "DAY5").get("whatsapp_text")).contains("choose a plan");

        endsIn(tenant, "10 hours");
        nudges.run();
        nudges.run();
        assertThat(log.sent.stream().filter(e -> e.to().equals(email) && e.subject().contains("ends today"))).hasSize(1);
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
            endsIn(tenant, "10 hours");
            nudges.run();
            Map<String, Object> n = nudge(tenant, "DAY7");
            assertThat(n.get("email_status")).isEqualTo("FAILED");
            assertThat(n.get("whatsapp_to")).isEqualTo("0712345678");
            // Joining late on the last day doesn't also send the "2 days left" one.
            assertThat(nudge(tenant, "DAY5").get("email_status")).isEqualTo("SKIPPED");
        } finally {
            nudges.useSender(new EmailSender.LogSender());
        }
    }

    @Test
    void payingWorkspacesAndLongTrialsAreLeftAlone() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        endsIn(tenant, "5 days");
        nudges.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trial_nudges WHERE tenant_id = ?::uuid", Long.class, tenant)).isZero();
        endsIn(tenant, "10 hours");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?::uuid", tenant);
        nudges.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trial_nudges WHERE tenant_id = ?::uuid", Long.class, tenant)).isZero();
    }
}
