package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.auth.LoginThrottle;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The control panel signs in with a password and an emailed code, and opening a business needs another code and a
 * reason. Without the owner's grant LeziTech can only look: the server refuses every change.
 */
class AdminSecurityTest extends IntegrationTest {

    static final String PW = "staff-pass-123";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    LoginThrottle throttle;
    @org.springframework.beans.factory.annotation.Value("${suite.cors-origins}")
    String corsOrigins;

    EmailSender.LogSender box;

    @BeforeEach
    void capture() {
        box = new EmailSender.LogSender();
        itMailer.useSender(box);
    }

    /** Wrong tries from this machine share limits for the whole run; give back what these tests used. */
    @AfterEach
    @SuppressWarnings("unchecked")
    void forgetFailures() throws Exception {
        var f = LoginThrottle.class.getDeclaredField("failures");
        f.setAccessible(true);
        ((Map<String, ?>) f.get(throttle)).clear();
    }

    String newAdmin(String role) {
        return newAdmin(role, "", "");
    }

    String newAdmin(String role, String extraColumns, String extraValues) {
        String email = "staff-" + UUID.randomUUID().toString().substring(0, 8) + "@lezitech.test";
        jdbc.update("INSERT INTO platform_admins (email, name, password_hash, role" + extraColumns + ") VALUES (?, 'Test Staff', ?, ?" + extraValues + ")",
                email, encoder.encode(PW), role);
        return email;
    }

    long mailsTo(String email) {
        return box.sent.stream().filter(m -> m.to().equals(email)).count();
    }

    String wrongCode(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    // ---------- Part 1: sign-in ----------

    @Test
    void signingInNeedsThePasswordAndTheEmailedCodeAndIsRecorded() {
        String email = newAdmin("SUPPORT");
        Res one = post("/admin/auth/login", null, Map.of("email", email, "password", PW));
        assertThat(one.status()).isEqualTo(200);
        assertThat(one.at("/step").asText()).isEqualTo("code");
        assertThat(one.at("/token").isMissingNode()).isTrue();
        assertThat(mailsTo(email)).isEqualTo(1);
        assertThat(box.sent.get(0).subject()).isEqualTo("Your Busynez control panel sign-in code");

        String code = codeTo(box, email);
        Res wrong = post("/admin/auth/code", null, Map.of("email", email, "code", wrongCode(code)));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.at("/token").isMissingNode()).isTrue();
        // The code alone, without the password step, is not a way in for someone else's address.
        assertThat(post("/admin/auth/code", null, Map.of("email", "nobody-" + UUID.randomUUID() + "@lezitech.test", "code", code)).status()).isEqualTo(401);

        Res two = post("/admin/auth/code", null, Map.of("email", email, "code", code));
        assertThat(two.status()).isEqualTo(200);
        String token = two.at("/token").asText();
        assertThat(get("/admin/auth/me", token).status()).isEqualTo(200);
        // It works once.
        assertThat(post("/admin/auth/code", null, Map.of("email", email, "code", code)).status()).isEqualTo(401);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE action = 'auth.signin' AND detail->>'device' IS NOT NULL AND detail->>'ip' IS NOT NULL AND admin_id = (SELECT id FROM platform_admins WHERE email = ?)",
                Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_login_device FROM platform_admins WHERE email = ?", String.class, email)).isNotBlank();
        JsonNode team = get("/admin/team", token).body();
        boolean seen = false;
        for (JsonNode m : team) {
            if (email.equals(m.get("email").asText())) {
                seen = m.has("lastLoginAt") && !m.get("lastLoginAt").isNull() && !m.get("lastLoginDevice").isNull();
            }
        }
        assertThat(seen).isTrue();
    }

