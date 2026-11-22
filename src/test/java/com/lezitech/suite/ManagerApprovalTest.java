package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A junior member of staff asks a manager to cancel a bill; the bill stays as it is until a manager answers. */
class ManagerApprovalTest extends IntegrationTest {

    private JsonNode dishOf(String slug) {
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                return i;
            }
        }
        throw new AssertionError("no dish");
    }

    /** A restaurant with a manager, a waiter, and an open bill with one priced line. */
    private record Setup(String owner, String slug, String manager, String waiter, String bill) {
    }

    private Setup setUp() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String manager = staff(owner, "MANAGER");
        String waiter = staff(owner, "WAITER");
        String bill = post("/orders", owner, Map.of("lines", line(dishOf(slug).get("id").asText(), 1))).at("/id").asText();
        return new Setup(owner, slug, manager, waiter, bill);
    }

    private JsonNode bill(Setup x) {
        return get("/orders/" + x.bill(), x.owner()).body();
    }

    private String askedId(Setup x) {
        JsonNode waiting = get("/approvals/waiting", x.manager()).at("/requests");
        assertThat(waiting.size()).isEqualTo(1);
        return waiting.get(0).get("id").asText();
    }

    @Test
    void aWaiterAsksAndTheBillIsUntouched() {
        Setup x = setUp();
        Res r = post("/orders/" + x.bill() + "/ask-cancel", x.waiter(), Map.of("reason", "Guest left"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/asked").asBoolean()).isTrue();
        assertThat(bill(x).at("/status").asText()).isEqualTo("OPEN");
        assertThat(bill(x).at("/lines").size()).isEqualTo(1);
        assertThat(get("/approvals/waiting", x.manager()).at("/requests").size()).isEqualTo(1);
        // Asking twice does not pile up a second request.
        post("/orders/" + x.bill() + "/ask-cancel", x.waiter(), Map.of("reason", "Guest left"));
        assertThat(get("/approvals/waiting", x.manager()).at("/requests").size()).isEqualTo(1);
        // A reason is needed.
        assertThat(post("/orders/" + x.bill() + "/ask-cancel", x.waiter(), Map.of("reason", " ")).status()).isEqualTo(400);
    }

    @Test
    void theManagerSeesItAndAWaiterCannotOpenTheList() {
        Setup x = setUp();
        post("/orders/" + x.bill() + "/ask-cancel", x.waiter(), Map.of("reason", "Guest left"));
        JsonNode w = get("/approvals/waiting", x.manager()).body();
        assertThat(w.at("/requests/0/kind").asText()).isEqualTo("CANCEL_BILL");
        assertThat(w.at("/requests/0/orderNumber").isMissingNode()).isFalse();
        assertThat(w.at("/count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(get("/approvals/waiting", x.waiter()).status()).isEqualTo(403);
    }

    @Test
    void allowingCancelsTheBillAndNamesTheManager() {
        Setup x = setUp();
        post("/orders/" + x.bill() + "/ask-cancel", x.waiter(), Map.of("reason", "Guest left"));
        Res r = post("/approvals/" + askedId(x) + "/allow", x.manager(), Map.of());
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/outcome").asText()).isEqualTo("ALLOWED");
        assertThat(bill(x).at("/status").asText()).isEqualTo("VOID");
        JsonNode voids = get("/activity?kind=VOID", x.owner()).at("/entries");
        assertThat(voids.size()).isGreaterThan(0);
        assertThat(voids.get(0).get("approvedBy").asText()).isNotBlank();
        assertThat(get("/approvals/waiting", x.manager()).at("/requests").size()).isZero();
    }

    @Test
    void refusingKeepsTheBillAndTheWaiterReadsTheAnswer() {
        Setup x = setUp();
        post("/orders/" + x.bill() + "/ask-cancel", x.waiter(), Map.of("reason", "Guest left"));
        String id = askedId(x);
        Res r = post("/approvals/" + id + "/refuse", x.manager(), Map.of("answer", "They are only in the toilet", "takeOver", false));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(bill(x).at("/status").asText()).isEqualTo("OPEN");
        JsonNode mine = get("/approvals/mine", x.waiter()).at("/requests");
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("answer").asText()).isEqualTo("They are only in the toilet");
        assertThat(post("/approvals/" + id + "/seen", x.waiter(), Map.of()).status()).isEqualTo(200);
        assertThat(get("/approvals/mine", x.waiter()).at("/requests").size()).isZero();
    }

    @Test
    void refusingWithoutAWordIsRefusedAndASecondAnswerIsAConflict() {
        Setup x = setUp();
        post("/orders/" + x.bill() + "/ask-cancel", x.waiter(), Map.of("reason", "Guest left"));
        String id = askedId(x);
        assertThat(post("/approvals/" + id + "/refuse", x.manager(), Map.of("answer", " ")).status()).isEqualTo(400);
        assertThat(post("/approvals/" + id + "/refuse", x.manager(), Map.of("answer", "No")).status()).isEqualTo(200);
        assertThat(post("/approvals/" + id + "/allow", x.manager(), Map.of()).status()).isEqualTo(409);
    }

    /** An unpaid delivery taken by the rider; returns the delivery id. */
    private String unpaidDelivery(Setup x, String rider) {
        assertThat(post("/delivery/settings", x.owner(), Map.of("online", true, "payFirst", true, "payAtDoor", true)).status()).isEqualTo(200);
        String area = post("/delivery/areas", x.owner(), Map.of("name", "Kilimani", "fee", 150, "riderPay", 100, "minutes", 25)).at("/id").asText();
        Res placed = post("/public/orders/" + x.slug(), null, Map.of("lines", line(dishOf(x.slug()).get("id").asText(), 1),
                "delivery", Map.of("phone", "0722000111", "name", "Wanjiku", "areaId", area, "address", "Argwings Kodhek Rd",
                        "landmark", "Blue gate", "payAtDoor", true)));
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        int number = placed.at("/number").asInt();
        String deliveryId = null;
        for (JsonNode d : get("/delivery", x.owner()).at("/deliveries")) {
            if (d.get("number").asInt() == number) {
                deliveryId = d.get("id").asText();
            }
        }
        assertThat(deliveryId).isNotNull();
        assertThat(post("/delivery/" + deliveryId + "/rider", rider, Map.of()).status()).isEqualTo(200);
        return deliveryId;
    }

    @Test
    void aRiderWhoFailsADeliveryAsksInsteadOfCancelling() {
        Setup x = setUp();
        String rider = staff(x.owner(), "RIDER");
        String delivery = unpaidDelivery(x, rider);
        Res r = post("/delivery/" + delivery + "/failed", rider, Map.of("reason", "No answer at the door"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/stage").asText()).isEqualTo("FAILED");
        assertThat(r.at("/orderStatus").asText()).isEqualTo("OPEN");
        JsonNode waiting = get("/approvals/waiting", x.manager()).at("/requests");
        assertThat(waiting.size()).isEqualTo(1);
        assertThat(waiting.get(0).get("message").asText()).contains("deliver bill #");
    }

    @Test
    void whenTheOwnerLetsRidersCancelTheFailedDeliveryCancelsTheBill() {
        Setup x = setUp();
        Res set = call("PUT", "/settings", x.owner(), Map.of("settings", Map.of("access", Map.of("RIDER", Map.of("cancels", true)))));
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        String rider = staff(x.owner(), "RIDER");
        String delivery = unpaidDelivery(x, rider);
        Res r = post("/delivery/" + delivery + "/failed", rider, Map.of("reason", "No answer at the door"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/stage").asText()).isEqualTo("FAILED");
        assertThat(r.at("/orderStatus").asText()).isEqualTo("VOID");
        assertThat(get("/approvals/waiting", x.manager()).at("/requests").size()).isZero();
    }

    @Test
    void theSwitchWorksAtTheTillToo() {
        Setup x = setUp();
        String cashier = staff(x.owner(), "CASHIER");
        // By default a cashier is sent to ask a manager: cancelling needs a manager's PIN.
        assertThat(post("/orders/" + x.bill() + "/void", cashier, Map.of("reason", "Guest left")).status()).isEqualTo(403);
        // The owner gives the cashier's job "cancel a bill without asking a manager".
        Res set = call("PUT", "/settings", x.owner(), Map.of("settings", Map.of("access", Map.of("CASHIER", Map.of("cancels", true)))));
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        Res r = post("/orders/" + x.bill() + "/void", cashier, Map.of("reason", "Guest left"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(bill(x).at("/status").asText()).isEqualTo("VOID");
    }

    @Test
    void moneyPaidSinceTheAskStopsTheCancellation() {
        Setup x = setUp();
        post("/orders/" + x.bill() + "/ask-cancel", x.waiter(), Map.of("reason", "Guest left"));
        String asked = askedId(x);
        // The waiter then takes money on the bill after all.
        Res pay = post("/orders/" + x.bill() + "/pay", x.owner(), Map.of("payments", java.util.List.of(Map.of("method", "CASH", "amount", 1))));
        assertThat(pay.status()).as(pay.body().toString()).isEqualTo(200);
        Res allow = post("/approvals/" + asked + "/allow", x.manager(), Map.of());
        assertThat(allow.status()).as(allow.body().toString()).isEqualTo(409);
        assertThat(allow.at("/message").asText()).contains("paid on this bill since they asked");
        assertThat(bill(x).at("/status").asText()).isEqualTo("OPEN");
    }
}
