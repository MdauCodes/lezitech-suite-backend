package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.publicapi.PublicController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Restaurants, package R1: paying the right bill. The table's Pay QR lists no bills. The phone that ordered opens its
 * own bill; anyone else types the 4-digit code on the bill. A table paid from a phone waits for the waiter's check.
 */
class RightBillTest extends IntegrationTest {

    @BeforeEach
    void freshLimits() {
        PublicController.PER_TABLE.clear();
        PublicController.PER_CLIENT.clear();
        PublicController.PER_ORDER_PAY.clear();
        PublicController.FIND_BILL.clear();
    }

    private String dish(String slug) {
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                return i.get("id").asText();
            }
        }
        throw new AssertionError("no dish");
    }

    private JsonNode table(String owner, String id) {
        for (JsonNode f : get("/floors", owner).body()) {
            for (JsonNode t : f.get("tables")) {
                if (id.equals(t.get("id").asText())) {
                    return t;
                }
            }
        }
        throw new AssertionError("no table");
    }

    private JsonNode waitTable(String owner, String id, Predicate<JsonNode> done) throws InterruptedException {
        JsonNode t = null;
        for (int i = 0; i < 80; i++) {
            t = table(owner, id);
            if (done.test(t)) {
                return t;
            }
            Thread.sleep(100);
        }
        return t;
    }

    private List<String> tables(String owner) {
        List<String> tables = new ArrayList<>();
        get("/floors", owner).body().forEach(f -> f.get("tables").forEach(t -> tables.add(t.get("id").asText())));
        return tables;
    }

    private String code(String owner, String orderId) {
        String c = get("/orders/" + orderId, owner).at("/billCode").asText();
        assertThat(c).matches("\\d{4}");
        return c;
    }

    /** The kitchen finishes everything, so the diner may pay (pay when the food is ready). */
    private void cookAll(String owner) {
        for (JsonNode t : get("/kitchen", owner).body()) {
            post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", owner, Map.of());
            post("/kitchen/orders/" + t.get("orderId").asText() + "/bump", owner, Map.of());
        }
    }

    @Test
    void aDinerOpensOnlyTheirOwnBillFromTheirPhoneOrWithItsCode() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        List<String> tables = tables(owner);
        String dish = dish(slug);
        String table = tables.get(0);

        // No number needed to order. Achieng orders from her phone; a waiter opens a second bill on the same table.
        String achieng = post("/public/orders/" + slug, null, Map.of("tableId", table, "customerLabel", "Achieng",
                "lines", List.of(Map.of("itemId", dish, "qty", 1)))).at("/token").asText();
        Res second = post("/orders", owner, Map.of("channel", "DINE_IN", "tableId", table, "guests", 2, "allowSecondBill", true,
                "lines", List.of(Map.of("itemId", dish, "qty", 3))));
        assertThat(second.status()).as(second.body().toString()).isEqualTo(200);
        String secondCode = code(owner, second.at("/id").asText());

        // The Pay QR says how many bills are open, but lists none.
        JsonNode qr = get("/public/tables/" + slug + "/" + table + "/bills", null).body();
        assertThat(qr.at("/openBills").asInt()).isEqualTo(2);
        assertThat(qr.has("bills")).isFalse();

        // Achieng's phone remembers her order and opens only hers.
        String url = "/public/tables/" + slug + "/" + table + "/find-bill";
        JsonNode mine = post(url, null, Map.of("tokens", List.of(achieng))).at("/bills");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("customerLabel").asText()).isEqualTo("Achieng");
        assertThat(mine.get(0).has("token")).isFalse();
        assertThat(get("/public" + mine.get(0).get("trackPath").asText(), null).at("/order/customerLabel").asText()).isEqualTo("Achieng");

        // The waiter's bill opens with its code, and only that bill.
        JsonNode theirs = post(url, null, Map.of("code", secondCode)).at("/bills");
        assertThat(theirs).hasSize(1);
        assertThat(theirs.get(0).get("trackPath").asText()).isNotEqualTo(mine.get(0).get("trackPath").asText());
        String wrong = secondCode.equals("0000") ? "0001" : "0000";
        Res stranger = post(url, null, Map.of("code", wrong));
        if (stranger.status() == 200) {
            // The other bill on the table happened to get that code; it's still never a list.
            assertThat(stranger.at("/bills")).hasSize(1);
        } else {
            assertThat(stranger.status()).isEqualTo(404);
            assertThat(stranger.body().toString()).contains("code on your bill");
        }
        assertThat(post(url, null, Map.of("code", "12")).status()).isEqualTo(400);
        assertThat(post(url, null, Map.of()).status()).isEqualTo(400);

        // A code only works on its own table, and another restaurant's slug can't reach this table.
        PublicController.FIND_BILL.clear();
        assertThat(post("/public/tables/" + slug + "/" + tables.get(1) + "/find-bill", null, Map.of("code", secondCode)).status()).isEqualTo(404);
        JsonNode other = signup("restaurant");
        assertThat(post("/public/tables/" + other.at("/tenant/slug").asText() + "/" + table + "/find-bill", null,
                Map.of("tokens", List.of(achieng))).status()).isEqualTo(404);

        // Guessing codes is slowed down.
        PublicController.FIND_BILL.clear();
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            if (post(url, null, Map.of("code", String.format("%04d", 9000 + i))).status() == 429) {
                limited++;
            }
        }
        assertThat(limited).isPositive();
    }

    @Test
    void aTablePaidFromAPhoneWaitsForTheWaitersCheck() throws Exception {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        List<String> tables = tables(owner);
        String dish = dish(slug);

        // A diner orders with their number and a friend pays from a different one.
        String table = tables.get(2);
        String token = post("/public/orders/" + slug, null, Map.of("tableId", table, "phone", "0712000005",
                "lines", List.of(Map.of("itemId", dish, "qty", 1)))).at("/token").asText();
        cookAll(owner);
        Res paid = post("/public/track/" + slug + "/" + token + "/pay", null, Map.of("phone", "0722000006"));
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(200);

        // Paid, but the table isn't free until the waiter checks it.
        JsonNode t = waitTable(owner, table, x -> x.path("awaitingPayCheck").asBoolean());
        assertThat(t.get("awaitingPayCheck").asBoolean()).isTrue();
        String orderId = t.get("orderId").asText();
        JsonNode row = null;
        for (JsonNode o : get("/waiter/tables?everyone=true", owner).at("/orders")) {
            if (orderId.equals(o.get("id").asText())) {
                row = o;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("awaitingPayCheck").asBoolean()).isTrue();
        assertThat(row.get("paidFromOtherNumber").asBoolean()).isTrue();
        assertThat(row.get("paidBy").asText()).contains("254722000006");

        assertThat(post("/waiter/orders/" + orderId + "/pay-checked", owner, Map.of()).status()).isEqualTo(200);
        assertThat(table(owner, table).get("orderId").isNull()).isTrue();
        assertThat(post("/waiter/orders/" + orderId + "/pay-checked", owner, Map.of()).status()).isEqualTo(404);

        // A prompt sent from the till needs no check: the waiter is already there.
        String tillTable = tables.get(1);
        Res till = post("/orders", owner, Map.of("channel", "DINE_IN", "tableId", tillTable, "guests", 2,
                "lines", List.of(Map.of("itemId", dish, "qty", 1))));
        String tillOrder = till.at("/id").asText();
        Res stk = post("/mpesa/stk", owner, Map.of("orderId", tillOrder, "phone", "0722000003",
                "amount", till.at("/total").asDouble()));
        assertThat(stk.status()).as(stk.body().toString()).isEqualTo(200);
        JsonNode freed = waitTable(owner, tillTable, x -> x.get("orderId").isNull());
        assertThat(freed.get("orderId").isNull()).isTrue();
    }
}
