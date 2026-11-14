package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.property.ToLetController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Malls & property, package M7: floor map, the public to-let page, enquiries as leads, the owner's own units. */
class FloorMapAndToLetTest extends IntegrationTest {

    @Autowired
    Mailer mailer;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Nairobi"));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private EmailSender.LogSender log;

    @BeforeEach
    void capture() {
        log = new EmailSender.LogSender();
        mailer.useSender(log);
        ToLetController.ENQUIRIES.clear();
    }

    private JsonNode unit(String owner, String name) {
        for (JsonNode u : get("/property/units", owner).body()) {
            if (name.equals(u.get("name").asText())) {
                return u;
            }
        }
        return null;
    }

    private JsonNode listed(JsonNode page, String name) {
        for (JsonNode u : page.get("units")) {
            if (name.equals(u.get("name").asText())) {
                return u;
            }
        }
        return null;
    }

    @Test
    void thePublicPageListsVacantAndLeavingUnitsAsTheLandlordChooses() {
        JsonNode s = signup("property");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        // G03 shown without a price; G04 hidden; G05 used by the owner; G01's tenant is leaving.
        post("/property/units/" + unit(owner, "G03").get("id").asText() + "/listing", owner, Map.of("showPrice", false, "note", "Corner shop by the entrance"));
        post("/property/units/" + unit(owner, "G04").get("id").asText() + "/listing", owner, Map.of("listed", false));
        assertThat(post("/property/units/" + unit(owner, "G05").get("id").asText() + "/listing", owner, Map.of("ownerUse", true)).status()).isEqualTo(200);
        assertThat(post("/property/units/" + unit(owner, "G01").get("id").asText() + "/listing", owner, Map.of("ownerUse", true)).status()).isEqualTo(409);
        JsonNode g01 = unit(owner, "G01");
        assertThat(post("/property/leases/" + g01.get("leaseId").asText() + "/move-out/preview", owner, Map.of()).status()).isEqualTo(200);

        JsonNode page = get("/public/to-let/" + slug, null).body();
        assertThat(listed(page, "G03").get("rate").isNull()).isTrue();
        assertThat(listed(page, "G03").get("listingNote").asText()).isEqualTo("Corner shop by the entrance");
        assertThat(listed(page, "G06").get("rate").decimalValue()).isPositive();
        assertThat(listed(page, "G04")).isNull();
        assertThat(listed(page, "G05")).isNull();
        assertThat(listed(page, "G01")).isNull();
        assertThat(page.toString()).doesNotContain("Mama Mboga");
        assertThat(get("/public/to-let/no-such-place", null).status()).isEqualTo(404);
        assertThat(unit(owner, "G05").get("ownerUse").asBoolean()).isTrue();
    }

    @Test
    void anEnquiryBecomesALeadAndRentingTheUnitFromItWinsIt() {
        JsonNode s = signup("property");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String g06 = unit(owner, "G06").get("id").asText();
        assertThat(post("/public/to-let/" + slug + "/enquire", null, Map.of("spaceId", g06, "name", "Ali", "phone", "12")).status()).isEqualTo(400);
        assertThat(post("/public/to-let/" + slug + "/enquire", null, Map.of("spaceId", g06, "name", "Ali Hassan", "phone", "0722 333 444",
                "business", "Electronics", "moveIn", "December")).status()).isEqualTo(200);
        assertThat(log.sent.stream().anyMatch(e -> e.subject().contains("Enquiry for G06"))).isTrue();
        JsonNode leads = get("/property/to-let", owner).body().get("leads");
        assertThat(leads.get(0).get("contactName").asText()).isEqualTo("Ali Hassan");
        assertThat(leads.get(0).get("note").asText()).contains("Electronics");

        // Rented out from the lead: the lead is won, and the unit leaves the page.
        Map<String, Object> body = new HashMap<>(Map.of("spaceId", g06, "tenantName", "Ali Hassan", "phone", "0722333444", "rent", 30000,
                "startsOn", TODAY.toString(), "dealId", leads.get(0).get("id").asText()));
        assertThat(post("/property/leases", owner, body).status()).isEqualTo(200);
        assertThat(get("/property/to-let", owner).body().get("leads")).isEmpty();
        assertThat(listed(get("/public/to-let/" + slug, null).body(), "G06")).isNull();

        // The landlord's own business renting a unit like any tenant.
        Map<String, Object> own = new HashMap<>(Map.of("spaceId", unit(owner, "S03").get("id").asText(), "tenantName", "Uhuru Supermarket (ours)",
                "rent", 12000, "startsOn", TODAY.toString(), "ownBusiness", true));
        assertThat(post("/property/leases", owner, own).status()).isEqualTo(200);
        assertThat(unit(owner, "S03").get("ownBusiness").asBoolean()).isTrue();
    }

    @Test
    void floorMapPositionsPlansAndPhotos() {
        JsonNode s = signup("property");
        String owner = token(s);
        String slug = s.at("/tenant/slug").asText();
        String g03 = unit(owner, "G03").get("id").asText();
        assertThat(post("/property/map/positions", owner, Map.of("positions", List.of(Map.of("spaceId", g03, "x", 2, "y", 1, "w", 2, "h", 1, "pinX", 40.5, "pinY", 22)))).status()).isEqualTo(200);
        assertThat(unit(owner, "G03").get("mapX").asInt()).isEqualTo(2);
        assertThat(unit(owner, "G03").get("pinX").decimalValue()).isEqualByComparingTo("40.5");
        assertThat(post("/property/map/positions", owner, Map.of("positions", List.of(Map.of("spaceId", g03, "pinX", 140)))).status()).isEqualTo(400);

        assertThat(upload("/property/map/plan?floor=Ground", owner, PNG, "plan.png").status()).isEqualTo(200);
        JsonNode map = get("/property/map", owner).body();
        assertThat(map.get("plans").get(0).get("floor").asText()).isEqualTo("Ground");
        assertThat(map.get("floors").toString()).contains("Ground").contains("1st");

        // Photos of a listed unit are public; once the unit is hidden they aren't.
        assertThat(upload("/property/units/" + g03 + "/photos", owner, PNG, "shop.png").status()).isEqualTo(200);
        String photo = listed(get("/public/to-let/" + slug, null).body(), "G03").get("photos").get(0).asText();
        assertThat(raw("/public/to-let/" + slug + "/photos/" + photo, null).status()).isEqualTo(200);
        post("/property/units/" + g03 + "/listing", owner, Map.of("listed", false));
        assertThat(raw("/public/to-let/" + slug + "/photos/" + photo, null).status()).isEqualTo(404);
    }
}
