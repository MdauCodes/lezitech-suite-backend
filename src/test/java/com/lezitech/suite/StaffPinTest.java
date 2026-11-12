package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Staff with just a name, a job and a till PIN: no email needed. Optional email lets them reset their own PIN. */
class StaffPinTest extends IntegrationTest {

    @Autowired
    Mailer mailer;

    EmailSender.LogSender outbox;

    @BeforeEach
    void captureEmails() {
        outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
    }

    @Test
    void staffWithoutEmailSignInWithNameAndPin() {
        JsonNode owner = signup("restaurant");
        String token = token(owner);
        String slug = owner.at("/tenant/slug").asText();

        // Name, job, PIN: enough.
        Res added = post("/team", token, Map.of("name", "Otieno Barista", "role", "CASHIER", "pin", "4821"));
        assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
        assertThat(added.body().get("email").isNull()).isTrue();

        // Nothing to sign in with: refused. A password without an email: refused. An extra owner needs both.
        assertThat(post("/team", token, Map.of("name", "No Way In", "role", "CASHIER")).status()).isEqualTo(400);
        assertThat(post("/team", token, Map.of("name", "Half", "role", "CASHIER", "pin", "1111", "password", "password123")).status()).isEqualTo(400);
        assertThat(post("/team", token, Map.of("name", "Co Owner", "role", "OWNER", "pin", "1111")).status()).isEqualTo(400);

        // On the staff page: first name or full name, any case, with the PIN.
        Res wrong = post("/auth/staff-pin", null, Map.of("workspace", slug, "name", "otieno", "pin", "0000"));
        assertThat(wrong.status()).isEqualTo(401);
        Res in = post("/auth/staff-pin", null, Map.of("workspace", slug, "name", "otieno", "pin", "4821"));
        assertThat(in.status()).as(in.body().toString()).isEqualTo(200);
        assertThat(in.body().at("/user/name").asText()).isEqualTo("Otieno Barista");
        assertThat(in.body().at("/user/role").asText()).isEqualTo("CASHIER");
        assertThat(post("/auth/staff-pin", null, Map.of("workspace", slug, "name", "OTIENO BARISTA", "pin", "4821")).status()).isEqualTo(200);
        // Another business's staff page doesn't know them.
        String other = signup("retail").at("/tenant/slug").asText();
        assertThat(post("/auth/staff-pin", null, Map.of("workspace", other, "name", "Otieno", "pin", "4821")).status()).isEqualTo(401);

        // The owner sets a new PIN for them.
        String id = added.body().get("id").asText();
        assertThat(call("PUT", "/team/" + id, token, Map.of("pin", "7777")).status()).isEqualTo(200);
        assertThat(post("/auth/staff-pin", null, Map.of("workspace", slug, "name", "Otieno", "pin", "4821")).status()).isEqualTo(401);
        assertThat(post("/auth/staff-pin", null, Map.of("workspace", slug, "name", "Otieno", "pin", "7777")).status()).isEqualTo(200);
    }

    @Test
    void staffWithAnEmailResetTheirOwnPin() {
        JsonNode owner = signup("beauty");
        String token = token(owner);
        String slug = owner.at("/tenant/slug").asText();
        String email = "stylist-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        assertThat(post("/team", token, Map.of("name", "Faith Stylist", "role", "STYLIST", "pin", "2468", "email", email)).status()).isEqualTo(200);

        // No password: can't sign in by email, only with the PIN.
        assertThat(post("/auth/login", null, Map.of("email", email, "password", "anything123")).status()).isEqualTo(401);

        post("/auth/code", null, Map.of("email", email, "for", "pin"));
        EmailSender.Email sent = outbox.sent.stream().filter(m -> m.to().equals(email)).reduce((a, b) -> b).orElseThrow();
        assertThat(sent.text()).contains("till PIN");
        Matcher m = Pattern.compile("is (\\d{6})\\b").matcher(sent.text());
        assertThat(m.find()).isTrue();

        assertThat(post("/auth/reset-pin", null, Map.of("email", email, "code", m.group(1), "pin", "12")).status()).isEqualTo(400);
        Res reset = post("/auth/reset-pin", null, Map.of("email", email, "code", m.group(1), "pin", "9753"));
        assertThat(reset.status()).as(reset.body().toString()).isEqualTo(200);
        assertThat(post("/auth/staff-pin", null, Map.of("workspace", slug, "name", "Faith", "pin", "9753")).status()).isEqualTo(200);
        // The code works once.
        assertThat(post("/auth/reset-pin", null, Map.of("email", email, "code", m.group(1), "pin", "1357")).status()).isEqualTo(400);
    }
}
