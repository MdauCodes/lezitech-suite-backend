package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** A LeziTech colleague is added by email: they choose their own password from a one-off link, nobody types it for them. */
class AdminInvitesTest extends IntegrationTest {

    @Autowired
    Mailer mailer;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    EmailSender.LogSender outbox;

    @BeforeEach
    void captureEmails() {
        outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
        com.lezitech.suite.admin.AdminInviteController.PER_CLIENT.clear();
    }

    String superadmin() {
        return post("/admin/auth/demo", null, Map.of("role", "SUPERADMIN")).at("/token").asText();
    }

    String newEmail() {
        return "colleague-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /**
     * Wrong sign-ins from this test machine count towards one shared limit for the whole run, so tests below check
     * "cannot sign in" against the stored row instead of spending real failed attempts (one test does a real one).
     */
    boolean canSignInWith(String email, String password) {
        Map<String, Object> row = jdbc.queryForMap("SELECT password_hash, active FROM platform_admins WHERE email = ?", email);
        return Boolean.TRUE.equals(row.get("active")) && encoder.matches(password, (String) row.get("password_hash"));
    }

    EmailSender.Email inviteTo(String email) {
        return outbox.sent.stream().filter(m -> m.to().equals(email)).reduce((a, b) -> b).orElseThrow();
    }

    String tokenIn(EmailSender.Email mail) {
        Matcher m = Pattern.compile("/admin/invite/([A-Za-z0-9_-]{43})").matcher(mail.text());
        assertThat(m.find()).as(mail.text()).isTrue();
        return m.group(1);
    }

    String add(String boss, String email) {
        Res r = post("/admin/team", boss, Map.of("name", "Josiah Tester", "email", email, "role", "SUPPORT"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body().get("id").asText();
    }

    @Test
    void addingAColleagueNoLongerNeedsAPasswordAndEmailsThemAnInvite() {
        String boss = superadmin();
        String email = newEmail();
        String id = add(boss, email);

        EmailSender.Email mail = inviteTo(email);
        assertThat(mail.text()).contains("/admin/invite/").contains("Josiah");
        assertThat(mail.subject()).contains("password");
        JsonNode row = null;
        for (JsonNode a : get("/admin/team", boss).body()) {
            if (a.get("id").asText().equals(id)) {
                row = a;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("invitePending").asBoolean()).isTrue();
        assertThat(row.get("active").asBoolean()).isFalse();
        assertThat(row.has("passwordHash")).isFalse();
        // It is written to the audit log, and the log has no link or password in it.
        Integer added = jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE action = 'team.add' AND detail->>'email' = ?", Integer.class, email);
        Integer sent = jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE action = 'team.invite_send' AND detail->>'email' = ?", Integer.class, email);
        assertThat(added).isEqualTo(1);
        assertThat(sent).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE detail::text LIKE '%/admin/invite/%'", Integer.class)).isZero();
    }

    @Test
    void theInviteEmailContainsNoPasswordEvenIfOneIsSentAlong() {
        String boss = superadmin();
        String email = newEmail();
        // A password in the request is ignored: it is not stored, not emailed and does not let anyone in.
        Res r = post("/admin/team", boss, Map.of("name", "Josiah Tester", "email", email, "role", "SUPPORT", "password", "Sneaky-Password-123"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().toString()).doesNotContain("Sneaky-Password-123");

        EmailSender.Email mail = inviteTo(email);
        assertThat(mail.text() + mail.html()).doesNotContain("Sneaky-Password-123");
        assertThat(canSignInWith(email, "Sneaky-Password-123")).isFalse();

        // The password the person chooses never comes back in an answer, the audit log or the stored row in plain form.
        String mine = "My-Own-Password-456";
        Res ok = post("/public/admin-invite/" + tokenIn(mail), null, Map.of("password", mine));
        assertThat(ok.body().toString()).doesNotContain(mine);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE detail::text LIKE ?", Integer.class, "%" + mine + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM platform_admins WHERE email = ?", String.class, email)).doesNotContain(mine);
    }

    @Test
    void theLinkShowsTheirNameAndWorksOnceThenIsRefused() {
        String boss = superadmin();
        String email = newEmail();
        add(boss, email);
        String token = tokenIn(inviteTo(email));

        Res look = get("/public/admin-invite/" + token, null);
        assertThat(look.status()).isEqualTo(200);
        assertThat(look.body().get("name").asText()).isEqualTo("Josiah Tester");
        assertThat(look.body().get("email").asText()).isEqualTo(email);

        // Too short a password is turned down and does not spend the link.
        assertThat(post("/public/admin-invite/" + token, null, Map.of("password", "short")).status()).isEqualTo(400);

        Res ok = post("/public/admin-invite/" + token, null, Map.of("password", "chosen-by-josiah-1"));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(post("/admin/auth/login", null, Map.of("email", email, "password", "chosen-by-josiah-1")).status()).isEqualTo(200);

        Res again = post("/public/admin-invite/" + token, null, Map.of("password", "someone-else-entirely-2"));
        assertThat(again.status()).isEqualTo(410);
        assertThat(again.body().get("message").asText()).contains("already been used");
        assertThat(get("/public/admin-invite/" + token, null).status()).isEqualTo(410);
        assertThat(canSignInWith(email, "someone-else-entirely-2")).isFalse();
        assertThat(canSignInWith(email, "chosen-by-josiah-1")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE action = 'team.invite_accept' AND detail->>'email' = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void anUnknownLinkIsRefused() {
        assertThat(get("/public/admin-invite/" + "A".repeat(43), null).status()).isEqualTo(404);
        assertThat(get("/public/admin-invite/not-a-link", null).status()).isEqualTo(404);
        assertThat(post("/public/admin-invite/" + "B".repeat(43), null, Map.of("password", "long-enough-password")).status()).isEqualTo(404);
    }

    @Test
    void anExpiredLinkIsRefused() {
        String boss = superadmin();
        String email = newEmail();
        add(boss, email);
        String token = tokenIn(inviteTo(email));
        jdbc.update("UPDATE admin_invites SET expires_at = now() - interval '1 minute' WHERE admin_id = (SELECT id FROM platform_admins WHERE email = ?)", email);

        Res late = post("/public/admin-invite/" + token, null, Map.of("password", "chosen-by-josiah-1"));
        assertThat(late.status()).isEqualTo(410);
        assertThat(late.body().get("message").asText()).contains("expired");
        assertThat(get("/public/admin-invite/" + token, null).status()).isEqualTo(410);
        assertThat(canSignInWith(email, "chosen-by-josiah-1")).isFalse();
        assertThat(jdbc.queryForObject("SELECT active FROM platform_admins WHERE email = ?", Boolean.class, email)).isFalse();
    }

    @Test
    void someoneWhoNeverAcceptsCannotSignIn() {
        String boss = superadmin();
        String email = newEmail();
        add(boss, email);
        assertThat(post("/admin/auth/login", null, Map.of("email", email, "password", "chosen-by-josiah-1")).status()).isEqualTo(401);
        for (String guess : new String[]{"", "password", email}) {
            assertThat(canSignInWith(email, guess)).isFalse();
        }
    }

    @Test
    void sendingTheInviteAgainMakesTheOldLinkStopWorkingAndIsRefusedAfterTheyAccept() {
        String boss = superadmin();
        String email = newEmail();
        String id = add(boss, email);
        String first = tokenIn(inviteTo(email));

        assertThat(post("/admin/team/" + id + "/invite", boss, Map.of()).status()).isEqualTo(200);
        String second = tokenIn(inviteTo(email));
        assertThat(second).isNotEqualTo(first);
        assertThat(get("/public/admin-invite/" + first, null).status()).isEqualTo(410);
        assertThat(get("/public/admin-invite/" + second, null).status()).isEqualTo(200);

        assertThat(post("/public/admin-invite/" + second, null, Map.of("password", "chosen-by-josiah-1")).status()).isEqualTo(200);
        Res refused = post("/admin/team/" + id + "/invite", boss, Map.of());
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().get("message").asText()).contains("already set their password");
    }

    @Test
    void switchingAPendingTeammateOffBlocksTheOldLinkTheResendAndBecomingActive() {
        String boss = superadmin();
        String email = newEmail();
        String id = add(boss, email);
        String link = tokenIn(inviteTo(email));

        assertThat(call("PUT", "/admin/team/" + id, boss, Map.of("role", "SUPPORT", "active", false)).status()).isEqualTo(200);

        assertThat(post("/public/admin-invite/" + link, null, Map.of("password", "chosen-by-josiah-1")).status()).isEqualTo(410);
        assertThat(get("/public/admin-invite/" + link, null).status()).isEqualTo(410);
        Res resend = post("/admin/team/" + id + "/invite", boss, Map.of());
        assertThat(resend.status()).isEqualTo(400);
        assertThat(resend.body().get("message").asText()).contains("switched off");
        assertThat(jdbc.queryForObject("SELECT active FROM platform_admins WHERE email = ?", Boolean.class, email)).isFalse();
        assertThat(jdbc.queryForObject("SELECT blocked_at IS NOT NULL FROM platform_admins WHERE email = ?", Boolean.class, email)).isTrue();
        assertThat(canSignInWith(email, "chosen-by-josiah-1")).isFalse();

        // Even a link issued before the block, un-cancelled by force, cannot switch them on.
        jdbc.update("UPDATE admin_invites SET used_at = NULL WHERE admin_id = ?", UUID.fromString(id));
        jdbc.update("UPDATE platform_admins SET invite_pending = true WHERE id = ?", UUID.fromString(id));
        assertThat(post("/public/admin-invite/" + link, null, Map.of("password", "chosen-by-josiah-1")).status()).isEqualTo(410);
        assertThat(jdbc.queryForObject("SELECT active FROM platform_admins WHERE email = ?", Boolean.class, email)).isFalse();
    }

    @Test
    void switchingThemBackOnLetsTheInviteBeSentAgain() {
        // Switching someone off must not strand them: turned back on, they are waiting to accept once more and a
        // fresh invite reaches them. Otherwise a colleague switched off by mistake could never be let in again.
        String boss = superadmin();
        String email = newEmail();
        String id = add(boss, email);
        String firstLink = tokenIn(inviteTo(email));
        assertThat(call("PUT", "/admin/team/" + id, boss, Map.of("role", "SUPPORT", "active", false)).status()).isEqualTo(200);

        assertThat(call("PUT", "/admin/team/" + id, boss, Map.of("role", "SUPPORT", "active", true)).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT blocked_at IS NULL FROM platform_admins WHERE email = ?", Boolean.class, email)).isTrue();
        assertThat(post("/admin/team/" + id + "/invite", boss, Map.of()).status()).isEqualTo(200);

        // The link cancelled while they were off stays dead; only the newest one works.
        assertThat(post("/public/admin-invite/" + firstLink, null, Map.of("password", "chosen-by-josiah-1")).status()).isNotEqualTo(200);
        String freshLink = tokenIn(inviteTo(email));
        assertThat(post("/public/admin-invite/" + freshLink, null, Map.of("password", "chosen-by-josiah-1")).status()).isEqualTo(200);
        assertThat(canSignInWith(email, "chosen-by-josiah-1")).isTrue();
    }

    @Test
    void onlyTheTeamManagerCanAddOrReinviteAndTheRoleIsStillChecked() {
        String support = post("/admin/auth/demo", null, Map.of("role", "SUPPORT")).at("/token").asText();
        assertThat(post("/admin/team", support, Map.of("name", "X Y", "email", newEmail(), "role", "SUPPORT")).status()).isEqualTo(403);
        assertThat(post("/admin/team", superadmin(), Map.of("name", "X Y", "email", newEmail(), "role", "KING")).status()).isEqualTo(400);
    }
}
