package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staff on a booking or a sale must be on the same business. Old rows may still hold a stranger's id (there is no
 * foreign key), so no screen may show that person's name, and a stale id must never stop a client paying.
 */
class BookingStaffFenceTest extends IntegrationTest {

    private static final String FOREIGN_NAME = "Zebediah Foreign";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txm;

    /** Runs SQL as the business (so row-level security lets us at its rows), to plant data the API now refuses. */
    private void asTenant(String tenantId, String sql, Object... args) {
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, tenantId);
            jdbc.update(sql, args);
        });
    }

    private String serviceId(String token) {
        for (JsonNode i : get("/items", token).body()) {
            if ("SERVICE".equals(i.get("kind").asText())) {
                return i.get("id").asText();
            }
        }
        throw new AssertionError("The sample salon has a service");
    }

    /** A person on business B with a name nobody else has; returns their id. */
    private String foreignStaff(String ownerB) {
        Res add = post("/team", ownerB, Map.of("name", FOREIGN_NAME, "email", "zeb-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local",
                "password", "password123", "role", "STYLIST", "pin", "1234"));
        assertThat(add.status()).as(add.body().toString()).isEqualTo(200);
        for (JsonNode u : get("/team", ownerB).body()) {
            if (FOREIGN_NAME.equals(u.get("name").asText())) {
                return u.get("id").asText();
            }
        }
        throw new AssertionError("Foreign staff not found");
    }

    @Test
    void bookingWithAnotherBusinessesStaffIsRefusedAndTheirNameNeverShows() {
        JsonNode sa = signup("beauty");
        String ownerA = token(sa);
        String tenantA = sa.at("/tenant/id").asText();
        String slugA = sa.at("/tenant/slug").asText();
        String ownerB = token(signup("beauty"));
        String victim = foreignStaff(ownerB);
        String svc = serviceId(ownerA);
        Instant at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        String day = at.atZone(ZoneId.of("Africa/Nairobi")).toLocalDate().toString();

        Res bad = post("/appointments", ownerA, Map.of("startsAt", at.toString(), "itemId", svc, "staffId", victim, "customerName", "Wanjiku"));
        assertThat(bad.status()).as(bad.body().toString()).isEqualTo(400);
        assertThat(bad.body().toString()).contains("isn't on this business");
        // A made-up id is plain words too, not a crash.
        Res junk = post("/appointments", ownerA, Map.of("startsAt", at.toString(), "staffId", "abc", "customerName", "Wanjiku"));
        assertThat(junk.status()).as(junk.body().toString()).isEqualTo(400);
        assertThat(get("/appointments?date=" + day, ownerA).at("/appointments").size()).isZero();

        // An older booking that already holds the stranger's id: nothing shows the name, on any of the three screens.
        Res ok = post("/appointments", ownerA, Map.of("startsAt", at.toString(), "itemId", svc, "customerName", "Akinyi"));
        assertThat(ok.status()).isEqualTo(200);
        String id = ok.at("/id").asText();
        asTenant(tenantA, "UPDATE appointments SET staff_id = ?::uuid, source = 'ONLINE' WHERE id = ?::uuid", victim, id);
        String tk = new TransactionTemplate(txm).execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, tenantA);
            return jdbc.queryForObject("SELECT token FROM appointments WHERE id = ?::uuid", String.class, id);
        });

        Res pub = get("/public/appointments/" + slugA + "/a/" + tk, null);
        assertThat(pub.status()).as(pub.body().toString()).isEqualTo(200);
        assertThat(pub.body().toString()).doesNotContain(FOREIGN_NAME);
        assertThat(get("/appointments/alerts", ownerA).body().toString()).contains("Akinyi").doesNotContain(FOREIGN_NAME);
        assertThat(get("/appointments?date=" + day, ownerA).body().toString()).contains("Akinyi").doesNotContain(FOREIGN_NAME);
    }

    @Test
    void aBookingWhoseStaffIsGoneCanStillBePaid() {
        JsonNode sa = signup("beauty");
        String ownerA = token(sa);
        String tenantA = sa.at("/tenant/id").asText();
        String ownerB = token(signup("beauty"));
        String victim = foreignStaff(ownerB);
        staff(ownerA, "STYLIST");
        String stylist = userId(ownerA, "STYLIST");
        String svc = serviceId(ownerA);
        Instant at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        // One booking left pointing at another business's person, one at someone who no longer exists.
        String[] stale = {victim, UUID.randomUUID().toString()};
        for (int i = 0; i < stale.length; i++) {
            Res booked = post("/appointments", ownerA, Map.of("startsAt", at.plus(i * 3L, ChronoUnit.HOURS).toString(), "itemId", svc,
                    "staffId", stylist, "customerName", "Client " + i));
            assertThat(booked.status()).as(booked.body().toString()).isEqualTo(200);
            String id = booked.at("/id").asText();
            asTenant(tenantA, "UPDATE appointments SET staff_id = ?::uuid WHERE id = ?::uuid", stale[i], id);

            Res bill = post("/appointments/" + id + "/checkout", ownerA, Map.of());
            assertThat(bill.status()).as("checkout must not be blocked: " + bill.body()).isEqualTo(200);
            assertThat(bill.at("/lines/0/staffId").isMissingNode() || bill.at("/lines/0/staffId").isNull()).isTrue();
            assertThat(bill.toString()).doesNotContain(FOREIGN_NAME);
            double total = bill.at("/total").asDouble();
            Res paid = post("/orders/" + bill.at("/id").asText() + "/pay", ownerA, Map.of("payments", List.of(Map.of("method", "CASH", "amount", total))));
            assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
            assertThat(paid.at("/status").asText()).isEqualTo("PAID");
        }
    }

    @Test
    void salesByStaffNeverShowsAnotherBusinessesName() {
        JsonNode sa = signup("retail");
        String ownerA = token(sa);
        String tenantA = sa.at("/tenant/id").asText();
        String ownerB = token(signup("retail"));
        String victim = foreignStaff(ownerB);
        String itemId = get("/items", ownerA).body().get(0).get("id").asText();

        Res sale = post("/orders", ownerA, Map.of("lines", List.of(Map.of("itemId", itemId, "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "amount", 100000))));
        assertThat(sale.status()).as(sale.body().toString()).isEqualTo(200);
        asTenant(tenantA, "UPDATE order_lines SET staff_id = ?::uuid WHERE order_id = ?::uuid", victim, sale.at("/id").asText());

        Res report = get("/reports/sales", ownerA);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.at("/byStaff").toString()).doesNotContain(FOREIGN_NAME);
        assertThat(report.body().toString()).doesNotContain(FOREIGN_NAME);
    }

    // ---- A booking with no stylist credits nobody, not even whoever took the payment ----

    private static final String CASHIER_NAME = "CASHIER person";

    /** A cashier with an open till, as a cashier needs one before taking any payment. */
    private String tillCashier(String owner) {
        String c = staff(owner, "CASHIER");
        assertThat(post("/shifts/open", c, Map.of("openingCash", 1000)).status()).isEqualTo(200);
        return c;
    }

    private String today() {
        return java.time.LocalDate.now(ZoneId.of("Africa/Nairobi")).toString();
    }

    /** Books a service, optionally leaves a stale stylist id on it, then the cashier checks out and pays cash. Returns {appointmentId, orderId}. */
    private String[] bookAndPay(String owner, String tenant, String cashier, String svc, String staffOnBooking, String staleStaff) {
        Instant at = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Map<String, Object> body = new java.util.HashMap<>(Map.of("startsAt", at.toString(), "itemId", svc, "customerName", "Client"));
        if (staffOnBooking != null) {
            body.put("staffId", staffOnBooking);
        }
        Res booked = post("/appointments", owner, body);
        assertThat(booked.status()).as(booked.body().toString()).isEqualTo(200);
        String id = booked.at("/id").asText();
        if (staleStaff != null) {
            asTenant(tenant, "UPDATE appointments SET staff_id = ?::uuid WHERE id = ?::uuid", staleStaff, id);
        }
        Res bill = post("/appointments/" + id + "/checkout", cashier, Map.of());
        assertThat(bill.status()).as("checkout: " + bill.body()).isEqualTo(200);
        double total = bill.at("/total").asDouble();
        Res paid = post("/orders/" + bill.at("/id").asText() + "/pay", cashier, Map.of("payments", List.of(Map.of("method", "CASH", "amount", total))));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        assertThat(paid.at("/status").asText()).isEqualTo("PAID");
        return new String[]{id, bill.at("/id").asText()};
    }

    /** The person's row on the commission report, or null when they have none. */
    private JsonNode commissionRow(String owner, String userId) {
        Res r = get("/reports/commissions?from=" + today() + "&to=" + today(), owner);
        assertThat(r.status()).as("commissions must be reachable during the trial: " + r.body()).isEqualTo(200);
        for (JsonNode row : r.at("/staff")) {
            if (userId.equals(row.get("userId").asText())) {
                return row;
            }
        }
        return null;
    }

    private boolean listsName(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            if (name.equals(row.get("name").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aBookingWithNoStylistCreditsNobodyNotEvenWhoTookThePayment() {
        JsonNode sa = signup("beauty");
        String owner = token(sa);
        String tenant = sa.at("/tenant/id").asText();
        String cashier = tillCashier(owner);
        String cashierId = userId(owner, "CASHIER");
        // Give the cashier a rate, so a wrong credit would show up as real money.
        assertThat(call("PUT", "/team/" + cashierId, owner, Map.of("commissionRate", 20)).status()).isEqualTo(200);
        String svc = serviceId(owner);
        String stylist = userId(owner, "STYLIST");
        double before = get("/reports/dashboard", owner).at("/kpis/sales_month").asDouble();

        String[] ids = bookAndPay(owner, tenant, cashier, svc, stylist, UUID.randomUUID().toString());

        // The business's takings include the sale in full.
        double total = get("/orders/" + ids[1], owner).at("/total").asDouble();
        assertThat(total).isPositive();
        double after = get("/reports/dashboard", owner).at("/kpis/sales_month").asDouble();
        assertThat(after - before).isCloseTo(total, org.assertj.core.data.Offset.offset(0.005));

        // The person who took the payment is credited with nothing, anywhere.
        assertThat(listsName(get("/reports/dashboard", owner).at("/topStaff"), CASHIER_NAME)).as("topStaff").isFalse();
        assertThat(listsName(get("/reports/sales", owner).at("/byStaff"), CASHIER_NAME)).as("byStaff").isFalse();
        JsonNode row = commissionRow(owner, cashierId);
        if (row != null) {
            assertThat(row.get("lines").asInt()).as("lines credited to the cashier").isZero();
            assertThat(row.get("amount").decimalValue()).isEqualByComparingTo("0");
            assertThat(row.get("commission").decimalValue()).as("commission paid to the person who took the payment").isEqualByComparingTo("0");
        }

        // The stale name is cleared off the booking itself.
        Integer cleared = new TransactionTemplate(txm).execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, tenant);
            return jdbc.queryForObject("SELECT count(*) FROM appointments WHERE id = ?::uuid AND staff_id IS NULL", Integer.class, ids[0]);
        });
        assertThat(cleared).isEqualTo(1);
    }

    @Test
    void aBookingWithARealStylistStillCreditsThatStylist() {
        JsonNode sa = signup("beauty");
        String owner = token(sa);
        String tenant = sa.at("/tenant/id").asText();
        String cashier = tillCashier(owner);
        String cashierId = userId(owner, "CASHIER");
        String stylist = userId(owner, "STYLIST");
        String stylistName = null;
        for (JsonNode u : get("/team", owner).body()) {
            if (stylist.equals(u.get("id").asText())) {
                stylistName = u.get("name").asText();
            }
        }
        assertThat(call("PUT", "/team/" + stylist, owner, Map.of("commissionRate", 30)).status()).isEqualTo(200);

        bookAndPay(owner, tenant, cashier, serviceId(owner), stylist, null);

        assertThat(listsName(get("/reports/dashboard", owner).at("/topStaff"), stylistName)).as("topStaff").isTrue();
        assertThat(listsName(get("/reports/sales", owner).at("/byStaff"), stylistName)).as("byStaff").isTrue();
        JsonNode row = commissionRow(owner, stylist);
        assertThat(row).isNotNull();
        assertThat(row.get("commission").decimalValue()).isPositive();
        JsonNode cashierRow = commissionRow(owner, cashierId);
        assertThat(cashierRow == null || cashierRow.get("lines").asInt() == 0).as("the cashier earns nothing from it").isTrue();
    }

    @Test
    void anOrdinaryTillSaleStillCreditsWhoeverRangItUp() {
        JsonNode sa = signup("beauty");
        String owner = token(sa);
        String cashier = tillCashier(owner);

        Res sale = post("/orders", cashier, Map.of("lines", List.of(Map.of("itemId", serviceId(owner), "qty", 1)),
                "payments", List.of(Map.of("method", "CASH", "amount", 100000))));
        assertThat(sale.status()).as(sale.body().toString()).isEqualTo(200);

        assertThat(listsName(get("/reports/sales", owner).at("/byStaff"), CASHIER_NAME)).as("byStaff").isTrue();
        JsonNode row = commissionRow(owner, userId(owner, "CASHIER"));
        assertThat(row).isNotNull();
        assertThat(row.get("lines").asInt()).isEqualTo(1);
    }
}
