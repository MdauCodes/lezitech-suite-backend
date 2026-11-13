package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
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

/** Malls & property, package M2: deposits, rent increases the owner approves, renewals, lease ends and move-out. */
class LeaseLifeTest extends IntegrationTest {

    @Autowired
    RentJobs jobs;

    @Autowired
    Mailer mailer;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Nairobi"));
    private EmailSender.LogSender log;

    @BeforeEach
    void capture() {
        log = new EmailSender.LogSender();
        mailer.useSender(log);
    }

    private String lease(String owner, String unitName, LocalDate starts, Map<String, Object> extra) {
        String unit = null;
        for (JsonNode u : get("/property/units", owner).body()) {
            if (unitName.equals(u.get("name").asText())) {
                unit = u.get("id").asText();
            }
        }
        Map<String, Object> body = new HashMap<>(Map.of("spaceId", unit, "tenantName", "Otieno Hardware", "phone", "0712345678",
                "rent", 10000, "serviceCharge", 0, "dueDay", 5, "startsOn", starts.toString()));
        body.putAll(extra);
        Res r = post("/property/leases", owner, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private JsonNode unit(String owner, String name) {
        for (JsonNode u : get("/property/units", owner).body()) {
            if (name.equals(u.get("name").asText())) {
                return u;
            }
        }
        return null;
    }

    private JsonNode statement(String owner, String lease) {
        return get("/property/leases/" + lease + "/statement", owner).body();
    }

    @Test
    void theDepositIsBilledAndHeldButIsNotSales() {
        String owner = token(signup("property"));
        String lease = lease(owner, "G03", TODAY.withDayOfMonth(1), Map.of("deposit", 20000));
        assertThat(statement(owner, lease).at("/balance").decimalValue()).isEqualByComparingTo("20000");
        assertThat(statement(owner, lease).at("/lines/0/kind").asText()).isEqualTo("DEPOSIT");
        double salesBefore = get("/reports/dashboard", owner).at("/kpis/sales_today").asDouble();

        post("/property/leases/" + lease + "/payments", owner, Map.of("amount", 20000, "method", "CASH"));
        assertThat(unit(owner, "G03").get("depositHeld").decimalValue()).isEqualByComparingTo("20000");
        assertThat(get("/reports/dashboard", owner).at("/kpis/sales_today").asDouble()).isEqualTo(salesBefore);

        // Already received before BizNez: no bill, but held.
        lease(owner, "G04", TODAY.withDayOfMonth(1), Map.of("deposit", 15000, "depositAlreadyReceived", true));
        assertThat(unit(owner, "G04").get("depositHeld").decimalValue()).isEqualByComparingTo("15000");
        assertThat(unit(owner, "G04").get("arrears").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void rentIncreasesWaitForTheOwnerThenApplyFromTheirMonth() {
        JsonNode s = signup("property");
        String owner = token(s);
        UUID tenant = UUID.fromString(s.at("/tenant/id").asText());
        UUID ownerId = UUID.fromString(s.at("/user/id").asText());
        String tenantEmail = "tenant-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        YearMonth thisMonth = YearMonth.from(TODAY);
        String lease = lease(owner, "G05", thisMonth.minusMonths(11).atDay(1),
                Map.of("email", tenantEmail, "increaseKind", "PERCENT", "increaseValue", 10, "increaseEvery", 12));
        LocalDate due = thisMonth.plusMonths(1).atDay(1);
        assertThat(unit(owner, "G05").get("nextIncreaseOn").asText()).isEqualTo(due.toString());

        // 10 days before: proposed to the owner (an email), nothing changes yet.
        jobs.forTenant(tenant, ownerId, due.minusDays(10));
        JsonNode pending = get("/property/attention", owner).body().get("increases");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("newRent").decimalValue()).isEqualByComparingTo("11000");
        assertThat(log.sent.stream().filter(e -> e.to().equals(s.get("email").asText())).map(EmailSender.Email::subject).toList())
                .contains("Rent increases waiting for your approval");
        jobs.forTenant(tenant, ownerId, due.minusDays(9));
        assertThat(get("/property/attention", owner).body().get("increases")).hasSize(1);

        // The owner settles on 10,800 instead: the tenant is told; next month's bill uses it.
        String inc = pending.get(0).get("id").asText();
        Res ok = post("/property/increases/" + inc + "/approve", owner, Map.of("newRent", 10800));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(ok.at("/noticeStatus").asText()).isEqualTo("SENT");
        assertThat(log.sent.stream().anyMatch(e -> e.to().equals(tenantEmail) && e.text().contains("10,800"))).isTrue();
        assertThat(post("/property/increases/" + inc + "/approve", owner, Map.of()).status()).isEqualTo(409);

        post("/property/invoices/generate", owner, Map.of("period", thisMonth.toString()));
        post("/property/invoices/generate", owner, Map.of("period", thisMonth.plusMonths(1).toString()));
        JsonNode st = statement(owner, lease);
        assertThat(st.at("/balance").decimalValue()).isEqualByComparingTo("20800");
        JsonNode g05 = unit(owner, "G05");
        assertThat(g05.get("leaseRent").decimalValue()).isEqualByComparingTo("10800");
        assertThat(g05.get("nextIncreaseOn").asText()).isEqualTo(due.plusMonths(12).toString());
        JsonNode history = get("/property/leases/" + lease + "/history", owner).body().get("events");
        assertThat(history.toString()).contains("INCREASE_APPROVED").contains("\"INCREASE\"");

        // Skipping one moves the next a full period on.
        String other = lease(owner, "G06", thisMonth.minusMonths(11).atDay(1), Map.of("increaseKind", "FIXED", "increaseValue", 500));
        jobs.forTenant(tenant, ownerId, due.minusDays(10));
        String skip = null;
        for (JsonNode i : get("/property/attention", owner).body().get("increases")) {
            if (other.equals(i.get("leaseId").asText())) {
                skip = i.get("id").asText();
                assertThat(i.get("newRent").decimalValue()).isEqualByComparingTo("10500");
            }
        }
        assertThat(post("/property/increases/" + skip + "/skip", owner, Map.of()).status()).isEqualTo(200);
        assertThat(unit(owner, "G06").get("nextIncreaseOn").asText()).isEqualTo(due.plusMonths(12).toString());
        assertThat(unit(owner, "G06").get("leaseRent").decimalValue()).isEqualByComparingTo("10000");
    }

    @Test
    void movingOutSettlesTheDepositAgainstWhatIsOwedAndRepairs() {
        String owner = token(signup("property"));
        YearMonth thisMonth = YearMonth.from(TODAY);
        String lease = lease(owner, "S03", thisMonth.minusMonths(1).atDay(1), Map.of("deposit", 20000, "depositAlreadyReceived", true));
        post("/property/invoices/generate", owner, Map.of("period", thisMonth.minusMonths(1).toString()));
        post("/property/invoices/generate", owner, Map.of("period", thisMonth.toString()));
        post("/property/leases/" + lease + "/payments", owner, Map.of("amount", 5000, "method", "CASH"));

        List<Map<String, Object>> repairs = List.of(Map.of("what", "Repaint the walls", "amount", 3000));
        JsonNode preview = post("/property/leases/" + lease + "/move-out/preview", owner, Map.of("deductions", repairs)).body();
        assertThat(preview.get("depositHeld").decimalValue()).isEqualByComparingTo("20000");
        assertThat(preview.get("unpaid").decimalValue()).isEqualByComparingTo("15000");
        assertThat(preview.get("toGiveBack").decimalValue()).isEqualByComparingTo("2000");

        Res out = post("/property/leases/" + lease + "/move-out", owner, Map.of("deductions", repairs));
        assertThat(out.status()).as(out.body().toString()).isEqualTo(200);
        assertThat(out.at("/toGiveBack").decimalValue()).isEqualByComparingTo("2000");
        assertThat(unit(owner, "S03").get("leaseId").isNull()).isTrue();
        JsonNode settle = get("/property/attention", owner).body().get("settle");
        assertThat(settle.toString()).contains(lease);

        // The owner pays it back themselves, then records it; no more than is owed.
        assertThat(post("/property/leases/" + lease + "/refund", owner, Map.of("amount", 2500, "method", "MPESA")).status()).isEqualTo(400);
        Res back = post("/property/leases/" + lease + "/refund", owner, Map.of("amount", 2000, "method", "MPESA", "reference", "SKX12"));
        assertThat(back.status()).as(back.body().toString()).isEqualTo(200);
        JsonNode st = statement(owner, lease);
        assertThat(st.at("/balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(st.get("lines").toString()).contains("Given back to you").contains("Repaint the walls").contains("Your deposit, put on your account");
        assertThat(get("/property/attention", owner).body().get("settle").toString()).doesNotContain(lease);
        assertThat(post("/property/leases/" + lease + "/move-out", owner, Map.of()).status()).isEqualTo(409);

        // A tenant whose deposit was never paid and who owes rent: the deposit bill is cancelled; they still owe the rent.
        String owes = lease(owner, "S04", thisMonth.atDay(1), Map.of("deposit", 10000));
        post("/property/invoices/generate", owner, Map.of("period", thisMonth.toString()));
        JsonNode gone = post("/property/leases/" + owes + "/move-out", owner, Map.of()).body();
        assertThat(gone.get("stillOwed").decimalValue()).isEqualByComparingTo("10000");
        assertThat(gone.get("toGiveBack").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void leasesPastTheirEndCarryOnMonthlyAndTheOwnerIsAlertedAhead() {
        JsonNode s = signup("property");
        String owner = token(s);
        UUID tenant = UUID.fromString(s.at("/tenant/id").asText());
        UUID ownerId = UUID.fromString(s.at("/user/id").asText());
        YearMonth thisMonth = YearMonth.from(TODAY);

        // Ended last month on paper, never renewed: still billed this month.
        String past = lease(owner, "S06", thisMonth.minusMonths(3).atDay(1), Map.of("endsOn", thisMonth.minusMonths(1).atEndOfMonth().toString()));
        post("/property/invoices/generate", owner, Map.of("period", thisMonth.toString()));
        assertThat(statement(owner, past).at("/balance").decimalValue()).isEqualByComparingTo("10000");
        assertThat(get("/property/attention", owner).body().get("ending").toString()).contains(past);

        // Renewed for a year at 12,000: from the next bill.
        assertThat(post("/property/leases/" + past + "/renew", owner, Map.of("endsOn", TODAY.plusYears(1).toString(), "rent", 12000)).status()).isEqualTo(200);
        post("/property/invoices/generate", owner, Map.of("period", thisMonth.plusMonths(1).toString()));
        assertThat(statement(owner, past).at("/balance").decimalValue()).isEqualByComparingTo("22000");
        assertThat(post("/property/leases/" + past + "/renew", owner, Map.of("endsOn", TODAY.minusDays(1).toString())).status()).isEqualTo(400);

        // Ending in 45 days: one alert (the 60-day one), not repeated.
        lease(owner, "S07", thisMonth.atDay(1), Map.of("endsOn", TODAY.plusDays(45).toString()));
        String ownerEmail = s.get("email").asText();
        jobs.forTenant(tenant, ownerId, TODAY);
        jobs.forTenant(tenant, ownerId, TODAY);
        assertThat(log.sent.stream().filter(e -> e.to().equals(ownerEmail) && e.subject().contains("Leases coming to an end")).count()).isEqualTo(1);
        assertThat(log.sent.stream().filter(e -> e.to().equals(ownerEmail)).findFirst().get().text()).contains("S07").contains("45 days");
    }
}
