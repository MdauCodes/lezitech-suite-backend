package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One product in several sizes and colours: each is its own price, barcode and stock; the till sells a size, never
 * the family; buying, stock and Excel all work per size.
 */
class VariantsTest extends IntegrationTest {

    private JsonNode find(String token, String path, String name) {
        for (JsonNode i : get(path, token).body()) if (i.at("/name").asText().equals(name)) return i;
        return null;
    }

    @Test
    void sizesAndColoursEachHaveTheirOwnPriceAndStock() {
        String owner = token(signup("retail"));
        String tee = "Zz Tee " + UUID.randomUUID().toString().substring(0, 4);
        String code = "88" + (System.nanoTime() % 1_000_000_000L);
        String family = post("/items", owner, Map.of("name", tee, "kind", "PRODUCT", "price", 500, "cost", 300, "trackStock", true)).at("/id").asText();

        Res saved = call("PUT", "/items/" + family + "/variants", owner, Map.of(
                "axes", List.of(Map.of("name", "Size", "values", List.of("S", "M")), Map.of("name", "Colour", "values", List.of("Red"))),
                "variants", List.of(
                        Map.of("values", Map.of("Size", "S", "Colour", "Red"), "barcode", code),
                        Map.of("values", Map.of("Size", "M", "Colour", "Red"), "price", 550, "reorderLevel", 2))));
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        assertThat(saved.at("/variants").size()).isEqualTo(2);

        // Stock, buying and other screens see the sizes, not the family; the till and products screen see both.
        JsonNode small = find(owner, "/items", tee + " S / Red");
        JsonNode medium = find(owner, "/items", tee + " M / Red");
        assertThat(small).isNotNull();
        assertThat(small.at("/price").asDouble()).isEqualTo(500.0); // the family's price when none is given
        assertThat(medium.at("/price").asDouble()).isEqualTo(550.0);
        assertThat(find(owner, "/items", tee)).isNull();
        assertThat(find(owner, "/items?families=true", tee).at("/variantAxes").toString()).contains("Size");
        assertThat(get("/items/barcode/" + code, owner).at("/name").asText()).isEqualTo(tee + " S / Red");

        // The family can't be sold; a size can, and only its stock moves.
        assertThat(post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", family, "qty", 1)))).at("/message").asText())
                .contains("Choose which size or colour");
        String m = medium.at("/id").asText();
        Res po = post("/purchases", owner, Map.of("lines", List.of(Map.of("itemId", m, "qty", 10, "unitCost", 320))));
        assertThat(po.status()).as(po.body().toString()).isEqualTo(200);
        assertThat(post("/purchases/" + po.at("/id").asText() + "/receive", owner, Map.of()).status()).isEqualTo(200);
        Res order = post("/orders", owner, Map.of("lines", List.of(Map.of("itemId", m, "qty", 3))));
        assertThat(order.status()).as(order.body().toString()).isEqualTo(200);
        assertThat(post("/orders/" + order.at("/id").asText() + "/pay", owner, Map.of("payments", List.of(Map.of("method", "CASH", "amount", 1650)))).status()).isEqualTo(200);
        assertThat(find(owner, "/items", tee + " M / Red").at("/onHand").asDouble()).isEqualTo(7.0);
        assertThat(find(owner, "/items", tee + " S / Red").at("/onHand").asDouble()).isEqualTo(0.0);

        // Renaming the family renames its sizes; dropping a size puts it away but keeps its history.
        Map<String, Object> rename = new java.util.HashMap<>(Map.of("name", tee + " Polo", "kind", "PRODUCT", "price", 500, "cost", 300));
        assertThat(call("PUT", "/items/" + family, owner, rename).status()).isEqualTo(200);
        assertThat(find(owner, "/items", tee + " Polo M / Red")).isNotNull();
        Res fewer = call("PUT", "/items/" + family + "/variants", owner, Map.of(
                "axes", List.of(Map.of("name", "Size", "values", List.of("S", "M")), Map.of("name", "Colour", "values", List.of("Red"))),
                "variants", List.of(Map.of("id", m, "values", Map.of("Size", "M", "Colour", "Red"), "price", 560))));
        assertThat(fewer.status()).as(fewer.body().toString()).isEqualTo(200);
        assertThat(find(owner, "/items", tee + " Polo S / Red")).isNull();
        assertThat(find(owner, "/items", tee + " Polo M / Red").at("/price").asDouble()).isEqualTo(560.0);

        // Mistakes are explained.
        assertThat(call("PUT", "/items/" + family + "/variants", owner, Map.of(
                "axes", List.of(Map.of("name", "Size", "values", List.of("S"))),
                "variants", List.of(Map.of("values", Map.of("Size", "XL"))))).at("/message").asText()).contains("Choose the size");
        assertThat(call("PUT", "/items/" + m + "/variants", owner, Map.of("axes", List.of())).status()).isEqualTo(400);
    }

    @Test
    void excelRowsWithGroupSizeAndColour() {
        String owner = token(signup("retail"));
        String shoe = "Zz Shoe " + UUID.randomUUID().toString().substring(0, 4);
        Res imp = post("/items/import", owner, Map.of("rows", List.of(
                Map.of("group", shoe, "size", "40", "colour", "Black", "price", 2500, "stock", 4),
                Map.of("group", shoe, "size", "41", "colour", "Black", "price", 2500, "stock", 2),
                Map.of("group", shoe, "size", "41", "colour", "Brown", "price", 2600))));
        assertThat(imp.status()).as(imp.body().toString()).isEqualTo(200);
        JsonNode fam = find(owner, "/items?families=true", shoe);
        assertThat(fam).isNotNull();
        assertThat(fam.at("/variantAxes").toString()).contains("40", "41", "Black", "Brown");
        assertThat(find(owner, "/items", shoe + " 41 / Brown").at("/price").asDouble()).isEqualTo(2600.0);
        assertThat(find(owner, "/items", shoe + " 40 / Black").at("/onHand").asDouble()).isEqualTo(4.0);
        // Importing the same rows again updates them instead of making copies.
        assertThat(post("/items/import", owner, Map.of("rows", List.of(Map.of("group", shoe, "size", "40", "colour", "Black", "price", 2400)))).status()).isEqualTo(200);
        assertThat(get("/items/" + fam.at("/id").asText() + "/variants", owner).at("/variants").size()).isEqualTo(3);
        assertThat(find(owner, "/items", shoe + " 40 / Black").at("/price").asDouble()).isEqualTo(2400.0);
    }

    @Test
    void aProductWithItsOwnStockMustBeCountedIntoSizesFirst() {
        String owner = token(signup("retail"));
        String cap = "Zz Cap " + UUID.randomUUID().toString().substring(0, 4);
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", cap, "kind", "PRODUCT", "price", 300, "trackStock", true, "openingStock", 5));
        String id = post("/items", owner, body).at("/id").asText();
        Res r = call("PUT", "/items/" + id + "/variants", owner, Map.of(
                "axes", List.of(Map.of("name", "Colour", "values", List.of("Blue"))),
                "variants", List.of(Map.of("values", Map.of("Colour", "Blue")))));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.at("/message").asText()).contains("stock of its own");
    }

    @Test
    void barcodeWordsOnlyForBusinessesThatUseThem() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "No Barcodes Shop", "sector", "retail", "name", "Owner", "email", email,
                "password", "password123", "withSamples", false, "barcodes", false));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/tenant/settings/features/barcode").asBoolean()).isFalse();
        String owner = r.body().get("token").asText();
        // The first product saved with a barcode switches scanning on.
        String code = "77" + (System.nanoTime() % 1_000_000_000L);
        assertThat(post("/items", owner, Map.of("name", "Zz Scanned " + code, "kind", "PRODUCT", "price", 50, "barcode", code)).status()).isEqualTo(200);
        assertThat(get("/auth/me", owner).at("/tenant/settings/features/barcode").asBoolean()).isTrue();
    }
}
