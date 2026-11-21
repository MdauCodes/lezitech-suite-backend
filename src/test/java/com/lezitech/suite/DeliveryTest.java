package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5, delivery: areas with a fee and the rider's pay; orders from the menu link or by phone; the rider's run sheet;
 * cash at the door handed in to the till (the rider can keep their trip pay from it); M-Pesa first holds the order;
 * delivery-app orders recorded with the app's cut.
 */
class DeliveryTest extends IntegrationTest {

    private JsonNode dishOf(String slug) {
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                return i;
            }
        }
        throw new AssertionError("no dish");
    }

    private void cookAll(String owner) {
        for (JsonNode t : get("/kitchen", owner).body()) {
            post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", owner, Map.of());
            post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", owner, Map.of());
        }
    }

    private JsonNode deliveryOf(String owner, int number) {
        for (JsonNode d : get("/delivery", owner).at("/deliveries")) {
            if (d.get("number").asInt() == number) {
                return d;
            }
        }
        throw new AssertionError("no delivery #" + number);
    }

    private String setUp(String owner) {
        assertThat(post("/delivery/settings", owner, Map.of("online", true, "payFirst", true, "payAtDoor", true)).at("/online").asBoolean()).isTrue();
        Res area = post("/delivery/areas", owner, Map.of("name", "Kilimani", "fee", 150, "riderPay", 100, "minutes", 25));
        assertThat(area.status()).as(area.body().toString()).isEqualTo(200);
        assertThat(post("/delivery/areas", owner, Map.of("name", "kilimani", "fee", 1)).status()).isEqualTo(409);
        return area.at("/id").asText();
    }

    @Test
    void aRiderDeliversPaysAtTheDoorAndTheCashComesBackToTheTill() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String area = setUp(owner);
        String rider = staff(owner, "RIDER");
        String cashier = staff(owner, "CASHIER");
        JsonNode dish = dishOf(slug);
        double price = dish.get("price").asDouble();

        // The menu link shows the area and its fee; the customer orders, paying at the door.
        assertThat(get("/public/delivery/" + slug, null).at("/areas/0/fee").asDouble()).isEqualTo(150);
        Res placed = post("/public/orders/" + slug, null, Map.of("lines", List.of(Map.of("itemId", dish.get("id").asText(), "qty", 2)),
                "delivery", Map.of("phone", "0722000111", "name", "Wanjiku", "areaId", area, "address", "Argwings Kodhek Rd, Elgon Court",
                        "landmark", "Blue gate, house 4", "payAtDoor", true)));
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        assertThat(placed.at("/total").asDouble()).isEqualTo(price * 2 + 150);
        assertThat(placed.at("/heldForPayment").asBoolean()).isFalse();
        int number = placed.at("/number").asInt();
        double total = placed.at("/total").asDouble();

        // It rings on the floor team's screens until someone stops it (for everyone).
        assertThat(get("/delivery/alerts", cashier).at("/count").asInt()).isEqualTo(1);
        assertThat(get("/delivery/alerts", cashier).at("/kind").asText()).isEqualTo("DELIVERIES");
        post("/delivery/alerts/stop", cashier, Map.of());
        assertThat(get("/delivery/alerts", owner).at("/count").asInt()).isZero();
        assertThat(deliveryOf(owner, number).get("stage").asText()).isEqualTo("COOKING");
        cookAll(owner);
        JsonNode d = deliveryOf(owner, number);
        assertThat(d.get("stage").asText()).isEqualTo("READY");
        String id = d.get("id").asText();

        // The rider sees it as free on their run sheet, takes it, picks it up and delivers it; the customer pays cash.
        JsonNode mine = get("/delivery/mine", rider).body();
        assertThat(mine.at("/free").toString()).contains(id);
        assertThat(post("/delivery/" + id + "/rider", rider, Map.of()).at("/riderName").asText()).isNotBlank();
        assertThat(post("/delivery/" + id + "/picked", rider, Map.of()).at("/status").asText()).isEqualTo("ON_THE_WAY");
        assertThat(get("/public/track/" + slug + "/" + placed.at("/token").asText(), null).at("/order/delivery/status").asText()).isEqualTo("ON_THE_WAY");
        assertThat(post("/delivery/" + id + "/delivered", rider, Map.of()).status()).isEqualTo(400);
        assertThat(post("/delivery/" + id + "/delivered", rider, Map.of("how", "CASH")).at("/collected").asDouble()).isEqualTo(total);

        mine = get("/delivery/mine", rider).body();
        assertThat(mine.at("/cashWithMe").asDouble()).isEqualTo(total);
        assertThat(mine.at("/payNotPaid").asDouble()).isEqualTo(100);
        // A rider can't see the whole board or take in cash.
        assertThat(get("/delivery", rider).status()).isEqualTo(403);
        String riderId = mine.at("/id").asText();
        assertThat(post("/delivery/riders/" + riderId + "/hand-in", rider, Map.of("counted", total)).status()).isEqualTo(403);

        // The cashier takes the cash in; the rider keeps their KES 100 trip pay from it.
        assertThat(post("/delivery/riders/" + riderId + "/hand-in", cashier, Map.of("counted", total - 100, "keepPay", true)).status()).isEqualTo(400);
        post("/shifts/open", cashier, Map.of("openingCash", 0));
        Res in = post("/delivery/riders/" + riderId + "/hand-in", cashier, Map.of("counted", total - 100, "keepPay", true));
        assertThat(in.status()).as(in.body().toString()).isEqualTo(200);
        assertThat(in.at("/keptAsPay").asDouble()).isEqualTo(100);
        assertThat(in.at("/short").asDouble()).isZero();
        assertThat(deliveryOf(owner, number).get("orderStatus").asText()).isEqualTo("PAID");

        // The till expects the bill's cash less the rider's pay.
        String shift = get("/shifts/current", cashier).at("/id").asText();
        JsonNode till = get("/shifts/" + shift, owner).body();
        assertThat(till.at("/expectedCash").asDouble()).isEqualTo(total - 100);
        assertThat(till.at("/riderPayFromTill").asDouble()).isEqualTo(100);
        assertThat(get("/delivery/mine", rider).at("/cashWithMe").asDouble()).isZero();
    }

    @Test
    void payingFirstHoldsTheOrderAppOrdersKeepTheirCutAndAFailedDeliveryIsCancelled() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String area = setUp(owner);
        JsonNode dish = dishOf(slug);
        String dishId = dish.get("id").asText();
        double price = dish.get("price").asDouble();
        Map<String, Object> where = Map.of("phone", "0733000222", "name", "Kamau", "areaId", area, "address", "Kindaruma Rd, Block B");

        // Paying first: held until the M-Pesa payment comes in, and it can't leave before.
        Res held = post("/public/orders/" + slug, null, Map.of("lines", line(dishId, 1), "delivery", Map.of(
                "phone", "0733000222", "areaId", area, "address", "Kindaruma Rd, Block B", "payAtDoor", false)));
        assertThat(held.at("/heldForPayment").asBoolean()).isTrue();
        JsonNode h = deliveryOf(owner, held.at("/number").asInt());
        assertThat(h.get("stage").asText()).isEqualTo("WAITING_PAYMENT");
        String juma = post("/delivery/riders", owner, Map.of("name", "Juma (boda)", "phone", "0711222333")).at("/id").asText();
        post("/delivery/" + h.get("id").asText() + "/rider", owner, Map.of("riderId", juma));

        // A staff rider given a trip gets it ringing on their phone until they open it.
        String rider = staff(owner, "RIDER");
        String riderEntry = get("/delivery/mine", rider).at("/id").asText();
        post("/delivery/" + h.get("id").asText() + "/rider", owner, Map.of("riderId", riderEntry));
        assertThat(get("/delivery/alerts", rider).at("/kind").asText()).isEqualTo("TRIPS");
        assertThat(get("/delivery/alerts", rider).at("/count").asInt()).isEqualTo(1);
        post("/delivery/alerts/stop", rider, Map.of());
        assertThat(get("/delivery/alerts", rider).at("/count").asInt()).isZero();
        post("/delivery/" + h.get("id").asText() + "/rider", owner, Map.of("riderId", juma));

        // Phone notifications: the device signs up with the key Busynez made.
        assertThat(get("/push", rider).at("/publicKey").asText()).hasSizeGreaterThan(80);
        assertThat(post("/push/subscribe", rider, Map.of("endpoint", "https://push.example.invalid/abc", "keys",
                Map.of("p256dh", "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM", "auth", "tBHItJI5svbpez7KI4CCXg"))).at("/devices").asInt()).isEqualTo(1);
        assertThat(post("/push/subscribe", rider, Map.of("endpoint", "http://not-secure", "keys", Map.of("p256dh", "x", "auth", "y"))).status()).isEqualTo(400);
        assertThat(post("/delivery/" + h.get("id").asText() + "/picked", owner, Map.of()).status()).isEqualTo(400);

        // An unknown area is refused, and leaves no order behind.
        Res bad = post("/public/orders/" + slug, null, Map.of("lines", line(dishId, 1), "delivery", Map.of(
                "phone", "0733000222", "areaId", java.util.UUID.randomUUID().toString(), "address", "Somewhere far")));
        assertThat(bad.status()).isEqualTo(400);

        // A phone order: the address is saved for next time.
        Res phone = post("/delivery", owner, Map.of("lines", line(dishId, 1), "delivery", where));
        assertThat(phone.status()).as(phone.body().toString()).isEqualTo(200);
        assertThat(get("/delivery/addresses?phone=0733000222", owner).at("/0/address").asText()).isEqualTo("Kindaruma Rd, Block B");

        // The hired rider couldn't reach the customer: the unpaid order is cancelled.
        String pid = phone.at("/id").asText();
        post("/delivery/" + pid + "/rider", owner, Map.of("riderId", juma));
        assertThat(post("/delivery/" + pid + "/failed", owner, Map.of()).status()).isEqualTo(400);
        JsonNode failed = post("/delivery/" + pid + "/failed", owner, Map.of("reason", "Customer didn't pick up")).body();
        assertThat(failed.at("/stage").asText()).isEqualTo("FAILED");
        assertThat(failed.at("/orderStatus").asText()).isEqualTo("VOID");

        // A Glovo order: the kitchen gets it, the sale closes, and 30% is the app's.
        Res app = post("/delivery/app-orders", owner, Map.of("app", "GLOVO", "ref", "G-55821", "lines", line(dishId, 2)));
        assertThat(app.status()).as(app.body().toString()).isEqualTo(200);
        assertThat(app.at("/cut").asDouble()).isEqualTo(Math.round(price * 2 * 0.30 * 100) / 100.0);
        assertThat(post("/delivery/app-orders", owner, Map.of("app", "GLOVO", "lines", line(dishId, 1))).status()).isEqualTo(400);

        // A second phone order is delivered and paid by M-Pesa code at the door; Juma is paid from the till.
        String second = post("/delivery", owner, Map.of("lines", line(dishId, 1), "delivery", where)).at("/id").asText();
        post("/delivery/" + second + "/rider", owner, Map.of("riderId", juma));
        post("/delivery/" + second + "/picked", owner, Map.of());
        JsonNode paid = post("/delivery/" + second + "/delivered", owner, Map.of("how", "MPESA", "reference", "TK45AB12CD")).body();
        assertThat(paid.at("/orderStatus").asText()).isEqualTo("PAID");
        assertThat(paid.at("/needsPayCheck").asBoolean()).isTrue();
        post("/shifts/open", owner, Map.of("openingCash", 1000));
        assertThat(post("/delivery/riders/" + juma + "/pay", owner, Map.of("how", "TILL_CASH")).at("/paid").asDouble()).isEqualTo(100);
        assertThat(post("/delivery/riders/" + juma + "/pay", owner, Map.of("how", "TILL_CASH")).status()).isEqualTo(400);

        // The Nairobi starting areas: added once, the owner's own Kilimani left as it was.
        Res nairobi = post("/delivery/areas/nairobi", owner, Map.of());
        assertThat(nairobi.status()).as(nairobi.body().toString()).isEqualTo(200);
        assertThat(nairobi.at("/added").asInt()).isGreaterThan(100);
        assertThat(post("/delivery/areas/nairobi", owner, Map.of()).at("/added").asInt()).isZero();
        assertThat(get("/delivery/areas", owner).body().toString()).contains("Westlands");
        for (JsonNode a : get("/delivery/areas", owner).body()) {
            if ("Kilimani".equals(a.get("name").asText())) {
                assertThat(a.get("fee").asDouble()).isEqualTo(150);
            }
        }

        JsonNode report = get("/delivery/report", owner).body();
        assertThat(report.at("/own/delivered").asInt()).isEqualTo(1);
        assertThat(report.at("/own/failed").asInt()).isEqualTo(1);
        assertThat(report.at("/own/riderPay").asDouble()).isEqualTo(100);
        assertThat(report.at("/apps/0/app").asText()).isEqualTo("GLOVO");
        assertThat(report.at("/apps/0/kept").asDouble()).isEqualTo(price * 2 - app.at("/cut").asDouble());
    }
}
