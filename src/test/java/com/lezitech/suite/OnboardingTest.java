package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Setup requests, the team building a workspace, hand-over with a welcome code, and "forgot password" by code. */
class OnboardingTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Mailer mailer;

    EmailSender.LogSender outbox;

    @BeforeEach
    void captureEmails() {
        outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
    }

    private String codeSentTo(String email) {
        for (int i = outbox.sent.size() - 1; i >= 0; i--) {
            EmailSender.Email e = outbox.sent.get(i);
            if (e.to().equals(email)) {
                Matcher m = Pattern.compile("(?:code|is) (\\d{6})\\b").matcher(e.text());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        throw new IllegalStateException("no code emailed to " + email);
    }

    private String admin(String role) {
        return post("/admin/auth/demo", null, Map.of("role", role)).at("/token").asText();
    }

    @Test
    void aBusinessAsksTheTeamBuildsItAndHandsItOver() {
        String email = "mama-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
        Res asked = post("/public/setup-requests", null, Map.ofEntries(Map.entry("businessName", "Mama Njeri Butchery"),
                Map.entry("sector", "retail"), Map.entry("subSector", "Butchery"), Map.entry("contactName", "Njeri Wanjiku"),
                Map.entry("phone", "0712 345 678"), Map.entry("email", email), Map.entry("town", "Mombasa"), Map.entry("branches", 2),
                Map.entry("staff", 4), Map.entry("payments", List.of("CASH", "MPESA_TILL", "NONSENSE")), Map.entry("currentTool", "Exercise book")));
        assertThat(asked.status()).as(asked.body().toString()).isEqualTo(200);
        String requestId = asked.at("/id").asText();

        String support = admin("SUPPORT");
        JsonNode open = get("/admin/setup-requests", support).body();
        JsonNode mine = null;
        for (JsonNode r : open) {
            if (r.get("id").asText().equals(requestId)) {
                mine = r;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("status").asText()).isEqualTo("NEW");
        assertThat(mine.get("payments").toString()).contains("MPESA_TILL").doesNotContain("NONSENSE");

        Res made = post("/admin/workspaces", support, Map.of("requestId", requestId, "businessName", "Mama Njeri Butchery",
                "sector", "retail", "subSector", "Butchery", "name", "Njeri Wanjiku", "email", email, "phone", "0712345678"));
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        String tenant = made.at("/tenantId").asText();

        Map<String, Object> t = jdbc.queryForMap("SELECT in_setup, settings->'setupProfile' AS profile FROM tenants WHERE id = ?::uuid", tenant);
        assertThat(t.get("in_setup")).isEqualTo(true);
        assertThat(t.get("profile").toString()).contains("Mombasa").contains("Exercise book");
        assertThat(jdbc.queryForObject("SELECT status FROM setup_requests WHERE id = ?::uuid", String.class, requestId)).isEqualTo("IN_SETUP");
        // A second workspace for the same request is refused.
        assertThat(post("/admin/workspaces", support, Map.of("requestId", requestId, "businessName", "x", "sector", "retail",
                "name", "x", "email", "x-" + email)).status()).isEqualTo(409);

        Res handed = post("/admin/tenants/" + tenant + "/handover", support, null);
        assertThat(handed.status()).as(handed.body().toString()).isEqualTo(200);
        assertThat(handed.at("/emailStatus").asText()).isEqualTo("SENT");
        assertThat(handed.at("/whatsappLink").asText()).startsWith("https://wa.me/254712345678?text=");
        assertThat(jdbc.queryForObject("SELECT in_setup FROM tenants WHERE id = ?::uuid", Boolean.class, tenant)).isFalse();
        assertThat(jdbc.queryForObject("SELECT trial_ends_at BETWEEN now() + interval '6 days' AND now() + interval '8 days' FROM tenants WHERE id = ?::uuid",
                Boolean.class, tenant)).isTrue();
        assertThat(post("/admin/tenants/" + tenant + "/handover", support, null).status()).isEqualTo(409);

        // The owner sets a password with the code from the welcome email, and is signed in.
        String code = codeSentTo(email);
        Res set = post("/auth/reset", null, Map.of("email", email, "code", code, "password", "mama-njeri-2026"));
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        assertThat(set.at("/tenant/name").asText()).isEqualTo("Mama Njeri Butchery");
        assertThat(post("/auth/login", null, Map.of("email", email, "password", "mama-njeri-2026")).status()).isEqualTo(200);
        // The code worked once.
        assertThat(post("/auth/reset", null, Map.of("email", email, "code", code, "password", "another-pass-1")).status()).isEqualTo(400);
    }

    @Test
    void salesCanSeeRequestsButBotsAndBadFormsAreTurnedAway() {
        assertThat(post("/public/setup-requests", null, Map.of("businessName", "Spam", "contactName", "Bot", "phone", "0700000000",
                "website", "http://spam.example")).at("/id").isMissingNode()).isTrue();
        assertThat(post("/public/setup-requests", null, Map.of("businessName", "Shop", "contactName", "Ann", "phone", "12")).status()).isEqualTo(400);
        assertThat(get("/admin/setup-requests", admin("SALES")).status()).isEqualTo(200);
        assertThat(get("/admin/setup-requests", admin("DEVELOPER")).status()).isEqualTo(403);
    }

    @Test
    void forgotPasswordSendsACodeThatSignsOutOtherDevices() {
        JsonNode s = signup("retail");
        String email = s.get("email").asText();
        String oldToken = token(s);

        // Same answer for an unknown email, and no email is sent for it.
        int before = outbox.sent.size();
        assertThat(post("/auth/code", null, Map.of("email", "nobody-" + UUID.randomUUID() + "@test.local")).status()).isEqualTo(200);
        assertThat(outbox.sent.size()).isEqualTo(before);

        assertThat(post("/auth/code", null, Map.of("email", email.toUpperCase())).status()).isEqualTo(200);
        String code = codeSentTo(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        assertThat(post("/auth/reset", null, Map.of("email", email, "code", wrong, "password", "new-password-1")).at("/code").asText()).isEqualTo("BAD_CODE");
        assertThat(post("/auth/reset", null, Map.of("email", email, "code", code, "password", "short")).status()).isEqualTo(400);

        Res ok = post("/auth/reset", null, Map.of("email", email, "code", code, "password", "new-password-1"));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(get("/auth/me", oldToken).status()).isEqualTo(401);
        assertThat(get("/auth/me", ok.at("/token").asText()).status()).isEqualTo(200);
    }

    @Test
    void aCodeStopsWorkingAfterFiveWrongTries() {
        JsonNode s = signup("retail");
        String email = s.get("email").asText();
        post("/auth/code", null, Map.of("email", email));
        String code = codeSentTo(email);
        String wrong = code.equals("123456") ? "654321" : "123456";
        for (int i = 0; i < 5; i++) {
            post("/auth/reset", null, Map.of("email", email, "code", wrong, "password", "new-password-1"));
        }
        Res late = post("/auth/reset", null, Map.of("email", email, "code", code, "password", "new-password-1"));
        assertThat(late.status()).isIn(400, 429);
    }
}