    @Test
    void aWrongPasswordGetsNoCodeAndTheSameAnswerAsEveryoneElse() {
        String email = newAdmin("SUPPORT");
        Res r = post("/admin/auth/login", null, Map.of("email", email, "password", "not-it"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.at("/code").asText()).isEqualTo("BAD_LOGIN");
        assertThat(mailsTo(email)).isZero();
    }

    @Test
    void blockedSwitchedOffPendingAndUnknownAccountsAreRefusedLikeAWrongPassword() {
        String pending = newAdmin("SUPPORT", ", invite_pending", ", true");
        String blocked = newAdmin("SUPPORT", ", blocked_at", ", now()");
        String off = newAdmin("SUPPORT", ", active", ", false");
        String unknown = "nobody-" + UUID.randomUUID() + "@lezitech.test";
        String message = null;
        for (String email : new String[]{pending, blocked, off, unknown}) {
            Res r = post("/admin/auth/login", null, Map.of("email", email, "password", PW));
            assertThat(r.status()).as(email).isEqualTo(401);
            assertThat(r.at("/code").asText()).isEqualTo("BAD_LOGIN");
            if (message == null) {
                message = r.at("/message").asText();
            }
            assertThat(r.at("/message").asText()).isEqualTo(message);
        }
        assertThat(box.sent).isEmpty();
    }

    @Test
    void aCodeThatHasExpiredIsRefused() {
        String email = newAdmin("SUPPORT");
        post("/admin/auth/login", null, Map.of("email", email, "password", PW));
        String code = codeTo(box, email);
        jdbc.update("UPDATE admin_codes SET expires_at = now() - interval '1 minute' WHERE admin_id = (SELECT id FROM platform_admins WHERE email = ?)", email);
        Res r = post("/admin/auth/code", null, Map.of("email", email, "code", code));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.at("/token").isMissingNode()).isTrue();
    }

