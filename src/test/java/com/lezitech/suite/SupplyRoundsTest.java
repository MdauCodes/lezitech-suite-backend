package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supply rounds, phase 1: the store loads, the driver accepts on his own phone, the client's clerk confirms by the emailed
 * link (with a signature) or by a 4 digit code, and the invoice follows what was confirmed.
 */
class SupplyRoundsTest extends IntegrationTest {

    private static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
    private static final Pattern LINK = Pattern.compile("/confirm-delivery/([A-Za-z0-9_-]{43})");
    private static final Pattern CODE = Pattern.compile("driver: (\\d{4})");

    private record Driver(String id, String token) {
    }

    private Driver rider(String owner, String name) {
        String email = "rider-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res add = post("/team", owner, Map.of("name", name, "email", email, "password", "password123", "role", "RIDER", "pin", "1234"));
        assertThat(add.status()).as(add.body().toString()).isEqualTo(200);
        String token = post("/auth/login", null, Map.of("email", email, "password", "password123")).body().get("token").asText();
        for (JsonNode u : get("/team", owner).body()) {
            if (email.equals(u.path("email").asText())) {
                return new Driver(u.get("id").asText(), token);
            }
        }
        throw new AssertionError("rider not listed");
    }

    private String customer(String owner) {
        Res c = post("/customers", owner, Map.of("name", "Hotel Savanna", "email", "stores@savanna.test"));
        assertThat(c.status()).as(c.body().toString()).isEqualTo(200);
        return c.at("/id").asText();
    }

    /** An open order for a customer: 24 of one item. Returns the order. */
    private JsonNode order(String owner, String customerId, JsonNode item, int qty) {
        Res o = post("/orders", owner, Map.of("customerId", customerId, "lines", line(item.get("id").asText(), qty)));
        assertThat(o.status()).as(o.body().toString()).isEqualTo(200);
        assertThat(o.at("/status").asText()).isEqualTo("OPEN");
        return o.body();
    }

