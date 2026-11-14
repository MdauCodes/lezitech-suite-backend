package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.property.PublicStatementController;
import com.lezitech.suite.property.RentJobs;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Malls & property, package M1: money from tenants, credit, statements, penalties, paybill matching and reminders. */
class RentCollectionTest extends IntegrationTest {

    @Autowired
    RentJobs jobs;

    @Autowired
    Mailer mailer;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Nairobi"));

    @BeforeEach
    void freshLimits() {
        PublicStatementController.PER_CLIENT.clear();
    }

    /** A new lease of KES 10,000 a month, no service charge, on a vacant unit, starting at the start of {@code from}. */
    private String lease(String owner, String unitName, YearMonth from, Map<String, Object> extra) {
        String unit = null;
        for (JsonNode u : get("/property/units", owner).body()) {
            if (unitName.equals(u.get("name").asText())) {
                unit = u.get("id").asText();
            }
        }
        Map<String, Object> body = new HashMap<>(Map.of("spaceId", unit, "tenantName", "Achieng Traders", "phone", "0712345678",
                "rent", 10000, "serviceCharge", 0, "dueDay", 5, "startsOn", from.atDay(1).toString()));
        body.putAll(extra);
        Res r = post("/property/leases", owner, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private JsonNode statement(String owner, String lease) {
        return get("/property/leases/" + lease + "/statement", owner).body();
    }

    @Test
    void moneyGoesOnTheOldestInvoiceFirstAndExtraIsCreditForTheNextOne() {
        String owner = token(signup("property"));
        YearMonth now = YearMonth.from(TODAY);
        String lease = lease(owner, "G03", now.minusMonths(1), Map.of());
        post("/property/invoices/generate", owner, Map.of("period", now.minusMonths(1).toString()));
        post("/property/invoices/generate", owner, Map.of("period", now.toString()));
        assertThat(statement(owner, lease).at("/balance").decimalValue()).isEqualByComparingTo("20000");

        // 25,000 in cash pays both months; 5,000 is kept as credit.
        Res paid = post("/property/leases/" + lease + "/payments", owner, Map.of("amount", 25000, "method", "CASH"));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        assertThat(paid.at("/toInvoices").decimalValue()).isEqualByComparingTo("20000");
        assertThat(paid.at("/toCredit").decimalValue()).isEqualByComparingTo("5000");
        assertThat(paid.at("/balance").decimalValue()).isEqualByComparingTo("-5000");
        assertThat(statement(owner, lease).at("/open")).isEmpty();

        // Next month's invoice uses the credit: half of it is already paid.
        post("/property/invoices/generate", owner, Map.of("period", now.plusMonths(1).toString()));
        JsonNode s = statement(owner, lease);
        assertThat(s.at("/lease/credit").decimalValue()).isEqualByComparingTo("0");
        assertThat(s.at("/balance").decimalValue()).isEqualByComparingTo("5000");
        assertThat(s.at("/open/0/unpaid").decimalValue()).isEqualByComparingTo("5000");

        // The statement: two invoices raised so far this period (next month's is dated today too) and one payment, adding up.
        assertThat(s.at("/billed").decimalValue()).isEqualByComparingTo("30000");
        assertThat(s.at("/paid").decimalValue()).isEqualByComparingTo("25000");
        assertThat(s.at("/closing").decimalValue()).isEqualByComparingTo("5000");
        JsonNode lines = s.get("lines");
        assertThat(lines.get(lines.size() - 1).get("balance").decimalValue()).isEqualByComparingTo("5000");

        // An M-Pesa code is recorded once only, and needs to be given.
        assertThat(post("/property/leases/" + lease + "/payments", owner, Map.of("amount", 1000, "method", "MPESA")).status()).isEqualTo(400);
        String code = "QK" + System.nanoTime();
        assertThat(post("/property/leases/" + lease + "/payments", owner, Map.of("amount", 1000, "method", "MPESA", "reference", code)).status()).isEqualTo(200);
        assertThat(post("/property/leases/" + lease + "/payments", owner, Map.of("amount", 1000, "method", "MPESA", "reference", code)).status()).isEqualTo(409);
        assertThat(statement(owner, lease).at("/balance").decimalValue()).isEqualByComparingTo("4000");
    }

    @Test
    void aLateInvoiceGetsOnePenaltyWhichTheOwnerCanLift() {
        JsonNode s = signup("property");
        String owner = token(s);
        UUID tenant = UUID.fromString(s.at("/tenant/id").asText());
        UUID ownerId = UUID.fromString(s.at("/user/id").asText());
        YearMonth last = YearMonth.from(TODAY).minusMonths(1);
        String lease = lease(owner, "G04", last, Map.of("penaltyKind", "FIXED", "penaltyValue", 750, "penaltyGraceDays", 5));
        post("/property/invoices/generate", owner, Map.of("period", last.toString()));
        LocalDate due = last.atDay(5);

        // Within the grace days: no penalty.
        jobs.forTenant(tenant, ownerId, due.plusDays(5));
        assertThat(statement(owner, lease).at("/balance").decimalValue()).isEqualByComparingTo("10000");
        // After them: one penalty, however often the job runs.
        jobs.forTenant(tenant, ownerId, due.plusDays(6));
        jobs.forTenant(tenant, ownerId, due.plusDays(20));
        JsonNode st = statement(owner, lease);
        assertThat(st.at("/balance").decimalValue()).isEqualByComparingTo("10750");
        String penalty = null;
        for (JsonNode o : st.get("open")) {
            if (o.get("penalty").asBoolean()) {
                penalty = o.get("id").asText();
            }
        }
        assertThat(penalty).isNotNull();

        // The tenant pays it all; the owner then lifts the penalty: the 750 comes back as credit.
        post("/property/leases/" + lease + "/payments", owner, Map.of("amount", 10750, "method", "BANK", "reference", "EQ123"));
        assertThat(statement(owner, lease).at("/balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(post("/property/penalties/" + penalty + "/lift", owner, Map.of()).status()).isEqualTo(400);
        Res lifted = post("/property/penalties/" + penalty + "/lift", owner, Map.of("reason", "First time late, agreed on the phone"));
        assertThat(lifted.status()).as(lifted.body().toString()).isEqualTo(200);
        assertThat(lifted.at("/balance").decimalValue()).isEqualByComparingTo("-750");
        assertThat(statement(owner, lease).at("/lease/credit").decimalValue()).isEqualByComparingTo("750");
        assertThat(post("/property/penalties/" + penalty + "/lift", owner, Map.of("reason", "again")).status()).isEqualTo(409);
        // The lifted penalty is never raised again for that invoice.
        jobs.forTenant(tenant, ownerId, due.plusDays(21));
        assertThat(statement(owner, lease).at("/balance").decimalValue()).isEqualByComparingTo("-750");
        // And it's in the activity log.
        boolean logged = false;
        for (JsonNode a : get("/activity", owner).body().path("entries")) {
            logged |= "PENALTY_LIFTED".equals(a.path("kind").asText());
        }
        assertThat(logged).isTrue();
    }

    @Test
    void paybillPaymentsFindTheUnitByItsAccountNumberOrWaitForTheLandlord() {
        String owner = token(signup("property"));
        String till = String.valueOf(700000 + (int) (Math.random() * 99999));
        assertThat(call("PUT", "/settings", owner, Map.of("settings", Map.of("mpesaTill", till))).status()).isEqualTo(200);
        YearMonth now = YearMonth.from(TODAY);
        String lease = lease(owner, "G05", now, Map.of());
        post("/property/invoices/generate", owner, Map.of("period", now.toString()));

        // The tenant types "g-05": it's G05's.
        String trans = "TR" + System.nanoTime();
        post("/public/mpesa/c2b/test-c2b-secret/confirmation", null, Map.of("TransID", trans, "TransAmount", "4000",
                "BusinessShortCode", till, "BillRefNumber", "g-05", "MSISDN", "254712345678", "FirstName", "ACHIENG"));
        assertThat(statement(owner, lease).at("/balance").decimalValue()).isEqualByComparingTo("6000");

        // A typo: it waits in the landlord's list, who says whose it is.
        String trans2 = "TR" + System.nanoTime();
        post("/public/mpesa/c2b/test-c2b-secret/confirmation", null, Map.of("TransID", trans2, "TransAmount", "6000",
                "BusinessShortCode", till, "BillRefNumber", "GO5", "MSISDN", "254712345678"));
        String waiting = null;
        for (JsonNode p : get("/property/paybill", owner).body()) {
            if (trans2.equals(p.get("receipt").asText())) {
                waiting = p.get("id").asText();
            }
        }
        assertThat(waiting).isNotNull();
        assertThat(post("/property/paybill/" + waiting + "/assign", owner, Map.of("leaseId", lease)).status()).isEqualTo(200);
        assertThat(statement(owner, lease).at("/balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(post("/property/paybill/" + waiting + "/assign", owner, Map.of("leaseId", lease)).status()).isEqualTo(409);

        // The owner can give a unit its own account number; two units can't share one.
        String g06 = null;
        String g05 = null;
        for (JsonNode u : get("/property/units", owner).body()) {
            if ("G06".equals(u.get("name").asText())) {
                g06 = u.get("id").asText();
            }
            if ("G05".equals(u.get("name").asText())) {
                g05 = u.get("id").asText();
                assertThat(u.get("payAccount").asText()).isEqualTo("G05");
            }
        }
        assertThat(post("/property/units/" + g06 + "/account", owner, Map.of("accountCode", "g05")).status()).isEqualTo(409);
        assertThat(post("/property/units/" + g05 + "/account", owner, Map.of("accountCode", "shop 5")).at("/accountCode").asText()).isEqualTo("SHOP5");
    }

    @Test
    void aPromptToTheTenantsPhonePaysTheirRent() throws Exception {
        String owner = token(signup("property"));
        YearMonth now = YearMonth.from(TODAY);
        String lease = lease(owner, "G06", now, Map.of());
        post("/property/invoices/generate", owner, Map.of("period", now.toString()));

        Res start = post("/property/leases/" + lease + "/prompt", owner, Map.of());
        assertThat(start.status()).as(start.body().toString()).isEqualTo(200);
        assertThat(start.at("/amount").decimalValue()).isEqualByComparingTo("10000");
        JsonNode r = null;
        for (int i = 0; i < 50; i++) {
            r = get("/mpesa/requests/" + start.at("/id").asText(), owner).body();
            if (!"PENDING".equals(r.get("status").asText())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(r.get("status").asText()).isEqualTo("PAID");
        assertThat(statement(owner, lease).at("/balance").decimalValue()).isEqualByComparingTo("0");
        // Nothing owed now: a prompt needs an amount (paying ahead).
        assertThat(post("/property/leases/" + lease + "/prompt", owner, Map.of()).status()).isEqualTo(400);
    }

    @Test
    void remindersGoByEmailOnceEachAndTheLinkShowsOnlyThatTenantsStatement() {
        JsonNode s = signup("property");
        String owner = token(s);
        UUID tenant = UUID.fromString(s.at("/tenant/id").asText());
        UUID ownerId = UUID.fromString(s.at("/user/id").asText());
        EmailSender.LogSender log = new EmailSender.LogSender();
        mailer.useSender(log);
        YearMonth last = YearMonth.from(TODAY).minusMonths(1);
        String email = "tenant-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        String lease = lease(owner, "S03", last, Map.of("email", email));
        assertThat(post("/property/settings", owner, Map.of("daysBefore", 2, "daysAfter", List.of(3, 10),
                "payInstructions", "Paybill 522522")).status()).isEqualTo(200);
        assertThat(get("/property/settings", owner).at("/onDueDay").asBoolean()).isTrue();
        post("/property/invoices/generate", owner, Map.of("period", last.toString()));
        LocalDate due = last.atDay(5);

        jobs.forTenant(tenant, ownerId, due.minusDays(2));
        jobs.forTenant(tenant, ownerId, due.minusDays(1));
        jobs.forTenant(tenant, ownerId, due);
        jobs.forTenant(tenant, ownerId, due.plusDays(3));
        jobs.forTenant(tenant, ownerId, due.plusDays(4));
        List<EmailSender.Email> mine = log.sent.stream().filter(e -> e.to().equals(email)).toList();
        assertThat(mine).hasSize(3);
        assertThat(mine.get(0).subject()).contains("please pay rent for");
        assertThat(mine.get(1).subject()).contains("Today is the day to pay");
        assertThat(mine.get(2).subject()).contains("3 days late");
        assertThat(mine.get(2).text()).contains("Paybill 522522").contains("S03").contains("/statement/");

        // Paid: no more reminders.
        post("/property/leases/" + lease + "/payments", owner, Map.of("amount", 10000, "method", "CASH"));
        jobs.forTenant(tenant, ownerId, due.plusDays(10));
        assertThat(log.sent.stream().filter(e -> e.to().equals(email)).count()).isEqualTo(3);

        // The tenant's link: their statement only, no internal ids; a wrong token finds nothing.
        String link = statement(owner, lease).at("/link").asText();
        JsonNode pub = get("/public" + link, null).body();
        assertThat(pub.at("/lease/unitName").asText()).isEqualTo("S03");
        assertThat(pub.at("/lease/id").isMissingNode()).isTrue();
        assertThat(pub.at("/balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(pub.at("/payInstructions").asText()).isEqualTo("Paybill 522522");
        // What the service charge pays for, in the landlord's words, reaches the tenant.
        post("/property/settings", owner, Map.of("serviceIncludes", List.of("Security guards", " ", "Cleaning of shared areas")));
        assertThat(get("/public" + link, null).at("/serviceIncludes").toString()).isEqualTo("[\"Security guards\",\"Cleaning of shared areas\"]");
        assertThat(get("/public" + link.substring(0, link.length() - 4) + "0000", null).status()).isEqualTo(404);
        // A new link stops the old one working.
        String fresh = post("/property/leases/" + lease + "/statement/new-link", owner, Map.of()).at("/link").asText();
        assertThat(get("/public" + link, null).status()).isEqualTo(404);
        assertThat(get("/public" + fresh, null).status()).isEqualTo(200);
    }
}