    @Test
    void fiveWrongTriesSpendTheCodeEvenForTheRightOneAfterwards() {
        String email = newAdmin("SUPPORT");
        post("/admin/auth/login", null, Map.of("email", email, "password", PW));
        String code = codeTo(box, email);
        for (int i = 0; i < 5; i++) {
            assertThat(post("/admin/auth/code", null, Map.of("email", email, "code", wrongCode(code))).status()).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_codes WHERE admin_id = (SELECT id FROM platform_admins WHERE email = ?) AND used_at IS NOT NULL", Integer.class, email)).isEqualTo(1);
        Res right = post("/admin/auth/code", null, Map.of("email", email, "code", code));
        assertThat(right.status()).isNotEqualTo(200);
        assertThat(right.at("/token").isMissingNode()).isTrue();
    }

    @Test
    void theApiAnswersWithSecurityHeaders() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/auth/options")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var h = res.headers();
        assertThat(h.firstValue("Strict-Transport-Security")).isPresent();
        assertThat(h.firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(h.firstValue("Referrer-Policy")).hasValue("strict-origin-when-cross-origin");
        assertThat(h.firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(h.firstValue("Content-Security-Policy").orElse("")).contains("default-src 'none'").contains("frame-ancestors 'none'");
        // Cross-origin calls from the web app still work.
        HttpResponse<String> pre = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/auth/login"))
                .header("Origin", corsOrigins.split(",")[0].trim()).header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(pre.statusCode()).isLessThan(300);
    }

    // ---------- Part 2: opening a business ----------

    String signIn(String email) {
        itMailer.useSender(box);
        return adminSignIn(email, PW, box);
    }

    Res open(String token, String email, String tenant, String mode, String reason) {
        return openBusiness(token, email, tenant, mode, reason);
    }

    @Test
    void openingABusinessNeedsAReasonAndACodeBoundToThatBusiness() {
        String email = newAdmin("SUPPORT");
        String token = signIn(email);
        String a = signup("retail").at("/tenant/id").asText();
        String b = signup("retail").at("/tenant/id").asText();

        // No code: refused. No reason: refused.
        assertThat(post("/admin/tenants/" + a + "/impersonate", token, Map.of("mode", "LOOK", "reason", "Checking stock")).at("/code").asText()).isEqualTo("STEP_UP_CODE");
        assertThat(post("/admin/tenants/" + a + "/open-code", token, Map.of()).at("/needed").asBoolean()).isTrue();
        String code = codeTo(box, email);
        assertThat(post("/admin/tenants/" + a + "/impersonate", token, Map.of("mode", "LOOK", "reason", "", "code", code)).status()).isEqualTo(400);
        // A wrong code is refused.
        assertThat(post("/admin/tenants/" + a + "/impersonate", token, Map.of("mode", "LOOK", "reason", "Checking stock", "code", wrongCode(code))).status()).isEqualTo(403);

        // A code asked for business B does not open business A.
        post("/admin/tenants/" + b + "/open-code", token, Map.of());
        String codeForB = codeTo(box, email);
        assertThat(post("/admin/tenants/" + a + "/impersonate", token, Map.of("mode", "LOOK", "reason", "Checking stock", "code", codeForB)).at("/code").asText()).isEqualTo("STEP_UP_CODE");
        // The wrong tries above spent it: ask again for B, then use it on B, once.
        post("/admin/tenants/" + b + "/open-code", token, Map.of());
        String fresh = codeTo(box, email);
        Res in = post("/admin/tenants/" + b + "/impersonate", token, Map.of("mode", "LOOK", "reason", "Checking stock", "code", fresh));
        assertThat(in.status()).as(in.body().toString()).isEqualTo(200);
        assertThat(post("/admin/tenants/" + b + "/impersonate", token, Map.of("mode", "LOOK", "reason", "Checking stock", "code", fresh)).status()).isEqualTo(403);
    }

    @Test
    void aDemoBusinessNeedsNoCodeButABusinessInSetupDoes() {
        String email = newAdmin("SUPPORT");
        String token = signIn(email);
        String demo = signup("retail").at("/tenant/id").asText();
        jdbc.update("UPDATE tenants SET is_demo = true WHERE id = ?::uuid", demo);
        assertThat(post("/admin/tenants/" + demo + "/open-code", token, Map.of()).at("/needed").asBoolean()).isFalse();
        assertThat(post("/admin/tenants/" + demo + "/impersonate", token, Map.of("mode", "HELP")).at("/token").asText()).isNotBlank();

        String setup = signup("retail").at("/tenant/id").asText();
        jdbc.update("UPDATE tenants SET in_setup = true WHERE id = ?::uuid", setup);
        assertThat(post("/admin/tenants/" + setup + "/open-code", token, Map.of()).at("/needed").asBoolean()).isTrue();
        assertThat(post("/admin/tenants/" + setup + "/impersonate", token, Map.of("mode", "HELP", "reason", "Setting up")).status()).isEqualTo(403);
    }

    @Test
    void lookOnlyIsRefusedByTheServerOnRealEndpointsAndIsRecorded() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String tenant = s.at("/tenant/id").asText();
        String email = newAdmin("SUPPORT");
        String token = signIn(email);

        Res opened = open(token, email, tenant, "LOOK", "Customer rang about prices");
        assertThat(opened.status()).as(opened.body().toString()).isEqualTo(200);
        String look = opened.at("/token").asText();
        assertThat(opened.at("/support/lookOnly").asBoolean()).isTrue();

        // Reading works.
        JsonNode sugar = item(look, "");
        assertThat(get("/items", look).status()).isEqualTo(200);
        assertThat(get("/auth/me", look).status()).isEqualTo(200);
        // A sale, an item edit, a settings change, a delete: all refused with a plain message.
        Res sale = post("/orders", look, Map.of("lines", line(sugar.get("id").asText(), 1), "payments", List.of(Map.of("method", "CASH", "amount", 100000))));
        assertThat(sale.status()).isEqualTo(403);
        assertThat(sale.at("/code").asText()).isEqualTo("LOOK_ONLY");
        assertThat(sale.at("/message").asText()).contains("only looking");
        Map<String, Object> edit = json.convertValue(sugar, Map.class);
        assertThat(call("PUT", "/items/" + sugar.get("id").asText(), look, edit).status()).isEqualTo(403);
        assertThat(call("PUT", "/settings", look, Map.of("name", "Hijacked")).status()).isEqualTo(403);
        assertThat(call("DELETE", "/items/" + sugar.get("id").asText(), look, null).status()).isEqualTo(403);
        assertThat(post("/categories", look, Map.of("name", "Nope")).status()).isEqualTo(403);
        assertThat(post("/auth/refresh", look, Map.of()).status()).isEqualTo(403);
        assertThat(post("/support-access", look, Map.of()).status()).isEqualTo(403);
        // Nothing changed.
        assertThat(get("/items", owner).body().toString()).doesNotContain("Nope");

        // The owner sees who is looking, and the opening is in the activity log and the platform audit.
        JsonNode status = get("/support-access", owner).body();
        assertThat(status.at("/looking/0/name").asText()).contains("Test Staff");
        assertThat(get("/activity", owner).body().toString()).contains("SUPPORT_OPENED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_audit WHERE action = 'workspace.impersonate' AND tenant_id = ?::uuid
                AND detail->>'mode' = 'LOOK' AND detail->>'reason' = 'Customer rang about prices'""", Integer.class, tenant)).isEqualTo(1);

        // It lasts at most 2 hours, and the owner can end it.
        assertThat(jdbc.queryForObject("SELECT expires_at <= now() + interval '2 hours 1 minute' FROM support_looks WHERE tenant_id = ?::uuid", Boolean.class, tenant)).isTrue();
        post("/support-access/stop", owner, Map.of());
        assertThat(get("/items", look).status()).isEqualTo(401);
        Res again = open(token, email, tenant, "LOOK", "Checking again");
        String look2 = again.at("/token").asText();
        assertThat(get("/items", look2).status()).isEqualTo(200);
        jdbc.update("UPDATE support_looks SET expires_at = now() - interval '1 minute' WHERE tenant_id = ?::uuid", tenant);
        assertThat(get("/items", look2).status()).isEqualTo(401);
    }

    @Test
    void helpModeNeedsTheOwnersGrantAndTheOwnerCanBeAsked() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String tenant = s.at("/tenant/id").asText();
        String email = newAdmin("SUPPORT");
        String token = signIn(email);

        Res refused = open(token, email, tenant, "HELP", "Fix the menu");
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.at("/code").asText()).isEqualTo("NO_SUPPORT_GRANT");
        assertThat(get("/admin/tenants/" + tenant, token).at("/supportAccess/active").asBoolean()).isFalse();

        // Ask the owner: they see the request, and Not now closes it.
        assertThat(post("/admin/tenants/" + tenant + "/ask-help", token, Map.of("reason", "Fix the menu")).status()).isEqualTo(200);
        assertThat(get("/support-access", owner).at("/request/reason").asText()).isEqualTo("Fix the menu");
        post("/support-access/request/decline", owner, Map.of());
        assertThat(get("/support-access", owner).at("/request").isNull()).isTrue();
        post("/admin/tenants/" + tenant + "/ask-help", token, Map.of("reason", "Fix the menu please"));
        assertThat(get("/support-access", owner).at("/request/name").asText()).contains("Test Staff");

        // Allow = the existing grant. The request is then answered, and help mode can change things.
        assertThat(post("/support-access", owner, Map.of()).at("/active").asBoolean()).isTrue();
        assertThat(get("/support-access", owner).at("/request").isNull()).isTrue();
        Res help = open(token, email, tenant, "HELP", "Fix the menu");
        assertThat(help.status()).as(help.body().toString()).isEqualTo(200);
        String as = help.at("/token").asText();
        assertThat(post("/categories", as, Map.of("name", "Added by support")).status()).isEqualTo(200);
        assertThat(get("/activity", owner).body().toString()).contains("SUPPORT_ACTION").contains("SUPPORT_OPENED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE action = 'workspace.impersonate' AND tenant_id = ?::uuid AND detail->>'mode' = 'HELP' AND detail->>'reason' = 'Fix the menu'",
                Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void whoMayOpenABusinessFollowsTheirRole() {
        String tenant = signup("retail").at("/tenant/id").asText();
        for (String role : new String[]{"SUPERADMIN", "ADMIN", "SUPPORT", "DEVELOPER"}) {
            String email = newAdmin(role);
            String token = signIn(email);
            Res r = open(token, email, tenant, "LOOK", "Checking");
            assertThat(r.status()).as(role).isEqualTo(200);
        }
        String sales = newAdmin("SALES");
        String salesToken = signIn(sales);
        assertThat(post("/admin/tenants/" + tenant + "/open-code", salesToken, Map.of()).status()).isEqualTo(403);
        Map<String, Object> body = new HashMap<>(Map.of("mode", "LOOK", "reason", "Checking", "code", "123456"));
        assertThat(post("/admin/tenants/" + tenant + "/impersonate", salesToken, body).status()).isEqualTo(403);
    }
}