    private JsonNode plan(String owner, Driver driver, JsonNode... orders) {
        Res r = post("/rounds", owner, Map.of("runDate", "2030-01-15", "vehicle", "KDA 123A", "driverUserId", driver.id(),
                "orderIds", java.util.Arrays.stream(orders).map(o -> o.get("id").asText()).toList()));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    private JsonNode loadAndAccept(String owner, String storekeeper, Driver driver, JsonNode run) {
        String id = run.get("id").asText();
        List<Map<String, Object>> load = new java.util.ArrayList<>();
        for (JsonNode l : run.get("load")) {
            load.add(Map.of("itemId", l.get("itemId").asText(), "qtyLoaded", l.get("qtyPlanned").asDouble()));
        }
        Res saved = post("/rounds/" + id + "/load", storekeeper, Map.of("lines", load));
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        assertThat(saved.at("/loadedByName").asText()).isEqualTo("STOREKEEPER person");
        Res accepted = post("/rounds/" + id + "/accept", driver.token(), Map.of());
        assertThat(accepted.status()).as(accepted.body().toString()).isEqualTo(200);
        return accepted.body();
    }

    private static String tokenOf(EmailSender.LogSender box) {
        Matcher m = LINK.matcher(box.sent.get(box.sent.size() - 1).text());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static String codeOf(EmailSender.LogSender box) {
        Matcher m = CODE.matcher(box.sent.get(box.sent.size() - 1).text());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private JsonNode concern(String owner, String kind) {
        for (JsonNode e : get("/activity", owner).at("/entries")) {
            if (kind.equals(e.get("kind").asText())) {
                return e;
            }
        }
        return null;
    }

    @Test
    void storeLoadsDriverAcceptsClerkConfirmsShortByLink() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        String cashier = staff(owner, "CASHIER");
        Driver joe = rider(owner, "Joe Driver");
        Driver other = rider(owner, "Other Driver");
        JsonNode item = item(owner, "");
        JsonNode order = order(owner, customer(owner), item, 24);
        double before = order.get("total").asDouble();

        assertThat(get("/rounds/orders-ready", owner).at("/0/orderId").asText()).isEqualTo(order.get("id").asText());
        assertThat(get("/rounds/orders-ready", cashier).status()).isEqualTo(403);
        assertThat(post("/rounds", joe.token(), Map.of("runDate", "2030-01-15", "driverUserId", joe.id(), "orderIds", List.of(order.get("id").asText()))).status()).isEqualTo(403);
        assertThat(post("/rounds", owner, Map.of("runDate", "2030-01-15", "driverUserId", UUID.randomUUID().toString(), "orderIds", List.of(order.get("id").asText()))).status()).isEqualTo(400);

        JsonNode run = plan(owner, joe, order);
        String runId = run.get("id").asText();
        assertThat(run.at("/status").asText()).isEqualTo("LOADING");
        assertThat(run.at("/stops/0/noteNo").asText()).isEqualTo("DN-1");
        assertThat(run.at("/load/0/qtyPlanned").asDouble()).isEqualTo(24);
        assertThat(get("/rounds/orders-ready", owner).body()).isEmpty();
        assertThat(post("/rounds", owner, Map.of("runDate", "2030-01-15", "driverUserId", joe.id(), "orderIds", List.of(order.get("id").asText()))).status()).isEqualTo(400);

        // Name 1: the store loads. Name 2: only the driver accepts, and only once the load is saved.
        assertThat(post("/rounds/" + runId + "/accept", joe.token(), Map.of()).status()).isEqualTo(400);
        JsonNode loadLine = run.at("/load/0");
        Res saved = post("/rounds/" + runId + "/load", storekeeper, Map.of("lines", List.of(Map.of("itemId", loadLine.get("itemId").asText(), "qtyLoaded", 24))));
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        assertThat(post("/rounds/" + runId + "/accept", other.token(), Map.of()).status()).isEqualTo(403);
        assertThat(post("/rounds/" + runId + "/accept", owner, Map.of()).status()).isEqualTo(403);
        assertThat(get("/rounds/" + runId, other.token()).status()).isEqualTo(403);
        assertThat(get("/rounds/" + runId, joe.token()).status()).isEqualTo(200);
        assertThat(get("/rounds/mine", other.token()).body()).isEmpty();
        Res accepted = post("/rounds/" + runId + "/accept", joe.token(), Map.of());
        assertThat(accepted.status()).as(accepted.body().toString()).isEqualTo(200);
        assertThat(accepted.at("/status").asText()).isEqualTo("OUT");
        assertThat(accepted.at("/acceptedByName").asText()).isEqualTo("Joe Driver");
        assertThat(post("/rounds/" + runId + "/load", storekeeper, Map.of("lines", List.of(Map.of("itemId", loadLine.get("itemId").asText(), "qtyLoaded", 1)))).status()).isEqualTo(409);
        assertThat(concern(owner, "LOAD_ACCEPTED")).isNotNull();
        assertThat(get("/rounds/mine", joe.token()).at("/0/id").asText()).isEqualTo(runId);
        assertThat(get("/rounds?date=2030-01-15", storekeeper).at("/0/stopCount").asInt()).isEqualTo(1);

        // Arrived: the clerk gets a link and a code. Nobody else can ring it.
        var box = new EmailSender.LogSender();
        itMailer.useSender(box);
        String stop = accepted.at("/stops/0/id").asText();
        assertThat(post("/rounds/stops/" + stop + "/arrived", other.token(), Map.of()).status()).isEqualTo(403);
        Res sent = post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of());
        assertThat(sent.status()).as(sent.body().toString()).isEqualTo(200);
        assertThat(sent.at("/emailed").asBoolean()).isTrue();
        assertThat(box.sent).hasSize(1);
        assertThat(box.sent.get(0).to()).isEqualTo("stores@savanna.test");
        assertThat(box.sent.get(0).subject()).startsWith("Confirm your delivery DN-1 from ");
        assertThat(box.sent.get(0).text()).contains("Read this code to the driver");
        String token = tokenOf(box);

        // The clerk's page, no login.
        assertThat(get("/public/delivery/" + "Xx".repeat(21) + "x", null).status()).isEqualTo(404);
        Res page = get("/public/delivery/" + token, null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.at("/noteNo").asText()).isEqualTo("DN-1");
        assertThat(page.at("/driverName").asText()).isEqualTo("Joe Driver");
        assertThat(page.at("/confirmed").isNull()).isTrue();
        String lineId = page.at("/lines/0/lineId").asText();

        // A line cannot come to more than was sent, and every line must be answered.
        assertThat(post("/public/delivery/" + token, null, Map.of("receivedByName", "Mary", "signature", PNG, "agreed", true,
                "lines", List.of(Map.of("lineId", lineId, "qtyReceived", 25, "qtySentBack", 0)))).status()).isEqualTo(400);
        assertThat(post("/public/delivery/" + token, null, Map.of("receivedByName", "Mary", "signature", PNG, "agreed", true,
                "lines", List.of(Map.of("lineId", lineId, "qtyReceived", 20, "qtySentBack", 2)))).status()).as("a reason is needed").isEqualTo(400);
        assertThat(post("/public/delivery/" + token, null, Map.of("receivedByName", "Mary", "signature", "nope", "agreed", true,
                "lines", List.of(Map.of("lineId", lineId, "qtyReceived", 20, "qtySentBack", 0)))).status()).isEqualTo(400);

        // 22 of 24 came. The order follows.
        Res done = post("/public/delivery/" + token, null, Map.of("receivedByName", "Mary Clerk", "signature", PNG, "agreed", true,
                "lines", List.of(Map.of("lineId", lineId, "qtyReceived", 22, "qtySentBack", 0))));
        assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
        JsonNode now = get("/orders/" + order.get("id").asText(), owner).body();
        assertThat(now.get("total").asDouble()).isLessThan(before);
        assertThat(now.get("total").asDouble()).isCloseTo(before / 24 * 22, org.assertj.core.data.Offset.offset(0.05));
        JsonNode short_ = concern(owner, "DELIVERY_SHORT");
        assertThat(short_).isNotNull();
        assertThat(short_.get("severity").asText()).isEqualTo("CONCERN");
        assertThat(short_.get("summary").asText()).contains("Joe Driver").contains("Mary Clerk").contains("DN-1");
        assertThat(short_.get("userName").asText()).contains("Mary Clerk");

        // Once only, and the driver cannot change it.
        assertThat(post("/public/delivery/" + token, null, Map.of("receivedByName", "Mary Clerk", "signature", PNG, "agreed", true,
                "lines", List.of(Map.of("lineId", lineId, "qtyReceived", 24, "qtySentBack", 0)))).status()).isEqualTo(409);
        assertThat(get("/orders/" + order.get("id").asText(), owner).at("/total").asDouble()).isCloseTo(before / 24 * 22, org.assertj.core.data.Offset.offset(0.05));
        assertThat(post("/rounds/stops/" + stop + "/code", joe.token(), Map.of("code", "1234")).status()).isEqualTo(409);
        assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of()).status()).isEqualTo(409);
        assertThat(get("/public/delivery/" + token, null).at("/confirmed/receivedByName").asText()).isEqualTo("Mary Clerk");
        assertThat(get("/public/delivery/" + token, null).at("/confirmed/lines/0/qtyReceived").asDouble()).isEqualTo(22);

