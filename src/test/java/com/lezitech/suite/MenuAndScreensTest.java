package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.publicapi.PublicController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Package F: allergens on menu items, and order-status screens per area. */
class MenuAndScreensTest extends IntegrationTest {

    @BeforeEach
    void freshLimits() {
        PublicController.PER_TABLE.clear();
        PublicController.PER_CLIENT.clear();
    }

    private JsonNode firstDish(String slug) {
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (!i.get("soldOut").asBoolean() && i.get("options").isEmpty() && "MENU".equals(i.get("kind").asText())) {
                return i;
            }
        }
        throw new AssertionError("no plain dish on the sample menu");
    }

    @Test
    void allergensAreChosenFromTheListAndShowOnTheMenu() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        JsonNode dish = firstDish(slug);
        String id = dish.get("id").asText();
        JsonNode item = null;
        for (JsonNode i : get("/items?all=true", owner).body()) {
            if (i.get("id").asText().equals(id)) {
                item = i;
            }
        }
        assertThat(item).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(item, Map.class);

        body.put("allergens", List.of("Sesame", "nuts", "nuts"));
        body.put("allergenNote", "  Cooked in groundnut oil  ");
        Res saved = call("PUT", "/items/" + id, owner, body);
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        assertThat(saved.at("/allergens").toString()).isEqualTo("[\"nuts\",\"sesame\"]");
        assertThat(saved.at("/allergenNote").asText()).isEqualTo("Cooked in groundnut oil");

        JsonNode onMenu = null;
        for (JsonNode i : get("/public/menu/" + slug, null).body().get("items")) {
            if (i.get("id").asText().equals(id)) {
                onMenu = i;
            }
        }
        assertThat(onMenu.get("allergens").toString()).isEqualTo("[\"nuts\",\"sesame\"]");
        assertThat(onMenu.get("allergenNote").asText()).isEqualTo("Cooked in groundnut oil");

        body.put("allergens", List.of("glitter"));
        assertThat(call("PUT", "/items/" + id, owner, body).status()).isEqualTo(400);

        // Saving without the allergen fields leaves them as they were (e.g. an older till screen).
        body.remove("allergens");
        body.remove("allergenNote");
        assertThat(call("PUT", "/items/" + id, owner, body).at("/allergens").toString()).isEqualTo("[\"nuts\",\"sesame\"]");
    }

    @Test
    void anAreaScreenShowsOnlyItsTablesOrders() {
        JsonNode s = signup("restaurant");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        List<String> tables = new ArrayList<>();
        get("/floors", owner).body().forEach(f -> f.get("tables").forEach(t -> tables.add(t.get("id").asText())));
        String dish = firstDish(slug).get("id").asText();

        Res made = post("/board-screens", owner, Map.of("name", "Terrace TV", "tableIds", List.of(tables.get(0))));
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        String key = made.at("/screenKey").asText();
        assertThat(key).hasSizeGreaterThan(20);
        assertThat(get("/board-screens", owner).body().get(0).get("tableIds")).hasSize(1);

        int mine = post("/public/orders/" + slug, null, Map.of("tableId", tables.get(0), "lines", List.of(Map.of("itemId", dish, "qty", 1))))
                .at("/number").asInt();
        int other = post("/public/orders/" + slug, null, Map.of("tableId", tables.get(1), "lines", List.of(Map.of("itemId", dish, "qty", 1))))
                .at("/number").asInt();

        JsonNode screen = get("/public/board/" + slug + "?k=" + key, null).body();
        assertThat(screen.get("screenName").asText()).isEqualTo("Terrace TV");
        assertThat(numbers(screen)).contains(mine).doesNotContain(other);

        JsonNode whole = get("/public/board/" + slug + "?k=" + s.at("/tenant/displayKey").asText(), null).body();
        assertThat(numbers(whole)).contains(mine, other);

        assertThat(get("/public/board/" + slug + "?k=nope", null).status()).isEqualTo(404);

        // A new link retires the old one.
        String id = made.at("/id").asText();
        String fresh = post("/board-screens/" + id + "/new-link", owner, Map.of()).at("/screenKey").asText();
        assertThat(get("/public/board/" + slug + "?k=" + key, null).status()).isEqualTo(404);
        assertThat(get("/public/board/" + slug + "?k=" + fresh, null).status()).isEqualTo(200);

        // Only managers set screens up; another restaurant's tables can't be added.
        String waiter = staff(owner, "WAITER");
        assertThat(get("/board-screens", waiter).status()).isEqualTo(403);
        JsonNode other2 = signup("restaurant");
        String foreign = get("/floors", token(other2)).body().get(0).get("tables").get(0).get("id").asText();
        assertThat(call("PUT", "/board-screens/" + id, owner, Map.of("name", "Terrace TV", "tableIds", List.of(foreign))).status()).isEqualTo(404);
    }

    private static List<Integer> numbers(JsonNode board) {
        List<Integer> out = new ArrayList<>();
        board.get("preparing").forEach(o -> out.add(o.get("number").asInt()));
        board.get("ready").forEach(o -> out.add(o.get("number").asInt()));
        return out;
    }
}
