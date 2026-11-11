package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Receipts are emailed in the background in the business's name; failures become problems the business can retry. */
class EmailJobsTest extends IntegrationTest {

    @Autowired
    Mailer mailer;

    private JsonNode waitFor(String jobId, String token, String status) throws InterruptedException {
        JsonNode j = null;
        for (int i = 0; i < 60; i++) {
            j = get("/email-jobs/" + jobId, token).body();
            if (status.equals(j.get("status").asText())) {
                return j;
            }
            Thread.sleep(150);
        }
        return j;
    }

    @Test
    void receiptGoesOutInTheBusinessesOwnName() throws Exception {
        EmailSender.LogSender log = new EmailSender.LogSender();
        mailer.useSender(log);
        try {
            String owner = token(signup("retail"));
            String sugar = item(owner, "Sugar").get("id").asText();
            String sale = post("/orders", owner, Map.of("lines", line(sugar, 1), "payments", List.of(Map.of("method", "CASH", "amount", 500)))).at("/id").asText();
            Res r = post("/orders/" + sale + "/email-receipt", owner, Map.of("email", "buyer@example.com"));
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.at("/queued").asBoolean()).isTrue();
            assertThat(waitFor(r.at("/jobId").asText(), owner, "SENT").get("status").asText()).isEqualTo("SENT");
            EmailSender.Email sent = log.sent.stream().filter(e -> e.to().equals("buyer@example.com")).findFirst().orElseThrow();
            assertThat(sent.subject()).startsWith("Receipt #").contains("Test retail");
            assertThat(sent.html()).contains("Test retail").doesNotContain("Lezi Suite");
        } finally {
            mailer.useSender(new EmailSender.LogSender());
        }
    }

    @Test
    void aFailedEmailBecomesAProblemTheBusinessCanRetry() throws Exception {
        mailer.useSender(new EmailSender() {
            public void send(Email email) {
                throw new IllegalStateException("550 mailbox does not exist");
            }

            public String name() {
                return "broken";
            }
        });
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        String sale = post("/orders", owner, Map.of("lines", line(sugar, 1), "payments", List.of(Map.of("method", "CASH", "amount", 500)))).at("/id").asText();
        String job = post("/orders/" + sale + "/email-receipt", owner, Map.of("email", "nobody@example.com")).at("/jobId").asText();
        JsonNode failed = waitFor(job, owner, "FAILED");
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(3);
        assertThat(failed.get("message").asText()).contains("email address looks wrong");

        JsonNode problems = get("/problems", owner).body();
        assertThat(problems.get("open").asInt()).isEqualTo(1);
        String problem = problems.at("/problems/0/id").asText();
        assertThat(problems.at("/problems/0/title").asText()).contains("nobody@example.com");

        mailer.useSender(new EmailSender.LogSender());
        assertThat(post("/problems/" + problem + "/retry", owner, Map.of()).status()).isEqualTo(200);
        assertThat(waitFor(job, owner, "SENT").get("status").asText()).isEqualTo("SENT");
        assertThat(get("/problems", owner).body().get("open").asInt()).isZero();
        // A cashier doesn't manage problems.
        assertThat(get("/problems", staff(owner, "CASHIER")).status()).isEqualTo(403);
    }
}
