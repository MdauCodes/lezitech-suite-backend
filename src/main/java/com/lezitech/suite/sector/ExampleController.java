package com.lezitech.suite.sector;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.common.Db;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A look-around example of a business of the signed-in tenant's own kind. Read-only: it reads the sector
 * pack and writes nothing, so a new owner can see a filled-in platform without anything landing in their own business.
 */
@RestController
@RequestMapping("/api/example")
@Transactional(readOnly = true)
public class ExampleController {

    private final Db db;
    private final SectorCatalog sectors;

    public ExampleController(Db db, SectorCatalog sectors) {
        this.db = db;
        this.sectors = sectors;
    }

    @GetMapping
    public Map<String, Object> example() {
        Map<String, Object> t = db.one("Business", "SELECT sector, sub_sector FROM tenants WHERE id = :t");
        JsonNode pack = sectors.get((String) t.get("sector"), (String) t.get("sub_sector"));
        JsonNode demo = pack.path("demo");

        List<Map<String, Object>> menu = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        for (JsonNode c : pack.path("seed").path("categories")) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode i : c.path("items")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", i.path("name").asText(""));
                m.put("price", i.path("price").asDouble(0));
                items.add(m);
                prices.add(i.path("price").asDouble(0));
            }
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("name", c.path("name").asText(""));
            cat.put("items", items);
            menu.add(cat);
        }

        List<Map<String, Object>> staff = new ArrayList<>();
        for (JsonNode r : demo.path("roles")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.path("name").asText(""));
            m.put("role", r.path("role").asText(""));
            m.put("label", r.path("label").asText(""));
            staff.add(m);
        }

        // Made-up but steady figures: a fixed basket of the example's own prices, so a sector always shows the same numbers.
        int orders = 12;
        double total = 0;
        for (int n = 0; n < orders && !prices.isEmpty(); n++) {
            total += prices.get(n % prices.size()) + prices.get((n * 3 + 1) % prices.size());
        }
        String busiest = staff.isEmpty() ? "" : String.valueOf(staff.get(staff.size() > 2 ? 2 : 0).get("name"));

        Map<String, Object> takings = new LinkedHashMap<>();
        takings.put("salesToday", Math.round(total));
        takings.put("orders", orders);
        takings.put("customersServed", 9);
        takings.put("busiest", busiest);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", demo.path("business").asText("Your business"));
        out.put("tagline", pack.path("tagline").asText(""));
        out.put("menu", menu);
        out.put("staff", staff);
        out.put("takings", takings);
        return out;
    }
}
