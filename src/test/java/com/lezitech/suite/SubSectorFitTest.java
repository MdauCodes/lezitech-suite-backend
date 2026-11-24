package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A sub-sector's own switches decide which screens the business gets at sign-up. */
class SubSectorFitTest extends IntegrationTest {

    private String modules(String sector, String sub) {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Fit " + sub, "sector", sector, "subSector", sub,
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        assertThat(r.status()).isEqualTo(200);
        return r.body().at("/tenant/settings/modules").toString();
    }

    @Test
    void noTablesNoKitchenNoBookingsWhenTheFlagsSayNo() {
        for (String sub : new String[]{"fast-food", "ice-cream", "cloud-kitchen"}) {
            assertThat(modules("restaurant", sub)).as(sub).doesNotContain("\"floor\"");
        }
        assertThat(modules("restaurant", "ice-cream")).doesNotContain("\"kitchen\"");
        assertThat(modules("beauty", "cosmetics")).doesNotContain("\"bookings\"").contains("\"pos\"");
        assertThat(modules("beauty", "car-wash")).doesNotContain("\"bookings\"").contains("\"pos\"");
    }

    @Test
    void aFullRestaurantKeepsThem() {
        assertThat(modules("restaurant", "restaurant")).contains("\"floor\"").contains("\"kitchen\"");
        assertThat(modules("beauty", "hair-salon")).contains("\"bookings\"");
    }
}
