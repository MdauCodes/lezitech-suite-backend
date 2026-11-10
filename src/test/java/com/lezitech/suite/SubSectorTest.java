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
        assertThat(retail.get("subSectors").toString()).contains("Butchery").contains("Petrol station").contains("Hardware");
        assertThat(retail.at("/subSectorDetails").toString()).contains("\"key\":\"butchery\"").contains("Do you sell on credit to fundis");
        assertThat(retail.has("seed")).isFalse();
    }

    /** Pharmacy and medical clinics are "coming soon" (patients' health data): listed, but nobody can open one yet. */
    @Test
    void comingSoonSectorsCantBeOpened() {
        Res pharmacy = signupAs("pharmacy", null, Map.of());
        assertThat(pharmacy.status()).isEqualTo(400);
        assertThat(pharmacy.at("/message").asText()).contains("coming soon");
        assertThat(signupAs("beauty", "clinic", Map.of()).status()).isEqualTo(400);
        // Nor can the LeziTech team create one from the control panel.
        String admin = post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
        assertThat(post("/admin/workspaces", admin, Map.of("businessName", "Afya Chemist", "sector", "pharmacy", "name", "Owner",
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
        // No demo workspace for pharmacy.
        assertThat(get("/public/demo", null).body().toString()).doesNotContain("\"sector\":\"pharmacy\"");
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
