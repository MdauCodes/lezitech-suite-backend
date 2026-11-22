package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A short delivery is recorded: stock and what we owe the supplier follow what actually arrived. */
class GoodsReceivedTest extends IntegrationTest {

    private double onHand(String token, String id) {
        for (JsonNode i : get("/items", token).body()) {
            if (i.at("/id").asText().equals(id)) {
                return i.at("/onHand").asDouble();
            }
        }
        throw new IllegalStateException("item not found");
    }

    private String newItem(String owner) {
        Res made = post("/items", owner, Map.of("name", "Zz Rice " + UUID.randomUUID().toString().substring(0, 4), "kind", "PRODUCT",
                "price", 200, "cost", 100, "trackStock", true, "unit", "pc"));
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        return made.at("/id").asText();
    }

    private double owed(String owner, String supplier) {
        for (JsonNode s : get("/suppliers", owner).body()) {
            if (s.at("/id").asText().equals(supplier)) {
                return s.at("/balance").asDouble();
            }
        }
        throw new IllegalStateException("supplier not found");
    }

    private Res order(String owner, String item, String supplier) {
        Res po = post("/purchases", owner, Map.of("supplierId", supplier, "lines", List.of(Map.of("itemId", item, "qty", 10, "unitCost", 50))));
        assertThat(po.status()).as(po.body().toString()).isEqualTo(200);
        return po;
    }

    @Test
    void noBodyReceivesEverything() {
        String owner = token(signup("retail"));
        String item = newItem(owner);
        String supplier = post("/suppliers", owner, Map.of("name", "Full Supplier")).at("/id").asText();
        double before = onHand(owner, item);
        Res po = order(owner, item, supplier);
        assertThat(post("/purchases/" + po.at("/id").asText() + "/receive", owner, null).status()).isEqualTo(200);
        assertThat(onHand(owner, item)).isEqualTo(before + 10);
        assertThat(owed(owner, supplier)).isEqualTo(500.0);
    }

    @Test
    void aShortDeliveryIsRecorded() {
        String owner = token(signup("retail"));
        String item = newItem(owner);
        String supplier = post("/suppliers", owner, Map.of("name", "Short Supplier")).at("/id").asText();
        double before = onHand(owner, item);
        Res po = order(owner, item, supplier);
        String lineId = po.at("/lines/0/id").asText();
        Res got = post("/purchases/" + po.at("/id").asText() + "/receive", owner, Map.of("lines", List.of(Map.of("id", lineId, "received", 8))));
        assertThat(got.status()).as(got.body().toString()).isEqualTo(200);
        assertThat(onHand(owner, item)).isEqualTo(before + 8);
        assertThat(got.at("/lines/0/receivedQty").asDouble()).isEqualTo(8.0);
        assertThat(got.at("/total").asDouble()).isEqualTo(500.0);
        assertThat(owed(owner, supplier)).isEqualTo(400.0);
        JsonNode entries = get("/activity?kind=SHORT_DELIVERY", owner).at("/entries");
        assertThat(entries.size()).isEqualTo(1);
        assertThat(entries.get(0).at("/severity").asText()).isEqualTo("CONCERN");
        assertThat(entries.get(0).at("/amount").asDouble()).isEqualTo(100.0);
        assertThat(entries.get(0).at("/summary").asText()).contains("by 2");
    }

    @Test
    void moreThanOrderedIsRefused() {
        String owner = token(signup("retail"));
        String item = newItem(owner);
        String supplier = post("/suppliers", owner, Map.of("name", "Over Supplier")).at("/id").asText();
        double before = onHand(owner, item);
        Res po = order(owner, item, supplier);
        String lineId = po.at("/lines/0/id").asText();
        Res bad = post("/purchases/" + po.at("/id").asText() + "/receive", owner, Map.of("lines", List.of(Map.of("id", lineId, "received", 11))));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(onHand(owner, item)).isEqualTo(before);
        assertThat(owed(owner, supplier)).isEqualTo(0.0);
    }

    @Test
    void nothingArrivedLeavesTheOrderOpen() {
        String owner = token(signup("retail"));
        String item = newItem(owner);
        String supplier = post("/suppliers", owner, Map.of("name", "None Supplier")).at("/id").asText();
        double before = onHand(owner, item);
        Res po = order(owner, item, supplier);
        String id = po.at("/id").asText();
        String lineId = po.at("/lines/0/id").asText();
        Res got = post("/purchases/" + id + "/receive", owner, Map.of("lines", List.of(Map.of("id", lineId, "received", 0))));
        assertThat(got.status()).as(got.body().toString()).isEqualTo(200);
        assertThat(got.at("/status").asText()).isNotEqualTo("RECEIVED");
        assertThat(got.at("/message").asText()).contains("Nothing was recorded as arrived").contains("still open");
        assertThat(got.at("/lines/0/receivedQty").asDouble()).isEqualTo(0.0);
        assertThat(onHand(owner, item)).isEqualTo(before);
        assertThat(owed(owner, supplier)).isEqualTo(0.0);
        assertThat(get("/activity?kind=SHORT_DELIVERY", owner).at("/entries").size()).isEqualTo(1);
        // The goods turn up the next day and can still be recorded.
        assertThat(post("/purchases/" + id + "/receive", owner, null).status()).isEqualTo(200);
        assertThat(onHand(owner, item)).isEqualTo(before + 10);
        assertThat(owed(owner, supplier)).isEqualTo(500.0);
    }

    @Test
    void receivingTwiceDoesNotDoubleTheStock() {
        String owner = token(signup("retail"));
        String item = newItem(owner);
        String supplier = post("/suppliers", owner, Map.of("name", "Twice Supplier")).at("/id").asText();
        double before = onHand(owner, item);
        Res po = order(owner, item, supplier);
        String id = po.at("/id").asText();
        assertThat(post("/purchases/" + id + "/receive", owner, null).status()).isEqualTo(200);
        assertThat(post("/purchases/" + id + "/receive", owner, null).status()).isEqualTo(409);
        assertThat(onHand(owner, item)).isEqualTo(before + 10);
        assertThat(owed(owner, supplier)).isEqualTo(500.0);
    }

    @Test
    void aShortDeliveryInPacksPutsAwayPieces() {
        String owner = token(signup("wholesale"));
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", "Zz Soda " + UUID.randomUUID().toString().substring(0, 4), "kind", "PRODUCT",
                "price", 130, "cost", 100, "trackStock", true, "unit", "pc"));
        body.putAll(Map.of("openingStock", 0, "packUnit", "Carton", "packSize", 24, "packBuyPrice", 2400, "packPrice", 2800));
        Res made = post("/items", owner, body);
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        String soda = made.at("/id").asText();
        double before = onHand(owner, soda);
        Res po = post("/purchases", owner, Map.of("lines", List.of(Map.of("itemId", soda, "qty", 3, "unitCost", 2400, "inPacks", true))));
        assertThat(po.status()).as(po.body().toString()).isEqualTo(200);
        String lineId = po.at("/lines/0/id").asText();
        Res got = post("/purchases/" + po.at("/id").asText() + "/receive", owner, Map.of("lines", List.of(Map.of("id", lineId, "received", 2))));
        assertThat(got.status()).as(got.body().toString()).isEqualTo(200);
        assertThat(onHand(owner, soda)).isEqualTo(before + 48);
    }
}
