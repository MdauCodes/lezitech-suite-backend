package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Every product gets a short code unique in its business (DRI-0012); sizes add theirs (CLO-0004-M-RED). */
class ProductCodesTest extends IntegrationTest {

    private JsonNode find(String token, String path, String name) {
        for (JsonNode i : get(path, token).body()) if (i.at("/name").asText().equals(name)) return i;
        return null;
    }

    @Test
    void productsGetUniqueReadableCodes() {
        String owner = token(signup("retail"));
        String drinks = post("/categories", owner, Map.of("name", "Drinks")).at("/id").asText();
        String soda = "Zz Soda " + UUID.randomUUID().toString().substring(0, 4);
        JsonNode a = post("/items", owner, Map.of("name", soda, "kind", "PRODUCT", "price", 80, "categoryId", drinks)).body();
        assertThat(a.at("/sku").asText()).matches("DRI-\\d{4}");
        JsonNode b = post("/items", owner, Map.of("name", "Zz Juice", "kind", "PRODUCT", "price", 120, "categoryId", drinks)).body();
        assertThat(b.at("/sku").asText()).matches("DRI-\\d{4}").isNotEqualTo(a.at("/sku").asText());
        // No category: the name's first letters.
        assertThat(post("/items", owner, Map.of("name", "Bar soap", "kind", "PRODUCT", "price", 50)).at("/sku").asText()).matches("BAR-\\d{4}");

        // An owner's own code is kept; a code already in use is explained, not saved.
        Map<String, Object> mine = new java.util.HashMap<>(Map.of("name", soda, "kind", "PRODUCT", "price", 80, "sku", "COKE500"));
        assertThat(call("PUT", "/items/" + a.at("/id").asText(), owner, mine).at("/sku").asText()).isEqualTo("COKE500");
        Map<String, Object> clash = new java.util.HashMap<>(Map.of("name", "Zz Juice", "kind", "PRODUCT", "price", 120, "sku", "coke500"));
        Res refused = call("PUT", "/items/" + b.at("/id").asText(), owner, clash);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.at("/message").asText()).contains("already on " + soda);
        // Emptying the box keeps the code it had.
        Map<String, Object> emptied = new java.util.HashMap<>(Map.of("name", soda, "kind", "PRODUCT", "price", 80, "sku", ""));
        assertThat(call("PUT", "/items/" + a.at("/id").asText(), owner, emptied).at("/sku").asText()).isEqualTo("COKE500");
    }

    @Test
    void sizesAddTheirSizeToTheProductCode() {
        String owner = token(signup("retail"));
        String clothes = post("/categories", owner, Map.of("name", "Clothes")).at("/id").asText();
        String tee = "Zz Tee " + UUID.randomUUID().toString().substring(0, 4);
        JsonNode fam = post("/items", owner, Map.of("name", tee, "kind", "PRODUCT", "price", 500, "categoryId", clothes)).body();
        String famCode = fam.at("/sku").asText();
        assertThat(call("PUT", "/items/" + fam.at("/id").asText() + "/variants", owner, Map.of(
                "axes", List.of(Map.of("name", "Size", "values", List.of("M")), Map.of("name", "Colour", "values", List.of("Navy blue"))),
                "variants", List.of(Map.of("values", Map.of("Size", "M", "Colour", "Navy blue"))))).status()).isEqualTo(200);
        assertThat(find(owner, "/items", tee + " M / Navy blue").at("/sku").asText()).isEqualTo(famCode + "-M-NAVYBL");

        // Excel: new rows get codes; a code already in use is left out and noted.
        String shoe = "Zz Shoe " + UUID.randomUUID().toString().substring(0, 4);
        Res imp = post("/items/import", owner, Map.of("rows", List.of(
                Map.of("name", "Zz Belt", "price", 300),
                Map.of("name", "Zz Hat", "price", 200, "sku", famCode),
                Map.of("group", shoe, "size", "41", "price", 2500))));
        assertThat(imp.status()).as(imp.body().toString()).isEqualTo(200);
        assertThat(find(owner, "/items", "Zz Belt").at("/sku").asText()).matches("ZZB-\\d{4}");
        assertThat(find(owner, "/items", "Zz Hat").at("/sku").asText()).isNotEqualTo(famCode);
        assertThat(imp.at("/codeNotes").toString()).contains("Zz Hat");
        String shoeCode = find(owner, "/items?families=true", shoe).at("/sku").asText();
        assertThat(find(owner, "/items", shoe + " 41").at("/sku").asText()).isEqualTo(shoeCode + "-41");
    }
}
