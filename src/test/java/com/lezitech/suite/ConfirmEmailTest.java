package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Owners sign up without an email check; Home then asks them to confirm it with a 6-digit code. */
class ConfirmEmailTest extends IntegrationTest {

    @Autowired
    Mailer mailer;

    EmailSender.LogSender outbox;

    @BeforeEach
    void captureEmails() {
        outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
    }

    @Test
    void ownerConfirmsTheirEmailWithACode() {
        JsonNode s = signup("retail");
        String token = token(s);
        String email = s.at("/user/email").asText();
        assertThat(s.at("/user/emailConfirmed").asBoolean()).isFalse();

        assertThat(post("/auth/confirm-email/send", token, Map.of()).status()).isEqualTo(200);
        EmailSender.Email sent = outbox.sent.stream().filter(m -> m.to().equals(email)).reduce((a, b) -> b).orElseThrow();
        Matcher m = Pattern.compile("is (\\d{6})\\b").matcher(sent.text());
        assertThat(m.find()).isTrue();

        assertThat(post("/auth/confirm-email", token, Map.of("code", "000000")).status()).isEqualTo(400);
        // A wrong try uses up nothing but a try; the right code still works.
        Res ok = post("/auth/confirm-email", token, Map.of("code", m.group(1)));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(get("/auth/me", token).body().at("/user/emailConfirmed").asBoolean()).isTrue();
        // Already confirmed: nothing more to send.
        assertThat(post("/auth/confirm-email/send", token, Map.of()).body().get("confirmed").asBoolean()).isTrue();
    }
}
