package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Package I: the business's own KRA receipt (ETR) on a sale, tax for any dates, and support access only when granted. */
class PackageITest extends IntegrationTest {

    @Autowired
    Mailer mailer;
    @Autowired
    JdbcTemplate jdbc;

    /** Enough of a PNG for the type check (it reads the first bytes). */
    private static byte[] png() {
        byte[] b = new byte[200];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(head, 0, b, 0, head.length);
        return b;
    }

    private String sale(String token, String sugar) {
        return post("/orders", token, Map.of("lines", line(sugar, 1), "payments", List.of(Map.of("method", "CASH", "amount", 190)))).at("/id").asText();
    }

    @Test
    void anEtrIsAttachedToAPaidSaleEmailedAndCountedOnTheTaxPage() throws Exception {
        EmailSender.LogSender outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
        String owner = token(signup("retail"));
        String sugar = item(owner, "Sugar").get("id").asText();
        String with = sale(owner, sugar);
        sale(owner, sugar);
        String open = post("/orders", owner, Map.of("lines", line(sugar, 1))).at("/id").asText();

        assertThat(upload("/orders/" + open + "/etr", owner, png(), "etr.png").status()).as("not paid yet").isEqualTo(400);
        assertThat(upload("/orders/" + with + "/etr", owner, "not a picture".getBytes(), "etr.txt").status()).isEqualTo(400);
        Res attached = upload("/orders/" + with + "/etr", owner, png(), "etr.png");
        assertThat(attached.status()).as(attached.body().toString()).isEqualTo(200);
        assertThat(get("/orders/" + with, owner).at("/etrFileId").asText()).isEqualTo(attached.at("/etrFileId").asText());
        assertThat(raw("/orders/" + with + "/etr", owner).status()).isEqualTo(200);

        // Emailed in the background, with the file attached, in the business's name.
        assertThat(post("/orders/" + with + "/etr/email", owner, Map.of("email", "bad")).status()).isEqualTo(400);
        assertThat(post("/orders/" + with + "/etr/email", owner, Map.of("email", "Otieno@Example.test")).status()).isEqualTo(200);
        EmailSender.Email e = null;
        for (int i = 0; i < 60 && e == null; i++) {
            e = outbox.sent.stream().filter(m -> m.to().equals("otieno@example.test")).findFirst().orElse(null);
            Thread.sleep(100);
        }
        assertThat(e).as("ETR email sent").isNotNull();
        assertThat(e.subject()).startsWith("Your KRA receipt for sale #");
        assertThat(e.attachments()).hasSize(1);
        assertThat(e.attachments().get(0).name()).endsWith(".png");
        JsonNode after = null;
        for (int i = 0; i < 30; i++) {
            after = get("/orders/" + with, owner).body();
            if (!after.path("etrEmailedTo").isNull() && after.has("etrEmailedTo")) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(after.at("/etrEmailedTo").asText()).isEqualTo("otieno@example.test");

        // Tax for any dates (today): one paid sale has its ETR, one hasn't.
        String today = LocalDate.now(ZoneId.of("Africa/Nairobi")).toString();
        JsonNode tax = get("/reports/tax?from=" + today + "&to=" + today, owner).body();
        assertThat(tax.at("/from").asText()).isEqualTo(today);
        assertThat(tax.at("/totals/grossSales").asDouble()).isEqualTo(380);
        assertThat(tax.at("/etr/withEtr").asInt()).isEqualTo(1);
        assertThat(tax.at("/etr/withoutEtr").asInt()).isEqualTo(1);
        assertThat(tax.at("/etr/missing")).hasSize(1);
        assertThat(get("/reports/tax?from=" + today + "&to=2000-01-01", owner).status()).isEqualTo(400);

        // Removing it puts the sale back on the "needs an ETR" list.
        assertThat(call("DELETE", "/orders/" + with + "/etr", owner, null).status()).isEqualTo(200);
        assertThat(get("/reports/tax?from=" + today + "&to=" + today, owner).at("/etr/withEtr").asInt()).isZero();
        assertThat(post("/orders/" + with + "/etr/email", owner, Map.of("email", "otieno@example.test")).status()).isEqualTo(400);
    }

    @Test
    void lezitechCanSignInOnlyWhileTheOwnerAllowsItAndEverythingIsLogged() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String tenant = s.at("/tenant/id").asText();
        String support = post("/admin/auth/demo", null, Map.of("role", "SUPPORT")).at("/token").asText();

        Res refused = post("/admin/tenants/" + tenant + "/impersonate", support, Map.of());
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.at("/code").asText()).isEqualTo("NO_SUPPORT_GRANT");
        assertThat(get("/admin/tenants/" + tenant, support).at("/supportAccess/active").asBoolean()).isFalse();

        // Only the owner grants it.
        String manager = staff(owner, "MANAGER");
        assertThat(post("/support-access", manager, Map.of()).status()).isEqualTo(403);
        JsonNode granted = post("/support-access", owner, Map.of("note", "Help with the menu")).body();
        assertThat(granted.get("active").asBoolean()).isTrue();
        assertThat(granted.get("hours").asInt()).isEqualTo(5);

        JsonNode session = post("/admin/tenants/" + tenant + "/impersonate", support, Map.of()).body();
        String as = session.get("token").asText();
        assertThat(session.at("/support/by").asText()).isNotBlank();
        assertThat(get("/items", as).status()).isEqualTo(200);

        // Support can't extend or give itself access.
        assertThat(post("/support-access", as, Map.of()).status()).isEqualTo(403);

        // A change made as support is in the business's activity log, marked LeziTech support.
        assertThat(post("/categories", as, Map.of("name", "Added by support")).status()).isEqualTo(200);
        String log = get("/activity", owner).body().toString();
        assertThat(log).contains("SUPPORT_GRANTED").contains("SUPPORT_ACTION").contains("LeziTech support");

        // The owner stops it: the support sign-in stops working at once.
        assertThat(post("/support-access/stop", owner, Map.of()).at("/active").asBoolean()).isFalse();
        assertThat(get("/items", as).status()).isEqualTo(401);
        assertThat(post("/admin/tenants/" + tenant + "/impersonate", support, Map.of()).status()).isEqualTo(403);

        // An expired grant is the same as none.
        post("/support-access", owner, Map.of());
        String again = post("/admin/tenants/" + tenant + "/impersonate", support, Map.of()).at("/token").asText();
        jdbc.update("UPDATE support_grants SET expires_at = now() - interval '1 minute' WHERE tenant_id = ?::uuid", tenant);
        assertThat(get("/items", again).status()).isEqualTo(401);
    }
}
