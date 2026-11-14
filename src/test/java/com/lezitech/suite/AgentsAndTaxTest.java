package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.property.LandlordsController;
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

/** Malls & property, package M6: agents' landlords (commission, expenses, payouts, statements), rental tax, WHT certificates. */
class AgentsAndTaxTest extends IntegrationTest {

    @Autowired
    Mailer mailer;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Nairobi"));
    private static final String PERIOD = YearMonth.from(TODAY).toString();
    private EmailSender.LogSender log;

    @BeforeEach
    void capture() {
        log = new EmailSender.LogSender();
        mailer.useSender(log);
        LandlordsController.PUBLIC.clear();
    }

    private String unitId(String owner, String name) {
        for (JsonNode u : get("/property/units", owner).body()) {
            if (name.equals(u.get("name").asText())) {
                return u.get("id").asText();
            }
        }
        return null;
    }

    private String lease(String owner, String unit, int rent) {
        Res r = post("/property/leases", owner, new HashMap<>(Map.of("spaceId", unitId(owner, unit), "tenantName", "Tenant " + unit,
                "rent", rent, "serviceCharge", 0, "startsOn", YearMonth.from(TODAY).atDay(1).toString())));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private String landlord(String owner, String name, String kind, int value, String email) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "commissionKind", kind, "commissionValue", value, "payoutDetails", "Equity 0123"));
        if (email != null) {
            body.put("email", email);
        }
        Res r = post("/property/landlords", owner, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    @Test
    void eachLandlordsStatementShowsCollectedLessCommissionAndExpensesThenPayouts() {
        String owner = token(signup("property"));
        String email = "landlord-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        String kamau = landlord(owner, "Kamau Holdings", "PCT_COLLECTED", 10, email);
        String wanjiru = landlord(owner, "Wanjiru Estate", "PCT_BILLED", 5, null);
        String fixed = landlord(owner, "Otieno Trust", "FIXED", 1500, null);
        post("/property/landlords/" + kamau + "/units", owner, Map.of("spaceIds", List.of(unitId(owner, "G03"))));
        post("/property/landlords/" + wanjiru + "/units", owner, Map.of("spaceIds", List.of(unitId(owner, "G04"))));
        post("/property/landlords/" + fixed + "/units", owner, Map.of("spaceIds", List.of(unitId(owner, "G05"))));
        String g03 = lease(owner, "G03", 10000);
        lease(owner, "G04", 4000);
        lease(owner, "G05", 20000);
        post("/property/invoices/generate", owner, Map.of("period", PERIOD));
        post("/property/leases/" + g03 + "/payments", owner, Map.of("amount", 10000, "method", "CASH"));

        // 10% of collected, an expense, a payout.
        post("/property/landlords/" + kamau + "/expenses", owner, Map.of("what", "Fixed the door", "amount", 500));
        post("/property/landlords/" + kamau + "/payouts", owner, Map.of("amount", 5000, "method", "BANK", "reference", "EQ1"));
        JsonNode s = get("/property/landlords/" + kamau + "/statement?period=" + PERIOD, owner).body();
        assertThat(s.get("billed").decimalValue()).isEqualByComparingTo("10000");
        assertThat(s.get("collected").decimalValue()).isEqualByComparingTo("10000");
        assertThat(s.get("commission").decimalValue()).isEqualByComparingTo("1000");
        assertThat(s.get("net").decimalValue()).isEqualByComparingTo("8500");
        assertThat(s.get("owed").decimalValue()).isEqualByComparingTo("3500");

        // % of billed (nothing collected yet), and a fixed fee.
        assertThat(get("/property/landlords/" + wanjiru + "/statement?period=" + PERIOD, owner).at("/commission").decimalValue()).isEqualByComparingTo("200");
        assertThat(get("/property/landlords/" + fixed + "/statement?period=" + PERIOD, owner).at("/commission").decimalValue()).isEqualByComparingTo("1500");
        assertThat(get("/property/units", owner).body().toString()).contains("Kamau Holdings");

        // Sending keeps the month and emails the private link; the landlord's page opens without a password, no internal ids.
        JsonNode sent = post("/property/landlords/" + kamau + "/send", owner, Map.of("period", PERIOD)).body();
        assertThat(sent.get("emailed").asBoolean()).isTrue();
        assertThat(log.sent.stream().anyMatch(e -> e.to().equals(email) && e.text().contains("/owner/"))).isTrue();
        assertThat(post("/property/landlords/" + wanjiru + "/send", owner, Map.of("period", PERIOD)).at("/emailed").asBoolean()).isFalse();
        String link = s.get("link").asText();
        JsonNode page = get("/public" + link + "?period=" + PERIOD, null).body();
        assertThat(page.at("/landlord/name").asText()).isEqualTo("Kamau Holdings");
        assertThat(page.at("/landlord/id").isMissingNode()).isTrue();
        assertThat(page.at("/net").decimalValue()).isEqualByComparingTo("8500");
        assertThat(get("/public" + link.substring(0, link.length() - 4) + "0000", null).status()).isEqualTo(404);
    }

    @Test
    void rentalTaxOnResidentialRentAndWithholdingTaxCertificates() {
        String owner = token(signup("property"));
        assertThat(post("/spaces", owner, Map.of("name", "A1", "kind", "APARTMENT", "rate", 20000, "capacity", 1)).status()).isEqualTo(200);
        String apartment = lease(owner, "A1", 20000);
        String shop = lease(owner, "G06", 10000);
        post("/property/invoices/generate", owner, Map.of("period", PERIOD));
        post("/property/leases/" + apartment + "/payments", owner, Map.of("amount", 20000, "method", "CASH"));

        JsonNode tax = get("/property/rental-tax?period=" + PERIOD, owner).body();
        assertThat(tax.get("residentialReceived").decimalValue()).isEqualByComparingTo("20000");
        assertThat(tax.get("mri").decimalValue()).isEqualByComparingTo("1500");
        assertThat(tax.get("fileBy").asText()).isEqualTo(YearMonth.from(TODAY).plusMonths(1).atDay(20).toString());
        post("/property/settings", owner, Map.of("mriRate", 10));
        assertThat(get("/property/rental-tax?period=" + PERIOD, owner).at("/mri").decimalValue()).isEqualByComparingTo("2000");

        // A company tenant pays 9,000 and gives a 1,000 withholding-tax certificate: the bill is paid.
        assertThat(post("/property/leases/" + shop + "/wht", owner, Map.of("amount", 1000)).status()).isEqualTo(400);
        assertThat(post("/property/leases/" + shop + "/wht", owner, Map.of("certificateNo", "WHT-2026-001", "amount", 1000)).status()).isEqualTo(200);
        assertThat(post("/property/leases/" + shop + "/wht", owner, Map.of("certificateNo", "wht-2026-001", "amount", 1000)).status()).isEqualTo(409);
        post("/property/leases/" + shop + "/payments", owner, Map.of("amount", 9000, "method", "BANK"));
        assertThat(get("/property/leases/" + shop + "/statement", owner).at("/balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(get("/property/rental-tax?period=" + PERIOD, owner).at("/whtCertificates").toString()).contains("WHT-2026-001");
    }
}
