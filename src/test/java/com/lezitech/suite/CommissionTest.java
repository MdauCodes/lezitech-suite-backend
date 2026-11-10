package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Commissions: staff rates, a service's own rate, refunds not paying out, and what's still owed after a payout. */
class CommissionTest extends IntegrationTest {

    private JsonNode rowFor(JsonNode report, String userId) {
        for (JsonNode r : report.at("/staff")) {
            if (userId.equals(r.get("userId").asText())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + userId + " in " + report);
    }

    @Test
    void stylistEarnsTheirRateOrTheServicesOwnAndThePayoutReducesWhatIsOwed() {
        String owner = token(signup("beauty"));
        String stylist = userId(owner, "STYLIST");
        assertThat(call("PUT", "/team/" + stylist, owner, Map.of("commissionRate", 30)).at("/commissionRate").decimalValue())
                .isEqualByComparingTo("30");
        assertThat(call("PUT", "/team/" + stylist, owner, Map.of("commissionRate", 150)).status()).isEqualTo(400);

        // Two services: one at the stylist's 30%, one with its own 50%.
        JsonNode wash = item(owner, "Wash & blow-dry");
        JsonNode other = null;
        for (JsonNode i : get("/items", owner).body()) {
            if (!i.get("id").asText().equals(wash.get("id").asText()) && "SERVICE".equals(i.get("kind").asText())) {
                other = i;
                break;
            }
        }
        Map<String, Object> otherBody = new java.util.HashMap<>(Map.of("name", other.get("name").asText(), "kind", "SERVICE",
                "price", other.get("price").asText(), "commissionRate", 50));
        assertThat(call("PUT", "/items/" + other.get("id").asText(), owner, otherBody).at("/commissionRate").decimalValue()).isEqualByComparingTo("50");

        BigDecimal washPrice = wash.get("price").decimalValue();
        BigDecimal otherPrice = other.get("price").decimalValue();
        List<Map<String, Object>> lines = List.of(
                Map.of("itemId", wash.get("id").asText(), "qty", 1, "staffId", stylist),
                Map.of("itemId", other.get("id").asText(), "qty", 1, "staffId", stylist));
        Res sale = post("/orders", owner, Map.of("lines", lines, "payments", List.of(Map.of("method", "CASH", "amount", 100000))));
        assertThat(sale.status()).as(sale.body().toString()).isEqualTo(200);
        // A refunded sale earns nothing.
        String refunded = post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", wash.get("id").asText(), "qty", 1, "staffId", stylist)),
                "payments", List.of(Map.of("method", "CASH", "amount", 100000)))).at("/id").asText();
        assertThat(post("/orders/" + refunded + "/refund", owner, Map.of("reason", "Unhappy client")).status()).isEqualTo(200);

        String today = LocalDate.now(ZoneId.of("Africa/Nairobi")).toString();
        String q = "/reports/commissions?from=" + today + "&to=" + today;
        JsonNode r = rowFor(get(q, owner).body(), stylist);
        BigDecimal expected = washPrice.multiply(new BigDecimal("0.30")).add(otherPrice.multiply(new BigDecimal("0.50")));
        assertThat(r.get("commission").decimalValue()).isEqualByComparingTo(expected);
        assertThat(r.get("owed").decimalValue()).isEqualByComparingTo(expected);

        // Paid part of it by M-Pesa.
        assertThat(post("/reports/commissions/payouts", owner, Map.of("userId", stylist, "from", today, "to", today,
                "amount", 100, "method", "MPESA", "reference", "TKL9XYZ")).status()).isEqualTo(200);
        JsonNode after = get(q, owner).body();
        assertThat(rowFor(after, stylist).get("owed").decimalValue()).isEqualByComparingTo(expected.subtract(new BigDecimal("100")));
        assertThat(after.at("/payouts/0/method").asText()).isEqualTo("MPESA");

        // Cashiers can't see or pay commissions.
        String cashier = staff(owner, "CASHIER");
        assertThat(get(q, cashier).status()).isEqualTo(403);
        assertThat(post("/reports/commissions/payouts", cashier, Map.of("userId", stylist, "from", today, "to", today, "amount", 5)).status()).isEqualTo(403);
        // Another business never sees this one's staff.
        String other2 = token(signup("beauty"));
        assertThat(get(q, other2).body().at("/staff").toString()).doesNotContain(stylist);
        assertThat(post("/reports/commissions/payouts", other2, Map.of("userId", stylist, "from", today, "to", today, "amount", 5)).status())
                .isEqualTo(404);
        assertThat(UUID.fromString(stylist)).isNotNull();
    }
}
