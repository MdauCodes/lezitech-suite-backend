package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The staff table has the same wall round it as every other table (V63). Reading it while a business is in
 * context shows that business's people and nobody else's, even when the SQL asking has forgotten to say so.
 * The other half of this test is the list of things that must still work: signing in, an owner moving between
 * their own businesses, the LeziTech consoles, and support access the owner has granted.
 */
class UsersFenceTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txm;
    @Autowired
    Mailer mailer;

    /**
     * Runs one query the way a request does: through TenantContext.runAs and a transaction, so that
     * TenantTransactionManager is the thing that sets app.tenant. Setting it by hand here would test the
     * policy while leaving out the one component the whole fence depends on.
     */
    private <T> T asBusiness(String tenantId, java.util.function.Supplier<T> work) {
        return TenantContext.runAs(UUID.fromString(tenantId), null,
                () -> new TransactionTemplate(txm).execute(s -> work.get()));
    }

    private Long count(String tenantId, String sql, Object... args) {
        return asBusiness(tenantId, () -> jdbc.queryForObject(sql, Long.class, args));
    }

    private int update(String tenantId, String sql, Object... args) {
        return asBusiness(tenantId, () -> jdbc.update(sql, args));
    }

    @Test
    void oneBusinessCannotReachAnotherBusinessesStaffRowsAtAll() {
        JsonNode sa = signup("retail");
        String ownerA = token(sa);
        String tenantA = sa.at("/tenant/id").asText();
        staff(ownerA, "CASHIER");

        JsonNode sb = signup("retail");
        String ownerB = token(sb);
        String tenantB = sb.at("/tenant/id").asText();
        staff(ownerB, "CASHIER");
        UUID victim = UUID.fromString(userId(ownerB, "CASHIER"));

        // Asking for the other business's rows by name gets nothing, and so does asking for one by id -
        // neither query says a word about tenants, which is the point: the database says no, not the Java.
        assertThat(count(tenantA, "SELECT count(*) FROM users WHERE tenant_id = ?::uuid", tenantB)).isZero();
        assertThat(count(tenantA, "SELECT count(*) FROM users WHERE id = ?", victim)).isZero();

        // Asking for every row gets only business A's own people, and the same count both ways round.
        Long allSeenByA = count(tenantA, "SELECT count(*) FROM users");
        Long ownOfA = count(tenantA, "SELECT count(*) FROM users WHERE tenant_id = ?::uuid", tenantA);
        assertThat(allSeenByA).isEqualTo(ownOfA);
        assertThat(allSeenByA).isGreaterThanOrEqualTo(2L);
        assertThat(count(tenantB, "SELECT count(*) FROM users")).isEqualTo(
                count(tenantB, "SELECT count(*) FROM users WHERE tenant_id = ?::uuid", tenantB));

        Long ownOfBBefore = count(tenantB, "SELECT count(*) FROM users WHERE tenant_id = ?::uuid", tenantB);

        // Writing is fenced too: business A changes nothing in business B, and nothing was changed.
        assertThat(update(tenantA, "UPDATE users SET name = 'Taken over' WHERE id = ?", victim)).isZero();
        assertThat(count(tenantB, "SELECT count(*) FROM users WHERE id = ? AND name = 'Taken over'", victim)).isZero();

        // And a row cannot be pushed across the wall either: that is refused outright, not quietly ignored.
        // Matched on the SQL state rather than the words, which change with the server's language.
        Throwable refused = catchThrowable(() -> update(tenantA,
                "UPDATE users SET tenant_id = ?::uuid WHERE tenant_id = ?::uuid AND role = 'CASHIER'", tenantB, tenantA));
        assertThat(refused).isNotNull();
        Throwable root = refused;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException) root).getSQLState()).isEqualTo("42501");
        assertThat(count(tenantB, "SELECT count(*) FROM users WHERE tenant_id = ?::uuid", tenantB)).isEqualTo(ownOfBBefore);
    }

    /**
     * ops/go-live.md section 11b asks the owner to run one query against the live database before deploying
     * V63, to find rows still pointing at a staff member in another business (leftovers from the bugs V63
     * closes, which the fence would otherwise hide). This runs that exact query, so we never hand them SQL
     * that has not been run: it must work, name only real catalogue objects, and find nothing here.
     */
    @Test
    void theCheckTheOwnerRunsBeforeDeployingWorks() {
        signup("retail");
        signup("beauty");
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT table_name, column_name, cnt AS rows_pointing_at_another_business
                FROM (
                  SELECT c.table_name, c.column_name,
                         (xpath('/row/c/text()', query_to_xml(format(
                            'SELECT count(*) AS c FROM %I t JOIN users u ON u.id = t.%I WHERE u.tenant_id <> t.tenant_id',
                            c.table_name, c.column_name), false, true, '')))[1]::text::bigint AS cnt
                  FROM information_schema.columns c
                  JOIN information_schema.tables bt ON bt.table_schema = 'public' AND bt.table_name = c.table_name
                                                   AND bt.table_type = 'BASE TABLE'
                  JOIN information_schema.columns tc ON tc.table_schema = 'public' AND tc.table_name = c.table_name
                                                    AND tc.column_name = 'tenant_id'
                  WHERE c.table_schema = 'public' AND c.data_type = 'uuid'
                    AND c.column_name NOT IN ('id', 'tenant_id') AND c.table_name <> 'users'
                ) x
                WHERE cnt > 0 ORDER BY 1, 2""");
        assertThat(found).as("rows pointing at another business's staff: " + found).isEmpty();
    }

    /** Signing in has to work before anyone knows which business it is, so the fence must not be up yet. */
    @Test
    void signingInStillWorks() {
        JsonNode sa = signup("retail");
        String email = sa.get("email").asText();
        Res in = post("/auth/login", null, Map.of("email", email, "password", "password123"));
        assertThat(in.status()).as(in.body().toString()).isEqualTo(200);
        assertThat(in.at("/token").asText()).isNotBlank();

        // A staff member added by the owner can then sign in on their own, which is the same lookup by email.
        String cashier = staff(token(sa), "CASHIER");
        assertThat(cashier).isNotBlank();
        Res me = get("/auth/me", cashier);
        assertThat(me.status()).as(me.body().toString()).isEqualTo(200);
    }

    /**
     * Resetting a forgotten password is the one path that both reads and writes the staff table inside a
     * transaction with no business in context at all - nobody is signed in, and the email is all there is to
     * go on. If the fence is ever tightened, this is the first thing that breaks. The other tenant-free paths
     * are covered where they live: ConfirmEmailTest for /auth/confirm-email and the 6-digit codes that go
     * through LoginCodes, StaffPinTest for a staff member's own till PIN, OnboardingTest for operator setup.
     */
    @Test
    void aForgottenPasswordCanStillBeResetWithNobodySignedIn() {
        EmailSender.LogSender outbox = new EmailSender.LogSender();
        mailer.useSender(outbox);
        JsonNode sa = signup("retail");
        String email = sa.get("email").asText();

        assertThat(post("/auth/code", null, Map.of("email", email)).status()).isEqualTo(200);
        EmailSender.Email sent = outbox.sent.stream().filter(m -> m.to().equals(email)).reduce((a, b) -> b).orElseThrow();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{6})").matcher(sent.text());
        assertThat(m.find()).as(sent.text()).isTrue();

        Res reset = post("/auth/reset", null, Map.of("email", email, "code", m.group(1), "password", "brand-new-pass"));
        assertThat(reset.status()).as(reset.body().toString()).isEqualTo(200);
        assertThat(reset.at("/token").asText()).isNotBlank();
        // The new password really took, which means the write landed and was not fenced away.
        assertThat(post("/auth/login", null, Map.of("email", email, "password", "brand-new-pass")).status()).isEqualTo(200);
    }

    /** An owner with several businesses reads rows belonging to each of them to list and open them. */
    @Test
    void anOwnerWithSeveralBusinessesCanStillListAndOpenThem() {
        JsonNode sa = signup("retail");
        String owner = token(sa);

        Res added = post("/businesses", owner, Map.of("name", "Second Shop", "sector", "retail", "withSamples", false));
        assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
        String secondTenant = added.at("/tenant/id").asText();

        Res list = get("/businesses", owner);
        assertThat(list.status()).as(list.body().toString()).isEqualTo(200);
        assertThat(list.at("/businesses").size()).isEqualTo(2);

        Res opened = post("/businesses/" + secondTenant + "/switch", owner, Map.of());
        assertThat(opened.status()).as(opened.body().toString()).isEqualTo(200);
        assertThat(opened.at("/token").asText()).isNotBlank();
        assertThat(opened.at("/tenant/id").asText()).isEqualTo(secondTenant);
    }

    /** The admin and operator consoles exist to see across businesses; they still do. */
    @Test
    void theLeziTechConsolesStillSeeEveryBusinessesStaff() {
        JsonNode sa = signup("retail");
        String tenantA = sa.at("/tenant/id").asText();
        String ownerEmail = sa.get("email").asText();
        staff(token(sa), "MANAGER");
        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();

        Res one = get("/admin/tenants/" + tenantA, admin);
        assertThat(one.status()).as(one.body().toString()).isEqualTo(200);
        assertThat(one.at("/users").size()).isGreaterThanOrEqualTo(2);

        Res many = get("/admin/tenants?q=" + ownerEmail, admin);
        assertThat(many.status()).as(many.body().toString()).isEqualTo(200);
        assertThat(many.body().toString()).contains(ownerEmail);

        Res over = get("/admin/overview", admin);
        assertThat(over.status()).as(over.body().toString()).isEqualTo(200);
    }

    /** Support access names the owner who granted it, which is a read of that business's staff row. */
    @Test
    void supportAccessTheOwnerGrantedStillWorks() {
        JsonNode sa = signup("retail");
        String owner = token(sa);
        String tenant = sa.at("/tenant/id").asText();
        String support = post("/admin/auth/demo", null, Map.of("role", "SUPPORT")).at("/token").asText();

        assertThat(post("/support-access", owner, Map.of("note", "Help with the menu")).at("/active").asBoolean()).isTrue();
        Res seen = get("/admin/tenants/" + tenant, support);
        assertThat(seen.status()).as(seen.body().toString()).isEqualTo(200);
        assertThat(seen.at("/supportAccess/active").asBoolean()).isTrue();

        Res as = openBusiness(support, "support@lezitech.test", tenant, "HELP", "Fixing the menu");
        assertThat(as.status()).as(as.body().toString()).isEqualTo(200);
        assertThat(as.at("/token").asText()).isNotBlank();
    }

    /**
     * Emails are unique across the whole platform, so the "already in use" check has to look past the fence.
     * Without that, adding a staff member on an address another business uses would crash instead of saying so.
     */
    @Test
    void anEmailAnotherBusinessAlreadyUsesIsRefusedInPlainWords() {
        JsonNode sa = signup("retail");
        String takenEmail = sa.get("email").asText();
        String ownerB = token(signup("retail"));

        Res clash = post("/team", ownerB, Map.of("name", "Clash", "email", takenEmail,
                "password", "password123", "role", "CASHIER", "pin", "1234"));
        assertThat(clash.status()).as(clash.body().toString()).isEqualTo(409);
        assertThat(clash.body().toString()).contains("already in use");

        // Editing a staff member onto a taken address says the same, and a free address is accepted.
        staff(ownerB, "CASHIER");
        String mine = userId(ownerB, "CASHIER");
        Res edit = call("PUT", "/team/" + mine, ownerB, Map.of("email", takenEmail));
        assertThat(edit.status()).as(edit.body().toString()).isEqualTo(409);
        Res fine = call("PUT", "/team/" + mine, ownerB, Map.of("email", "free-" + UUID.randomUUID() + "@test.local"));
        assertThat(fine.status()).as(fine.body().toString()).isEqualTo(200);

        // And signing up a whole new business on a taken address still says so rather than crashing.
        Res dup = post("/auth/signup", null, Map.of("businessName", "Copycat", "sector", "retail",
                "name", "Owner", "email", takenEmail, "password", "password123", "withSamples", false));
        assertThat(dup.status()).as(dup.body().toString()).isEqualTo(409);
    }
}
