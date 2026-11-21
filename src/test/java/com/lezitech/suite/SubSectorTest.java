package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Sub-sectors personalise a sector pack: wording, features, KPIs, sample items and setup questions. */
class SubSectorTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private Res signupAs(String sector, String subSector, Map<String, Object> extra) {
        String email = "sub-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Map<String, Object> body = new HashMap<>(Map.of("businessName", "Sub " + email.substring(4, 8), "sector", sector,
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        if (subSector != null) {
            body.put("subSector", subSector);
        }
        body.putAll(extra);
        return post("/auth/signup", null, body);
    }

    private List<String> itemNames(String token) {
        List<String> out = new ArrayList<>();
        get("/items", token).body().forEach(i -> out.add(i.get("name").asText()));
        return out;
    }

    @Test
    void aHardwareShopGetsHardwareItemsWordingAndKpis() {
        Res s = signupAs("retail", "hardware", Map.of("setupAnswers", Map.of("credit", true, "delivery", false)));
        assertThat(s.status()).as(s.body().toString()).isEqualTo(200);
        String token = token(s.body());
        // Matched by key, stored by its name.
        assertThat(s.at("/tenant/subSector").asText()).isEqualTo("Hardware");
        assertThat(s.at("/pack/subSectorKey").asText()).isEqualTo("hardware");
        assertThat(s.at("/pack/kpis").toString()).contains("open_quotes");
        assertThat(s.at("/pack/features/wholesalePricing").asBoolean()).isTrue();
        assertThat(itemNames(token)).anyMatch(n -> n.startsWith("Cement 50kg")).noneMatch(n -> n.startsWith("Maize flour"));
        // The plain retail pack's own locations are kept.
        assertThat(get("/locations", token).body().size()).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT settings->'setupAnswers'->>'credit' FROM tenants WHERE id = ?::uuid", String.class,
                s.at("/tenant/id").asText())).isEqualTo("true");
        // The session keeps the personalised pack after signing in again.
        assertThat(get("/auth/me", token).at("/pack/kpis").toString()).contains("open_quotes");
    }

    @Test
    void aCarWashIsAnAppointmentsPackWithItsOwnWords() {
        Res s = signupAs("beauty", "Car wash", Map.of());
        String token = token(s.body());
        assertThat(s.at("/pack/terms/staff").asText()).isEqualTo("Washers");
        assertThat(s.at("/pack/terms/order").asText()).isEqualTo("Wash");
        assertThat(itemNames(token)).contains("SUV full wash").doesNotContain("Wash & blow-dry");
    }

    @Test
    void noOrUnknownSubSectorKeepsThePlainPack() {
        Res plain = signupAs("retail", null, Map.of());
        assertThat(itemNames(token(plain.body()))).anyMatch(n -> n.startsWith("Maize flour"));
        Res typed = signupAs("retail", "Something we don't know", Map.of());
        assertThat(typed.at("/tenant/subSector").asText()).isEqualTo("Something we don't know");
        assertThat(typed.at("/pack/kpis").toString()).isEqualTo(plain.at("/pack/kpis").toString());
    }

    @Test
    void thePickerListsSubSectorsWithTheirQuestions() {
        JsonNode sectors = get("/public/sectors", null).body();
        JsonNode retail = null;
        for (JsonNode p : sectors) {
            if ("retail".equals(p.get("key").asText())) {
                retail = p;
            }
        }
        assertThat(retail).isNotNull();
        assertThat(retail.get("subSectors").toString()).contains("Butchery").contains("Hardware").doesNotContain("Petrol station");
        assertThat(retail.at("/subSectorDetails").toString()).contains("\"key\":\"butchery\"").contains("Do you sell on credit to fundis");
        assertThat(retail.has("seed")).isFalse();
    }

    /** Pharmacy, resorts and medical clinics are "coming soon": listed, but nobody can open one yet. */
    @Test
    void comingSoonSectorsCantBeOpened() {
        Res pharmacy = signupAs("pharmacy", null, Map.of());
        assertThat(pharmacy.status()).isEqualTo(400);
        assertThat(pharmacy.at("/message").asText()).contains("coming soon");
        // Resorts (21 Nov): the pack is there so people see it is on the way, but nobody can open one yet,
        // by the sector's own name or by one of its kinds.
        Res resort = signupAs("resort", null, Map.of());
        assertThat(resort.status()).isEqualTo(400);
        assertThat(resort.at("/message").asText()).contains("coming soon");
        assertThat(signupAs("resort", "beach-resort", Map.of()).status()).isEqualTo(400);
        assertThat(signupAs("beauty", "clinic", Map.of()).status()).isEqualTo(400);
        // Launch (19 Nov): the types whose own tools aren't built yet open when their phase ships.
        for (String[] soon : new String[][]{{"services", "garage"}, {"services", "laundry"}, {"services", "school"}, {"services", "tailoring"},
                {"beauty", "gym"}, {"retail", "phones"}, {"retail", "petrol-station"}, {"retail", "gas-water"}}) {
            assertThat(signupAs(soon[0], soon[1], Map.of()).status()).as(soon[1]).isEqualTo(400);
        }
        // Nor can the LeziTech team create one from the control panel.
        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
        assertThat(post("/admin/workspaces", admin, Map.of("businessName", "Afya Chemist", "sector", "pharmacy", "name", "Owner",
                "email", "soon-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local")).status()).isEqualTo(400);
        assertThat(post("/admin/workspaces", admin, Map.of("businessName", "Bahari Resort", "sector", "resort", "name", "Owner",
                "email", "soon-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local")).status()).isEqualTo(400);
        // The beauty picker no longer offers clinics, but the details say they're coming.
        JsonNode beauty = null;
        for (JsonNode p : get("/public/sectors", null).body()) {
            if ("beauty".equals(p.get("key").asText())) {
                beauty = p;
            }
        }
        assertThat(beauty.get("subSectors").toString()).doesNotContain("Medical & dental clinic").contains("Car wash");
        assertThat(beauty.at("/subSectorDetails").toString()).contains("\"key\":\"clinic\"").contains("\"comingSoon\":true");
        // The sign-up picker lists resorts, marked as coming soon.
        JsonNode resortPack = null;
        for (JsonNode p : get("/public/sectors", null).body()) {
            if ("resort".equals(p.get("key").asText())) {
                resortPack = p;
            }
        }
        assertThat(resortPack).isNotNull();
        assertThat(resortPack.path("comingSoon").asBoolean()).isTrue();
        assertThat(resortPack.get("name").asText()).contains("Resort");
        // No demo workspace for pharmacy or resorts.
        assertThat(get("/public/demo", null).body().toString()).doesNotContain("\"sector\":\"pharmacy\"").doesNotContain("\"sector\":\"resort\"");
    }

    /** Every sub-sector's sample data loads: a typo in subsectors.json fails here, not on a customer's signup. */
    @Test
    void everySubSectorSignsUpWithItsSampleData() {
        int checked = 0;
        for (JsonNode pack : get("/public/sectors", null).body()) {
            for (JsonNode sub : pack.path("subSectorDetails")) {
                if (pack.path("comingSoon").asBoolean(false) || sub.path("comingSoon").asBoolean(false)) {
                    continue;
                }
                Res s = signupAs(pack.get("key").asText(), sub.get("key").asText(), Map.of());
                assertThat(s.status()).as(pack.get("key").asText() + "/" + sub.get("key").asText() + ": " + s.body()).isEqualTo(200);
                assertThat(s.at("/pack/subSectorKey").asText()).isEqualTo(sub.get("key").asText());
                assertThat(get("/items", token(s.body())).body().size()).as(sub.get("key").asText()).isGreaterThan(0);
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(54);
    }
}