        Res cash = post("/rounds/stops/" + stop + "/cash", joe.token(), Map.of("cashCollected", 500));
        assertThat(cash.status()).as(cash.body().toString()).isEqualTo(200);
        assertThat(post("/rounds/stops/" + stop + "/cash", joe.token(), Map.of("cashCollected", 900)).status()).isEqualTo(409);
        JsonNode detail = get("/rounds/" + runId, owner).body();
        assertThat(detail.at("/stops/0/status").asText()).isEqualTo("PART");
        assertThat(detail.at("/stops/0/confirmedVia").asText()).isEqualTo("LINK");
        assertThat(detail.at("/totals/0/missing").asDouble()).isEqualTo(2);
        assertThat(get("/rounds?date=2030-01-15", owner).at("/0/confirmedCount").asInt()).isEqualTo(1);
        assertThat(post("/rounds/" + runId + "/back", joe.token(), Map.of()).at("/status").asText()).isEqualTo("BACK");
    }

    @Test
    void sentBackLeavesItsOwnMarkAndAWholeLineCanGoToZero() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        JsonNode item = item(owner, "");
        JsonNode order = order(owner, customer(owner), item, 10);
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, order));
        var box = new EmailSender.LogSender();
        itMailer.useSender(box);
        String stop = run.at("/stops/0/id").asText();
        assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of()).at("/emailed").asBoolean()).isTrue();
        String token = tokenOf(box);
        String lineId = get("/public/delivery/" + token, null).at("/lines/0/lineId").asText();
        assertThat(post("/public/delivery/" + token, null, Map.of("receivedByName", "Mary", "signature", PNG, "agreed", true,
                "lines", List.of(Map.of("lineId", lineId, "qtyReceived", 0, "qtySentBack", 10, "reason", "Wrong brand")))).status()).isEqualTo(200);
        assertThat(concern(owner, "DELIVERY_SENT_BACK")).isNotNull();
        assertThat(concern(owner, "DELIVERY_SHORT")).as("sent back is not missing").isNull();
        assertThat(get("/orders/" + order.get("id").asText(), owner).at("/total").asDouble()).isZero();
        assertThat(concern(owner, "ITEM_REMOVED")).isNotNull();
    }

    @Test
    void theCodeConfirmsInFullFiveWrongTriesLockItAndAResendReplacesLinkAndCode() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        JsonNode item = item(owner, "");
        JsonNode order = order(owner, customer(owner), item, 5);
        double total = order.get("total").asDouble();
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, order));
        String stop = run.at("/stops/0/id").asText();
        var box = new EmailSender.LogSender();
        itMailer.useSender(box);

        assertThat(post("/rounds/stops/" + stop + "/code", joe.token(), Map.of("code", "1234")).status()).as("no email sent yet").isEqualTo(400);
        assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of()).at("/emailed").asBoolean()).isTrue();
        String firstToken = tokenOf(box);
        String code = codeOf(box);
        String wrong = code.equals("0000") ? "1111" : "0000";
        for (int i = 0; i < 5; i++) {
            Res bad = post("/rounds/stops/" + stop + "/code", joe.token(), Map.of("code", wrong));
            assertThat(bad.status()).isEqualTo(400);
            assertThat(bad.at("/message").asText()).doesNotContain("—").doesNotContain("–");
        }
        Res locked = post("/rounds/stops/" + stop + "/code", joe.token(), Map.of("code", code));
        assertThat(locked.status()).as("the right code is refused once locked").isEqualTo(400);
        assertThat(locked.at("/message").asText()).contains("Send the email again");

        // A resend gives a new link and code, and the old link stops working.
        assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of()).at("/emailed").asBoolean()).isTrue();
        assertThat(get("/public/delivery/" + firstToken, null).status()).isEqualTo(404);
        assertThat(get("/public/delivery/" + tokenOf(box), null).status()).isEqualTo(200);
        String fresh = codeOf(box);
        Res ok = post("/rounds/stops/" + stop + "/code", joe.token(), Map.of("code", fresh, "cashCollected", 1200));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(ok.at("/status").asText()).isEqualTo("CONFIRMED");
        assertThat(ok.at("/confirmedVia").asText()).isEqualTo("CODE");
        assertThat(ok.at("/cashCollected").asDouble()).isEqualTo(1200);
        assertThat(get("/orders/" + order.get("id").asText(), owner).at("/total").asDouble()).isEqualTo(total);
        assertThat(post("/rounds/stops/" + stop + "/code", joe.token(), Map.of("code", fresh)).status()).isEqualTo(409);
    }

    @Test
    void aResendIsLimitedAndAFailedEmailSaysSo() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        JsonNode order = order(owner, customer(owner), item(owner, ""), 3);
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, order));
        String stop = run.at("/stops/0/id").asText();

        itMailer.useSender(new EmailSender() {
            @Override
            public void send(Email email) {
                throw new RuntimeException("mail service is down");
            }

            @Override
            public String name() {
                return "broken";
            }
        });
        Res failed = post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of());
        assertThat(failed.status()).isEqualTo(200);
        assertThat(failed.at("/emailed").asBoolean()).isFalse();
        assertThat(failed.at("/message").asText()).contains("did not go out");

        itMailer.useSender(new EmailSender.LogSender());
        assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of("email", "not an email")).status()).isEqualTo(400);
        for (int i = 0; i < 3; i++) {
            assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of("email", "clerk@savanna.test")).at("/emailed").asBoolean()).isTrue();
        }
        assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of()).status()).isEqualTo(400);
    }

    @Test
    void anotherBusinessCannotSeeTheRound() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        Driver joe = rider(owner, "Joe Driver");
        JsonNode order = order(owner, customer(owner), item(owner, ""), 4);
        JsonNode run = plan(owner, joe, order);
        String runId = run.get("id").asText();

        String stranger = token(signup("wholesale"));
        assertThat(get("/rounds/" + runId, stranger).status()).isEqualTo(404);
        assertThat(get("/rounds?date=2030-01-15", stranger).body()).isEmpty();
        assertThat(post("/rounds/" + runId + "/load", stranger, Map.of("lines", List.of())).status()).isEqualTo(404);
        assertThat(post("/rounds/stops/" + run.at("/stops/0/id").asText() + "/arrived", stranger, Map.of()).status()).isIn(403, 404);
        assertThat(get("/rounds?date=2030-01-15", owner).at("/0/id").asText()).isEqualTo(runId);
    }
}
