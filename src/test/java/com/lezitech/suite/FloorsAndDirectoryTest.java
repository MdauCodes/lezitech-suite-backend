package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Malls & property, package M8: floors of multi-storey buildings, and the mall directory. */
class FloorsAndDirectoryTest extends IntegrationTest {

    @Autowired
    Mailer mailer;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Nairobi"));
    private EmailSender.LogSender log;

    @BeforeEach
    void capture() {
        log = new EmailSender.LogSender();
        mailer.useSender(log);
    }

    private List<String> floorNames(JsonNode floors) {
        List<String> out = new ArrayList<>();
        floors.forEach(f -> out.add(f.get("name").asText()));
        return out;
    }

    private String floorId(JsonNode floors, String name) {
        for (JsonNode f : floors) {
            if (name.equals(f.get("name").asText())) {
                return f.get("id").asText();
            }
        }
        return null;
    }

    @Test
    void floorsAreInTheBuildingsOrderAndAFloorsUnitsAreMadeInOneGo() {
        String owner = token(signup("property"));
        assertThat(floorNames(get("/property/floors", owner).body())).containsExactly("Ground", "1st", "2nd");
        post("/property/floors", owner, Map.of("name", "Basement"));
        JsonNode floors = post("/property/floors", owner, Map.of("name", "3rd")).body();
        assertThat(floorNames(floors)).containsExactly("Basement", "Ground", "1st", "2nd", "3rd");
        assertThat(post("/property/floors", owner, Map.of("name", "ground")).status()).isEqualTo(409);

        // Twenty shops on the 3rd floor in one go; the same names again are refused.
        String third = floorId(floors, "3rd");
        Res made = post("/property/floors/" + third + "/units", owner, Map.of("firstName", "S301", "count", 20, "kind", "SHOP", "areaSqft", 150, "rate", 25000));
        assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
        assertThat(made.at("/last").asText()).isEqualTo("S320");
        assertThat(post("/property/floors/" + third + "/units", owner, Map.of("firstName", "S319", "count", 3)).status()).isEqualTo(409);

        // Units come in floor order; renaming a floor moves its units with it; a floor with units can't be removed.
        List<String> order = new ArrayList<>();
        get("/property/units", owner).body().forEach(u -> order.add(u.get("name").asText()));
        assertThat(order.indexOf("G01")).isLessThan(order.indexOf("S01"));
        assertThat(order.indexOf("O1")).isLessThan(order.indexOf("S301"));
        post("/property/floors/" + third, owner, Map.of("name", "Third floor"));
        boolean moved = false;
        for (JsonNode u : get("/property/units", owner).body()) {
            moved |= "S305".equals(u.get("name").asText()) && "Third floor".equals(u.get("floor").asText());
        }
        assertThat(moved).isTrue();
        assertThat(post("/property/floors/" + third + "/delete", owner, Map.of()).status()).isEqualTo(409);
        assertThat(get("/property/map", owner).at("/floors").toString()).contains("Third floor");
    }

    @Test
    void tenantsKeepTheirShopEntryAndOffersAndTheLandlordApproves() {
        JsonNode s = signup("property");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        // The seeded tenants are already in the directory.
        assertThat(get("/public/mall/" + slug, null).body().toString()).contains("Mama Mboga Stores").contains("Wanjiku Fashions");

        // A tenant with a portal sign-in edits their shop: it waits for the landlord.
        String email = "shop-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        String unit = null;
        for (JsonNode u : get("/property/units", owner).body()) {
            if ("G03".equals(u.get("name").asText())) {
                unit = u.get("id").asText();
            }
        }
        String lease = post("/property/leases", owner, new HashMap<>(Map.of("spaceId", unit, "tenantName", "Otieno Electronics", "email", email,
                "rent", 30000, "startsOn", TODAY.toString()))).at("/id").asText();
        post("/property/leases/" + lease + "/portal-invite", owner, Map.of());
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(log.sent.stream().filter(e -> e.to().equals(email)).reduce((a, b) -> b).get().text());
        assertThat(m.find()).isTrue();
        String tenant = post("/public/portal/" + slug + "/password", null, Map.of("email", email, "code", m.group(1), "password", "shop-pass-1")).at("/token").asText();

        assertThat(get("/portal/leases/" + lease + "/shop", tenant).at("/displayName").asText()).isEqualTo("Otieno Electronics");
        post("/portal/leases/" + lease + "/shop", tenant, Map.of("displayName", "Otieno Phones & TVs", "category", "ELECTRONICS",
                "description", "Phones, TVs and repairs", "hours", "Mon–Sat 8am–8pm"));
        post("/portal/leases/" + lease + "/shop/offers", tenant, Map.of("title", "10% off all TVs this week"));
        String pub = get("/public/mall/" + slug, null).body().toString();
        assertThat(pub).contains("Otieno Electronics").doesNotContain("Otieno Phones").doesNotContain("10% off");

        // The landlord approves the change and the offer: now public.
        JsonNode admin = get("/property/directory", owner).body();
        assertThat(admin.get("waiting").asInt()).isEqualTo(2);
        String entry = null;
        String offer = null;
        for (JsonNode e : admin.get("entries")) {
            if ("G03".equals(e.get("unitName").asText())) {
                entry = e.get("id").asText();
                offer = e.get("offers").get(0).get("id").asText();
            }
        }
        post("/property/directory/" + entry + "/approve", owner, Map.of());
        post("/property/directory/offers/" + offer + "/approve", owner, Map.of());
        pub = get("/public/mall/" + slug, null).body().toString();
        assertThat(pub).contains("Otieno Phones & TVs").contains("10% off all TVs").contains("ELECTRONICS");

        // Hidden: gone from the page. A new tenant in the unit starts a fresh entry.
        post("/property/directory/" + entry, owner, Map.of("displayName", "Otieno Phones & TVs", "hidden", true));
        assertThat(get("/public/mall/" + slug, null).body().toString()).doesNotContain("Otieno Phones");
        post("/property/leases/" + lease + "/move-out", owner, Map.of());
        post("/property/leases", owner, new HashMap<>(Map.of("spaceId", unit, "tenantName", "Akinyi Books", "rent", 30000, "startsOn", TODAY.toString())));
        pub = get("/public/mall/" + slug, null).body().toString();
        assertThat(pub).contains("Akinyi Books").doesNotContain("Otieno");
    }
}
