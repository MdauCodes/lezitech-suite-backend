package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stall excuse, the called-off reservation and the cancelled trip booking all change their own status at once,
 * but their BILL is cancelled only by a job that may cancel bills; otherwise a manager is asked.
 * RoleGuard lets COLLECTOR reach /api/market, RECEPTION and MANAGER reach /api/stays, SALES and MANAGER reach /api/trips,
 * so each test uses the real job.
 */
class CancelNeedsAManagerTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Nairobi"));

    private record Market(String owner, String manager, String collector, String lease, String charge) {
    }

    private Market market() {
        String owner = token(signup("property"));
        String unit = null;
        for (JsonNode u : get("/property/units", owner).body()) {
            if ("S03".equals(u.get("name").asText())) {
                unit = u.get("id").asText();
            }
        }
        Map<String, Object> body = new HashMap<>(Map.of("spaceId", unit, "tenantName", "Mama S03", "phone", "0712345678",
                "rent", 50, "serviceCharge", 0, "startsOn", TODAY.toString(), "frequency", "DAILY"));
        Res l = post("/property/leases", owner, body);
        assertThat(l.status()).as(l.body().toString()).isEqualTo(200);
        String lease = l.at("/id").asText();
        String manager = staff(owner, "MANAGER");
        String collector = staff(owner, "COLLECTOR");
        String charge = null;
        for (JsonNode s : get("/market/round", collector).body().get("stalls")) {
            if (lease.equals(s.get("leaseId").asText())) {
                charge = s.get("chargeId").asText();
            }
        }
        assertThat(charge).isNotNull();
        return new Market(owner, manager, collector, lease, charge);
    }

    private String orderStatus(String owner, String id) {
        return get("/orders/" + id, owner).at("/status").asText();
    }

    private int waiting(String manager) {
        return get("/approvals/waiting", manager).at("/requests").size();
    }

    @Test
    void aCollectorExcusingAStallDayAsksInstead() {
        Market m = market();
        Res r = post("/market/excuse", m.collector(), Map.of("leaseId", m.lease(), "reason", "Sick"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/excused").asBoolean()).isFalse();
        assertThat(r.at("/asked").asBoolean()).isTrue();
        assertThat(orderStatus(m.owner(), m.charge())).isEqualTo("OPEN");
        JsonNode w = get("/approvals/waiting", m.manager()).at("/requests");
        assertThat(w.size()).isEqualTo(1);
        assertThat(w.get(0).get("message").asText()).containsIgnoringCase("excuse");
    }

    @Test
    void withTheOwnersSwitchOnTheCollectorExcusesItDirectly() {
        Market m = market();
        Res set = call("PUT", "/settings", m.owner(), Map.of("settings", Map.of("access", Map.of("COLLECTOR", Map.of("cancels", true)))));
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        Res r = post("/market/excuse", m.collector(), Map.of("leaseId", m.lease(), "reason", "Sick"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/excused").asBoolean()).isTrue();
        assertThat(orderStatus(m.owner(), m.charge())).isEqualTo("VOID");
        assertThat(waiting(m.manager())).isZero();
    }

    /** A hotel with a reservation holding an unpaid folio. */
    private record Hotel(String owner, String manager, String reception, String stay, String folio) {
    }

    private Hotel hotel() {
        String owner = token(signup("hotel"));
        LocalDate in = TODAY.plusDays(20);
        LocalDate out = in.plusDays(2);
        JsonNode free = get("/rooms/available?checkIn=" + in + "&checkOut=" + out, owner).body();
        String room = free.get(0).get("id").asText();
        Res reserved = post("/stays", owner, Map.of("spaceId", room, "checkIn", in.toString(), "checkOut", out.toString(),
                "guestName", "Achieng Otieno", "phone", "0712000111", "rate", 5000));
        assertThat(reserved.status()).as(reserved.body().toString()).isEqualTo(200);
        String folio = reserved.at("/folioId").asText();
        assertThat(folio).isNotBlank();
        assertThat(orderStatus(owner, folio)).isEqualTo("OPEN");
        return new Hotel(owner, staff(owner, "MANAGER"), staff(owner, "RECEPTION"), reserved.at("/id").asText(), folio);
    }

    @Test
    void receptionCallingOffAReservationAsksAboutTheBill() {
        Hotel h = hotel();
        Res r = post("/stays/" + h.stay() + "/cancel", h.reception(), Map.of());
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/message").asText()).contains("manager has been asked");
        assertThat(get("/stays/" + h.stay(), h.owner()).at("/status").asText()).isEqualTo("CANCELLED");
        assertThat(orderStatus(h.owner(), h.folio())).isEqualTo("OPEN");
        assertThat(waiting(h.manager())).isEqualTo(1);
    }

    @Test
    void aManagerCallingOffTheSameReservationCancelsTheBillThereAndThen() {
        Hotel h = hotel();
        Res r = post("/stays/" + h.stay() + "/cancel", h.manager(), Map.of());
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(get("/stays/" + h.stay(), h.owner()).at("/status").asText()).isEqualTo("CANCELLED");
        assertThat(orderStatus(h.owner(), h.folio())).isEqualTo("VOID");
        assertThat(waiting(h.manager())).isZero();
    }

    private record Tour(String owner, String manager, String sales, String booking, String invoice) {
    }

    private Tour tour() {
        String owner = token(signup("tours"));
        JsonNode pkg = item(owner, "");
        Res trip = post("/trips", owner, Map.of("itemId", pkg.get("id").asText(), "startsOn", TODAY.plusDays(21).toString(),
                "seats", 4, "vehicle", "Land Cruiser KDA 123A", "guide", "Joseph"));
        assertThat(trip.status()).as(trip.body().toString()).isEqualTo(200);
        Res bk = post("/trips/" + trip.at("/id").asText() + "/bookings", owner, Map.of("leadName", "The Smiths", "pax", 2));
        assertThat(bk.status()).as(bk.body().toString()).isEqualTo(200);
        JsonNode booked = null;
        for (JsonNode b : get("/trips/" + trip.at("/id").asText(), owner).at("/bookings")) {
            booked = b;
        }
        assertThat(booked).isNotNull();
        String invoice = booked.get("orderId").asText();
        assertThat(orderStatus(owner, invoice)).isEqualTo("OPEN");
        return new Tour(owner, staff(owner, "MANAGER"), staff(owner, "SALES"), booked.get("id").asText(), invoice);
    }

    @Test
    void aSalespersonCancellingATripBookingAsksAboutTheBill() {
        Tour t = tour();
        Res r = post("/trips/bookings/" + t.booking() + "/cancel", t.sales(), Map.of());
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/status").asText()).isEqualTo("CANCELLED");
        assertThat(r.at("/message").asText()).contains("manager has been asked");
        assertThat(orderStatus(t.owner(), t.invoice())).isEqualTo("OPEN");
        assertThat(waiting(t.manager())).isEqualTo(1);
    }

    @Test
    void allowingTheRequestThenCancelsThatBill() {
        Tour t = tour();
        post("/trips/bookings/" + t.booking() + "/cancel", t.sales(), Map.of());
        JsonNode w = get("/approvals/waiting", t.manager()).at("/requests");
        assertThat(w.size()).isEqualTo(1);
        Res a = post("/approvals/" + w.get(0).get("id").asText() + "/allow", t.manager(), Map.of());
        assertThat(a.status()).as(a.body().toString()).isEqualTo(200);
        assertThat(orderStatus(t.owner(), t.invoice())).isEqualTo("VOID");
        assertThat(waiting(t.manager())).isZero();
    }

    /** The trip invoice is worth real money: these tests are not about an empty bill. */
    @Test
    void theBillUnderTheAskIsWorthMoney() {
        Tour t = tour();
        assertThat(get("/orders/" + t.invoice(), t.owner()).at("/total").decimalValue().signum()).isPositive();
    }

    @Test
    void whileAManagerIsBeingAskedTheBillCannotBeEmptiedOrDeleted() {
        Tour t = tour();
        post("/trips/bookings/" + t.booking() + "/cancel", t.sales(), Map.of());
        JsonNode bill = get("/orders/" + t.invoice(), t.owner()).body();
        String lineId = bill.at("/lines/0/id").asText();
        assertThat(lineId).isNotBlank();
        // Taking the line off would leave nothing for the manager to answer about.
        Res off = call("PATCH", "/orders/" + t.invoice() + "/lines/" + lineId, t.sales(), Map.of("qty", 0));
        assertThat(off.status()).as(off.body().toString()).isEqualTo(409);
        assertThat(off.at("/message").asText()).contains("manager is being asked");
        // Nor can the bill be deleted out from under the ask.
        assertThat(call("DELETE", "/orders/" + t.invoice(), t.sales(), Map.of()).status()).isEqualTo(409);
        assertThat(orderStatus(t.owner(), t.invoice())).isEqualTo("OPEN");
        assertThat(waiting(t.manager())).isEqualTo(1);
        // A manager is not held up by it.
        assertThat(call("PATCH", "/orders/" + t.invoice() + "/lines/" + lineId, t.manager(), Map.of("qty", 1)).status()).isEqualTo(200);
    }

    @Test
    void askingAgainAfterANoSaysSo() {
        Tour t = tour();
        post("/trips/bookings/" + t.booking() + "/cancel", t.sales(), Map.of());
        String first = get("/approvals/waiting", t.manager()).at("/requests/0/id").asText();
        assertThat(post("/approvals/" + first + "/refuse", t.manager(), Map.of("answer", "The family is still coming")).status()).isEqualTo(200);
        assertThat(orderStatus(t.owner(), t.invoice())).isEqualTo("OPEN");
        // They ask a second time: the manager sees that this is a second go.
        post("/trips/bookings/" + t.booking() + "/cancel", t.sales(), Map.of());
        assertThat(get("/approvals/waiting", t.manager()).at("/requests/0/message").asText()).contains("Asked again after a no");
    }

    @Test
    void aReservationWithMoneyOnItSaysTheMoneyIsStillThere() {
        Hotel h = hotel();
        // A deposit only sits on a bill that is worth something, so the room night goes on first.
        Res charged = post("/orders/" + h.folio() + "/lines", h.owner(),
                Map.of("lines", java.util.List.of(Map.of("name", "Room deposit", "unitPrice", 5000, "qty", 1, "taxRate", 0))));
        assertThat(charged.status()).as(charged.body().toString()).isEqualTo(200);
        Res paid = post("/orders/" + h.folio() + "/pay", h.owner(),
                Map.of("payments", java.util.List.of(Map.of("method", "CASH", "amount", 1000))));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        assertThat(orderStatus(h.owner(), h.folio())).isEqualTo("OPEN");
        Res r = post("/stays/" + h.stay() + "/cancel", h.reception(), Map.of());
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/message").asText()).contains("still on the bill");
        // Nobody is asked: giving the guest's money back is a refund, which is a separate decision.
        assertThat(waiting(h.manager())).isZero();
    }

    @Test
    void theAskedAboutBillCannotBeEmptiedBySplittingItEither() {
        Tour t = tour();
        post("/trips/bookings/" + t.booking() + "/cancel", t.sales(), Map.of());
        JsonNode bill = get("/orders/" + t.invoice(), t.owner()).body();
        String lineId = bill.at("/lines/0/id").asText();
        Res split = post("/orders/" + t.invoice() + "/split", t.sales(), Map.of("lineIds", java.util.List.of(lineId)));
        assertThat(split.status()).as(split.body().toString()).isEqualTo(409);
        assertThat(split.at("/message").asText()).contains("manager is being asked");
        assertThat(get("/orders/" + t.invoice(), t.owner()).at("/total").decimalValue().signum()).isPositive();
    }

    @Test
    void askingDoesNotFreezeTheBillForEveryoneElse() {
        Tour t = tour();
        post("/trips/bookings/" + t.booking() + "/cancel", t.sales(), Map.of());
        JsonNode bill = get("/orders/" + t.invoice(), t.owner()).body();
        String lineId = bill.at("/lines/0/id").asText();
        java.math.BigDecimal was = bill.at("/lines/0/qty").decimalValue();
        // Putting more on the line is still fine: only taking value off waits for the manager.
        Res more = call("PATCH", "/orders/" + t.invoice() + "/lines/" + lineId, t.sales(), Map.of("qty", was.add(java.math.BigDecimal.ONE)));
        assertThat(more.status()).as(more.body().toString()).isEqualTo(200);
        assertThat(get("/orders/" + t.invoice(), t.owner()).at("/lines/0/qty").decimalValue()).isEqualByComparingTo(was.add(java.math.BigDecimal.ONE));
        // Taking value off does wait.
        assertThat(call("PATCH", "/orders/" + t.invoice() + "/lines/" + lineId, t.sales(), Map.of("qty", 0)).status()).isEqualTo(409);
    }
}
