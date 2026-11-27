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

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        Res accepted = post("/rounds/" + id + "/accept", driver.token(), Map.of("loadedAt", saved.at("/loadedAt").asText()));
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
        assertThat(post("/rounds/" + runId + "/accept", joe.token(), Map.of("loadedAt", "2030-01-01T00:00:00Z")).status()).isEqualTo(400);
        JsonNode loadLine = run.at("/load/0");
        Res saved = post("/rounds/" + runId + "/load", storekeeper, Map.of("lines", List.of(Map.of("itemId", loadLine.get("itemId").asText(), "qtyLoaded", 24))));
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        Map<String, Object> seen = Map.of("loadedAt", saved.at("/loadedAt").asText());
        assertThat(post("/rounds/" + runId + "/accept", other.token(), seen).status()).isEqualTo(403);
        assertThat(post("/rounds/" + runId + "/accept", owner, seen).status()).isEqualTo(403);
        assertThat(get("/rounds/" + runId, other.token()).status()).isEqualTo(403);
        assertThat(get("/rounds/" + runId, joe.token()).status()).isEqualTo(200);
        assertThat(get("/rounds/mine", other.token()).body()).isEmpty();
        Res accepted = post("/rounds/" + runId + "/accept", joe.token(), seen);
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
        // A paused business's link stops working, and works again once it is opened.
        String was = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?::uuid", String.class, s.at("/tenant/id").asText());
        jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?::uuid", s.at("/tenant/id").asText());
        assertThat(get("/public/delivery/" + token, null).status()).isEqualTo(404);
        assertThat(post("/public/delivery/" + token, null, Map.of("receivedByName", "Clerk")).status()).isEqualTo(404);
        jdbc.update("UPDATE tenants SET status = ? WHERE id = ?::uuid", was, s.at("/tenant/id").asText());
        assertThat(get("/public/delivery/" + token, null).status()).isEqualTo(200);
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
        for (int i = 0; i < 3; i++) {
            assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of("email", "driver-picked@evil.test")).at("/emailed").asBoolean()).isTrue();
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

    @Test
    void onlyTheOfficeChoosesTheEmailAddressAndTheDriverCannot() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        String noEmail = post("/customers", owner, Map.of("name", "No Mail Ltd")).at("/id").asText();
        JsonNode order = order(owner, noEmail, item(owner, ""), 3);
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, order));
        String stop = run.at("/stops/0/id").asText();
        var box = new EmailSender.LogSender();
        itMailer.useSender(box);

        Res none = post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of("email", "driver-picked@evil.test"));
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.at("/message").asText()).isEqualTo("This client has no email yet. Ask the office to add one.");
        assertThat(box.sent).isEmpty();

        assertThat(post("/rounds/stops/" + stop + "/email", joe.token(), Map.of("email", "clerk@nomail.test")).status()).isEqualTo(403);
        assertThat(post("/rounds/stops/" + stop + "/email", storekeeper, Map.of("email", "clerk@nomail.test")).status()).isEqualTo(403);
        Res staffMail = post("/rounds/stops/" + stop + "/email", owner, Map.of("email", s.get("email").asText()));
        assertThat(staffMail.status()).isEqualTo(400);
        assertThat(staffMail.at("/message").asText()).isEqualTo("That address belongs to one of your staff");

        Res set = post("/rounds/stops/" + stop + "/email", owner, Map.of("email", "clerk@nomail.test"));
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        assertThat(set.at("/email").asText()).isEqualTo("clerk@nomail.test");
        assertThat(set.at("/emailSetBy").asText()).isEqualTo("Owner");
        assertThat(concern(owner, "EMAIL_CHANGED")).isNotNull();

        assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of("email", "driver-picked@evil.test")).at("/emailed").asBoolean()).isTrue();
        assertThat(box.sent.get(0).to()).isEqualTo("clerk@nomail.test");
        String firstToken = tokenOf(box);
        // A new address cancels the link and code already sent.
        assertThat(post("/rounds/stops/" + stop + "/email", owner, Map.of("email", "other@nomail.test")).status()).isEqualTo(200);
        assertThat(get("/public/delivery/" + firstToken, null).status()).isEqualTo(404);
    }

    @Test
    void twoConfirmationsAtTheSameMomentLetOnlyOneThrough() throws Exception {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        JsonNode order = order(owner, customer(owner), item(owner, ""), 6);
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, order));
        String stop = run.at("/stops/0/id").asText();
        var box = new EmailSender.LogSender();
        itMailer.useSender(box);
        post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of());
        String token = tokenOf(box);
        String lineId = get("/public/delivery/" + token, null).at("/lines/0/lineId").asText();
        Map<String, Object> body = Map.of("receivedByName", "Mary", "signature", PNG, "agreed", true,
                "lines", List.of(Map.of("lineId", lineId, "qtyReceived", 6, "qtySentBack", 0)));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> post("/public/delivery/" + token, null, body).status()));
        }
        int ok = 0;
        int conflict = 0;
        for (var f : results) {
            int st = f.get();
            ok += st == 200 ? 1 : 0;
            conflict += st == 409 ? 1 : 0;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(3);
        // A resend after the confirmation cannot bring it back to waiting.
        assertThat(post("/rounds/stops/" + stop + "/arrived", joe.token(), Map.of()).status()).isEqualTo(409);
        assertThat(get("/rounds/" + run.get("id").asText(), owner).at("/stops/0/status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void theDriverMustAcceptTheLoadHeSawNotOneTheStoreChangedAfterwards() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        JsonNode run = plan(owner, joe, order(owner, customer(owner), item(owner, ""), 8));
        String runId = run.get("id").asText();
        String itemId = run.at("/load/0/itemId").asText();
        Res first = post("/rounds/" + runId + "/load", storekeeper, Map.of("lines", List.of(Map.of("itemId", itemId, "qtyLoaded", 8))));
        String seen = first.at("/loadedAt").asText();
        Res second = post("/rounds/" + runId + "/load", storekeeper, Map.of("lines", List.of(Map.of("itemId", itemId, "qtyLoaded", 7))));
        assertThat(second.status()).isEqualTo(200);
        assertThat(post("/rounds/" + runId + "/accept", joe.token(), Map.of()).status()).isEqualTo(400);
        Res stale = post("/rounds/" + runId + "/accept", joe.token(), Map.of("loadedAt", seen));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.at("/message").asText()).isEqualTo("The store changed the load. Check it again before you accept.");
        assertThat(post("/rounds/" + runId + "/accept", joe.token(), Map.of("loadedAt", second.at("/loadedAt").asText())).status()).isEqualTo(200);
    }

    @Test
    void aDriverCannotCloseARoundWithUnconfirmedStopsButTheOfficeCanAndTheGoodsCountAsMissing() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        String cust = customer(owner);
        JsonNode item = item(owner, "");
        JsonNode one = order(owner, cust, item, 5);
        JsonNode two = order(owner, cust, item, 4);
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, one, two));
        String runId = run.get("id").asText();
        var box = new EmailSender.LogSender();
        itMailer.useSender(box);
        post("/rounds/stops/" + run.at("/stops/1/id").asText() + "/arrived", joe.token(), Map.of());
        String token = tokenOf(box);

        Res refused = post("/rounds/" + runId + "/back", joe.token(), Map.of());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.at("/message").asText()).isEqualTo("Stops 1 and 2 are not confirmed yet. Ask the office to close the round.");

        Res closed = post("/rounds/" + runId + "/back", owner, Map.of());
        assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
        assertThat(closed.at("/status").asText()).isEqualTo("BACK");
        assertThat(closed.at("/stops/0/status").asText()).isEqualTo("MISSED");
        assertThat(closed.at("/stops/1/status").asText()).isEqualTo("MISSED");
        assertThat(closed.at("/totals/0/missing").asDouble()).isEqualTo(9);
        assertThat(concern(owner, "DELIVERY_MISSED")).isNotNull();
        assertThat(get("/public/delivery/" + token, null).status()).isEqualTo(404);
    }

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}")
    String dbUrl;

    /** Straight to the test database as its owner (no row fences), for setting up a state the app has no button for. */
    private void asDbOwner(String sql, Object... args) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(dbUrl, "postgres", "");
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    @Test
    void aClientWhoAlreadyPaidMoreThanTheConfirmedGoodsIsFlaggedAndNothingIsChangedAutomatically() throws Exception {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        JsonNode order = order(owner, customer(owner), item(owner, ""), 10);
        asDbOwner("UPDATE orders SET paid = total WHERE id = ?::uuid", order.get("id").asText());
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, order));
        var box = new EmailSender.LogSender();
        itMailer.useSender(box);
        post("/rounds/stops/" + run.at("/stops/0/id").asText() + "/arrived", joe.token(), Map.of());
        String token = tokenOf(box);
        String lineId = get("/public/delivery/" + token, null).at("/lines/0/lineId").asText();
        assertThat(post("/public/delivery/" + token, null, Map.of("receivedByName", "Mary", "signature", PNG, "agreed", true,
                "lines", List.of(Map.of("lineId", lineId, "qtyReceived", 8, "qtySentBack", 0)))).status()).isEqualTo(200);
        JsonNode row = concern(owner, "DELIVERY_OVERPAID");
        assertThat(row).isNotNull();
        assertThat(row.get("severity").asText()).isEqualTo("CONCERN");
        assertThat(row.get("amount").asDouble()).isCloseTo(order.get("total").asDouble() / 5, org.assertj.core.data.Offset.offset(0.05));
        assertThat(row.get("summary").asText()).contains("Give back or credit");
        JsonNode now = get("/orders/" + order.get("id").asText(), owner).body();
        assertThat(now.get("paid").asDouble()).isEqualTo(order.get("total").asDouble());
    }

    @Test
    void aRoundThatNeverWentOutIsCancelledPlainlyAndItsOrdersAreFreeAgain() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        Driver joe = rider(owner, "Joe Driver");
        JsonNode order = order(owner, customer(owner), item(owner, ""), 3);
        JsonNode run = plan(owner, joe, order);
        Res closed = post("/rounds/" + run.get("id").asText() + "/back", owner, Map.of());
        assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
        assertThat(closed.at("/status").asText()).isEqualTo("BACK");
        assertThat(closed.at("/stops/0/status").asText()).isEqualTo("CANCELLED");
        assertThat(concern(owner, "ROUND_CANCELLED").get("severity").asText()).isEqualTo("INFO");
        assertThat(concern(owner, "DELIVERY_MISSED")).isNull();
        assertThat(get("/rounds/orders-ready", owner).at("/0/orderId").asText()).isEqualTo(order.get("id").asText());
    }

    @Test
    void aCustomerEmailThatIsAStaffAddressInDisguiseIsNotCopiedToTheStop() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        Driver joe = rider(owner, "Joe Driver");
        String disguised = s.get("email").asText().toUpperCase().replace("@", "+deliveries@");
        String cust = post("/customers", owner, Map.of("name", "Sneaky Ltd", "email", disguised)).at("/id").asText();
        JsonNode run = plan(owner, joe, order(owner, cust, item(owner, ""), 2));
        assertThat(run.at("/stops/0/email").isNull()).isTrue();
        assertThat(concern(owner, "EMAIL_CHANGED")).isNotNull();
    }

    /** An unpaid account order: an open order put on the customer's account (status CREDIT). */
    private JsonNode onAccount(String owner, String customerId, JsonNode item, int qty) {
        JsonNode o = order(owner, customerId, item, qty);
        Res paid = post("/orders/" + o.get("id").asText() + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CREDIT", "amount", o.get("total").asDouble()))));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);
        assertThat(paid.at("/status").asText()).isEqualTo("CREDIT");
        return paid.body();
    }

    private double balanceOf(String owner, String customerId) {
        return get("/customers/" + customerId, owner).at("/balance").asDouble();
    }

    private String confirmShort(String owner, Driver joe, JsonNode run, int received) {
        var box = new EmailSender.LogSender();
        itMailer.useSender(box);
        post("/rounds/stops/" + run.at("/stops/0/id").asText() + "/arrived", joe.token(), Map.of());
        String token = tokenOf(box);
        String lineId = get("/public/delivery/" + token, null).at("/lines/0/lineId").asText();
        return String.valueOf(post("/public/delivery/" + token, null, Map.of("receivedByName", "Grace", "signature", PNG, "agreed", true,
                "lines", List.of(Map.of("lineId", lineId, "qtyReceived", received, "qtySentBack", 0)))).status());
    }

    @Test
    void anUnpaidAccountOrderGoesOnARoundAndAShortConfirmLowersBothTheBillAndTheAccount() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        post("/shifts/open", owner, Map.of("openingCash", 0));
        String cust = post("/customers", owner, Map.of("name", "Hotel Savanna", "email", "stores@savanna.test", "creditLimit", 10000000)).at("/id").asText();
        JsonNode order = onAccount(owner, cust, item(owner, ""), 10);
        double total = order.get("total").asDouble();
        assertThat(balanceOf(owner, cust)).isEqualTo(total);
        assertThat(get("/rounds/orders-ready", owner).at("/0/orderStatus").asText()).isEqualTo("CREDIT");

        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, order));
        assertThat(run.at("/stops/0/orderStatus").asText()).isEqualTo("CREDIT");
        assertThat(confirmShort(owner, joe, run, 8)).isEqualTo("200");

        double newTotal = get("/orders/" + order.get("id").asText(), owner).at("/total").asDouble();
        assertThat(newTotal).isLessThan(total);
        assertThat(balanceOf(owner, cust)).isCloseTo(newTotal, org.assertj.core.data.Offset.offset(0.005));
        JsonNode lines = get("/customers/" + cust + "/statement", owner).at("/lines");
        int refunds = 0;
        for (JsonNode l : lines) {
            if ("REFUND".equals(l.get("kind").asText())) {
                refunds++;
                assertThat(l.get("what").asText()).isEqualTo("Delivery DN-1 short, confirmed by Grace");
                assertThat(l.get("paid").asDouble()).isCloseTo(total - newTotal, org.assertj.core.data.Offset.offset(0.005));
            }
        }
        assertThat(refunds).isEqualTo(1);
        assertThat(concern(owner, "DELIVERY_SHORT")).isNotNull();
    }

    @Test
    void anAccountOrderThatWasPaidInTheMeantimeIsFlaggedAndNothingIsChanged() throws Exception {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        post("/shifts/open", owner, Map.of("openingCash", 0));
        String cust = post("/customers", owner, Map.of("name", "Hotel Savanna", "email", "stores@savanna.test", "creditLimit", 10000000)).at("/id").asText();
        JsonNode order = onAccount(owner, cust, item(owner, ""), 10);
        double total = order.get("total").asDouble();
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, order));
        // Money comes in against this bill after the round went out.
        asDbOwner("INSERT INTO payments (tenant_id, order_id, method, amount) SELECT tenant_id, id, 'CASH', 100 FROM orders WHERE id = ?::uuid", order.get("id").asText());
        assertThat(confirmShort(owner, joe, run, 8)).isEqualTo("200");
        assertThat(get("/orders/" + order.get("id").asText(), owner).at("/total").asDouble()).isEqualTo(total);
        assertThat(balanceOf(owner, cust)).isEqualTo(total);
        assertThat(concern(owner, "DELIVERY_NOT_BILLED_RIGHT")).isNotNull();
    }

    private JsonNode stocked(String owner) {
        for (JsonNode i : get("/items", owner).body()) {
            if (i.path("trackStock").asBoolean(false) && i.path("onHand").asDouble() >= 30) {
                return i;
            }
        }
        throw new IllegalStateException("no stocked item");
    }

    private double movedBy(String owner, JsonNode item, String reason) {
        double sum = 0;
        for (JsonNode m : get("/stock/moves?itemId=" + item.get("id").asText(), owner).body()) {
            if (reason.equals(m.get("reason").asText())) {
                sum += m.get("qty").asDouble();
            }
        }
        return sum;
    }

    private int confirmWith(Driver joe, JsonNode run, Map<String, Object> line) {
        var box = new EmailSender.LogSender();
        itMailer.useSender(box);
        post("/rounds/stops/" + run.at("/stops/0/id").asText() + "/arrived", joe.token(), Map.of());
        String token = tokenOf(box);
        String lineId = get("/public/delivery/" + token, null).at("/lines/0/lineId").asText();
        Map<String, Object> l = new java.util.HashMap<>(line);
        l.put("lineId", lineId);
        return post("/public/delivery/" + token, null, Map.of("receivedByName", "Grace", "signature", PNG, "agreed", true, "lines", List.of(l))).status();
    }

    @Test
    void goodsThatLeftAndWereNotSentBackAreGoneFromStockAndSentBackGoodsReturnForAnAccountOrder() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        post("/shifts/open", owner, Map.of("openingCash", 0));
        String cust = post("/customers", owner, Map.of("name", "Hotel Savanna", "email", "stores@savanna.test", "creditLimit", 10000000)).at("/id").asText();
        JsonNode item = stocked(owner);

        // An open order: 10 left, 7 received, 1 sent back, 2 missing. The sale will take off the 7; the 2 are taken off now.
        JsonNode open = order(owner, cust, item, 10);
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, open));
        assertThat(confirmWith(joe, run, Map.of("qtyReceived", 7, "qtySentBack", 1, "reason", "Wrong size"))).isEqualTo(200);
        assertThat(movedBy(owner, item, "DELIVERY_SHORT")).isEqualTo(-2);
        assertThat(movedBy(owner, item, "DELIVERY_SENT_BACK")).isZero();
        assertThat(get("/orders/" + open.get("id").asText(), owner).at("/lines/0/qty").asDouble()).isEqualTo(7);

        // An account order is already closed with all 10 taken off: the 3 sent back go in again, nothing else moves.
        JsonNode acct = onAccount(owner, cust, item, 10);
        JsonNode run2 = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, acct));
        assertThat(confirmWith(joe, run2, Map.of("qtyReceived", 6, "qtySentBack", 3, "reason", "Not ordered"))).isEqualTo(200);
        assertThat(movedBy(owner, item, "DELIVERY_SENT_BACK")).isEqualTo(3);
        assertThat(movedBy(owner, item, "DELIVERY_SHORT")).isEqualTo(-2);
    }

    @Test
    void anOrderAlreadyConfirmedCannotGoOnAnotherRound() {
        JsonNode s = signup("wholesale");
        String owner = token(s);
        String storekeeper = staff(owner, "STOREKEEPER");
        Driver joe = rider(owner, "Joe Driver");
        JsonNode order = order(owner, customer(owner), item(owner, ""), 4);
        JsonNode run = loadAndAccept(owner, storekeeper, joe, plan(owner, joe, order));
        assertThat(confirmWith(joe, run, Map.of("qtyReceived", 3, "qtySentBack", 0))).isEqualTo(200);
        assertThat(post("/rounds/" + run.get("id").asText() + "/back", owner, Map.of()).status()).isEqualTo(200);
        assertThat(get("/rounds/orders-ready", owner).body()).isEmpty();
        assertThat(post("/rounds", owner, Map.of("runDate", "2030-01-16", "driverUserId", joe.id(), "orderIds", List.of(order.get("id").asText()))).status()).isEqualTo(400);
    }
}
