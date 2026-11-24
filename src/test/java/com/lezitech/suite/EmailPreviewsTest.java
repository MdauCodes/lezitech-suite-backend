package com.lezitech.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.admin.AdminInvites;
import com.lezitech.suite.auth.LoginCodes;
import com.lezitech.suite.notify.BrandedEmail;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Builds Busynez's own emails with sample data and writes each page to target/email-previews so it can be opened and looked at. */
class EmailPreviewsTest {

    static final String CODE = "482913";
    static final String APP = "https://app.busynez.com";

    EmailSender.LogSender outbox;
    Mailer mailer;

    @BeforeEach
    void setUp() {
        mailer = new Mailer(new ObjectMapper(), "off", "", "", "LeziTech", APP, "0796 814 154", "");
        outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
    }

    EmailSender.Email send(EmailLayout.Msg m) {
        assertThat(mailer.send("amina@example.com", "Amina Otieno", m.subject(), m.text(), m.html(), m.preheader())).isEqualTo("SENT");
        return outbox.sent.get(outbox.sent.size() - 1);
    }

    void write(String name, EmailSender.Email mail) throws IOException {
        Path dir = Path.of("target", "email-previews");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".html"), mail.html());
        assertThat(mail.html()).doesNotContain("localhost").doesNotContain("LeziTech control panel").doesNotContain("<style")
                .contains("<meta name=\"color-scheme\" content=\"light\">");
        assertThat(mail.text()).doesNotContain("localhost").doesNotContain("LeziTech control panel");
    }

    void codeEmail(String name, EmailLayout.Msg m, boolean codeInSubject) throws IOException {
        EmailSender.Email mail = send(m);
        assertThat(mail.html()).contains(">" + CODE + "<").contains("Busynez will never phone, text or WhatsApp you");
        assertThat(mail.text()).contains(CODE);
        assertThat(mail.subject().contains(CODE)).isEqualTo(codeInSubject);
        // The inbox preview line never carries the code.
        assertThat(m.preheader()).doesNotContain(CODE);
        write(name, mail);
    }

    @Test
    void adminResetCode() throws IOException {
        codeEmail("admin-reset-code", AdminInvites.resetEmail("Amina", CODE), false);
    }

    @Test
    void adminInvite() throws IOException {
        EmailSender.Email mail = send(AdminInvites.inviteEmail("Amina", APP + "/admin/invite/sample-token"));
        assertThat(mail.subject()).isEqualTo("Choose your Busynez control panel password");
        assertThat(mail.html()).contains("Choose my password").contains("/admin/invite/sample-token").contains("You're on the Busynez control panel team");
        write("admin-invite", mail);
    }

    @Test
    void passwordReset() throws IOException {
        codeEmail("password-reset", LoginCodes.resetEmail("Amina", CODE, false), true);
    }

    @Test
    void tillPinReset() throws IOException {
        codeEmail("till-pin-reset", LoginCodes.resetEmail("Amina", CODE, true), true);
    }

    @Test
    void staffInvite() throws IOException {
        EmailLayout.Msg m = LoginCodes.inviteEmail("Amina", CODE, "amina@example.com", "Mama Njeri Cafe", "mama-njeri", "Waiter", "Njeri Wanjiku", APP);
        codeEmail("staff-invite", m, true);
        assertThat(m.html()).contains("Set my password").contains("/reset?email=").contains(APP + "/w/mama-njeri");
    }

    @Test
    void confirmEmail() throws IOException {
        codeEmail("confirm-email", LoginCodes.confirmEmail("Amina", CODE), true);
    }

    @Test
    void aBusinessBrandedEmail() throws IOException {
        BrandedEmail.Brand brand = new BrandedEmail.Brand("Mama Njeri Cafe", "#0f766e", null, "0712 345 678", "hello@mamanjeri.example", "Moi Avenue, Nairobi");
        assertThat(mailer.sendAs(brand, "amina@example.com", "Amina Otieno", "Your table is booked", "Your table is booked.",
                EmailLayout.h("Your table is booked", null) + EmailLayout.p("Hi Amina, we have a table for 4 at 19:00."))).isEqualTo("SENT");
        EmailSender.Email mail = outbox.sent.get(0);
        assertThat(mail.html()).contains("Your table is booked").contains("Mama Njeri Cafe");
        write("branded-booking", mail);
    }
}
