package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.platform.PlatformSettings;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan offers set in the control panel: price now with no offer, an offer that is live, not started or over, a business's
 * own offer against the plan offer (the lower price wins), the amount asked for equals the amount shown, and /api/public/plans.
 */
class PlanOffersTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformSettings platform;

    private String admin() {
        return post("/admin/auth/demo", null, Map.of("role", "ADMIN")).at("/token").asText();
    }

    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM platform_settings WHERE key = 'planOffers'");
        jdbc.update("UPDATE platform_offers SET active = false WHERE sector = 'tours'");
        platform.forget();
    }

    private static Map<String, Object> offer(Object price, String label, LocalDate start, LocalDate end, boolean on) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("price", price);
        m.put("label", label);
        m.put("startsOn", start == null ? null : start.toString());
        m.put("endsOn", end == null ? null : end.toString());
        m.put("on", on);
        return m;
    }

    private Res save(String admin, Map<String, Object> growth) {
        return call("PUT", "/admin/platform-settings", admin, Map.of("planOffers", Map.of("GROWTH", growth)));
    }

    private JsonNode publicPlan(String code) {
        for (JsonNode p : get("/public/plans", null).body().get("plans")) {
            if (code.equals(p.path("code").asText())) {
                return p;
            }
        }
        throw new IllegalStateException("no " + code);
    }

    private JsonNode myPlan(String token, String code) {
        for (JsonNode p : get("/billing/plans", token).body().get("plans")) {
            if (code.equals(p.path("code").asText())) {
                return p;
            }
        }
        throw new IllegalStateException("no " + code);
    }

    @Test
    void noOfferMeansTheNormalPrice() {
        JsonNode p = publicPlan("GROWTH");
        assertThat(p.get("price").asInt()).isEqualTo(4500);
        assertThat(p.get("priceNow").asInt()).isEqualTo(4500);
        assertThat(p.get("offerLabel").isNull()).isTrue();
        assertThat(p.get("offerEnds").isNull()).isTrue();
        assertThat(myPlan(token(signup("tours")), "GROWTH").get("pay").asLong()).isEqualTo(4500);
    }

    @Test
    void aLiveOfferShowsEverywhereAndIsWhatIsCharged() {
        String admin = admin();
        LocalDate end = LocalDate.now().plusDays(20);
        Res r = save(admin, offer(3500, "December offer", LocalDate.now().minusDays(1), end, true));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/planOffers/GROWTH/price").asInt()).isEqualTo(3500);

        JsonNode p = publicPlan("GROWTH");
        assertThat(p.get("price").asInt()).isEqualTo(4500);
        assertThat(p.get("priceNow").asInt()).isEqualTo(3500);
        assertThat(p.get("offerLabel").asText()).isEqualTo("December offer");
        assertThat(p.get("offerEnds").asText()).isEqualTo(end.toString());
        assertThat(publicPlan("LITE").get("priceNow").asInt()).isEqualTo(2650);

        String owner = token(signup("tours"));
        JsonNode mine = myPlan(owner, "GROWTH");
        assertThat(mine.get("pay").asLong()).isEqualTo(3500);
        assertThat(mine.get("payYear").asLong()).isEqualTo(35000);
        assertThat(mine.get("price").asLong()).isEqualTo(4500);
        // The payment request asks for exactly the price shown.
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY")).at("/amount").asLong()).isEqualTo(3500);
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "ANNUAL")).at("/amount").asLong()).isEqualTo(35000);
        assertThat(jdbc.queryForObject("SELECT amount FROM subscription_requests WHERE plan = 'GROWTH' AND billing_cycle = 'MONTHLY' ORDER BY created_at DESC LIMIT 1", Long.class)).isEqualTo(3500);
    }

    @Test
    void notStartedOrExpiredOffersDoNotApply() {
        String admin = admin();
        assertThat(save(admin, offer(3500, "Soon", LocalDate.now().plusDays(3), LocalDate.now().plusDays(9), true)).status()).isEqualTo(200);
        assertThat(publicPlan("GROWTH").get("priceNow").asInt()).isEqualTo(4500);
        // Over: written straight in (the panel refuses to save a past end date), and an off switch also ends it.
        jdbc.update("INSERT INTO platform_settings (key, value) VALUES ('planOffers', cast(? as jsonb)) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                "{\"GROWTH\":{\"price\":3500,\"label\":\"Old\",\"startsOn\":\"2020-01-01\",\"endsOn\":\"2020-01-31\",\"on\":true}}");
        platform.forget();
        assertThat(publicPlan("GROWTH").get("priceNow").asInt()).isEqualTo(4500);
        assertThat(myPlan(token(signup("tours")), "GROWTH").get("pay").asLong()).isEqualTo(4500);
        assertThat(save(admin, offer(3500, "Off", null, null, false)).status()).isEqualTo(200);
        assertThat(publicPlan("GROWTH").get("priceNow").asInt()).isEqualTo(4500);
    }

    @Test
    void anOpenEndedOfferHasNoEndDate() {
        assertThat(save(admin(), offer(4000, "", null, null, true)).status()).isEqualTo(200);
        JsonNode p = publicPlan("GROWTH");
        assertThat(p.get("priceNow").asInt()).isEqualTo(4000);
        assertThat(p.get("offerLabel").asText()).isEqualTo("Offer");
        assertThat(p.get("offerEnds").isNull()).isTrue();
    }

    @Test
    void theOwnerCannotSaveABadOffer() {
        String admin = admin();
        assertThat(save(admin, offer(4500, "Same", null, null, true)).status()).isEqualTo(400);
        assertThat(save(admin, offer(5000, "Higher", null, null, true)).status()).isEqualTo(400);
        assertThat(save(admin, offer(3000, "Backwards", LocalDate.now().plusDays(5), LocalDate.now().plusDays(2), true)).status()).isEqualTo(400);
        assertThat(save(admin, offer(3000, "Past", null, LocalDate.now().minusDays(1), true)).status()).isEqualTo(400);
        assertThat(save(admin, offer("abc", "Words", null, null, true)).status()).isEqualTo(400);
        String support = post("/admin/auth/demo", null, Map.of("role", "SUPPORT")).at("/token").asText();
        assertThat(save(support, offer(3000, "Nope", null, null, true)).status()).isEqualTo(403);
        assertThat(publicPlan("GROWTH").get("priceNow").asInt()).isEqualTo(4500);
    }

    @Test
    void theSavingIsInTheAuditLog() {
        save(admin(), offer(3000, "Logged", null, null, true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE action = 'platform.plan_offers'", Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theBetterOfThePlanOfferAndTheBusinessOfferWins() {
        String admin = admin();
        save(admin, offer(3500, "December offer", null, null, true));
        String owner = token(signup("tours"));
        LocalDate end = LocalDate.now().plusDays(10);
        // A business offer that is worse (10% off 4,500 = 4,050): the plan offer's 3,500 wins.
        Res worse = post("/admin/offers", admin, Map.of("kind", "PERCENT", "title", "10% off", "percent", 10, "months", 2,
                "audience", "ALL", "sector", "tours", "endsOn", end.toString()));
        assertThat(worse.status()).isEqualTo(200);
        assertThat(myPlan(owner, "GROWTH").get("pay").asLong()).isEqualTo(3500);
        // A business offer that is better (a set price of 3,000): that one wins.
        assertThat(post("/admin/offers", admin, Map.of("kind", "PRICE", "title", "Growth for 3,000", "plan", "GROWTH", "price", 3000,
                "audience", "ALL", "sector", "tours", "endsOn", end.toString())).status()).isEqualTo(200);
        JsonNode mine = myPlan(owner, "GROWTH");
        assertThat(mine.get("pay").asLong()).isEqualTo(3000);
        assertThat(mine.get("offer").asText()).isEqualTo("Growth for 3,000");
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY")).at("/amount").asLong()).isEqualTo(3000);
        // The shared public price is unchanged by one business's offer.
        assertThat(publicPlan("GROWTH").get("priceNow").asInt()).isEqualTo(3500);
    }

    @Test
    void anOfferBelowOneShillingIsRefusedAndAZeroPriceIsNeverRequested() {
        String admin = admin();
        assertThat(save(admin, offer(0, "Free month", null, null, true)).status()).isEqualTo(400);
        assertThat(publicPlan("GROWTH").get("priceNow").asInt()).isEqualTo(4500);
        // A business's own offer that makes the price 0 must not create a request or an M-Pesa prompt.
        String owner = token(signup("tours"));
        assertThat(post("/admin/offers", admin, Map.of("kind", "PRICE", "title", "Free", "plan", "LITE", "price", 0,
                "audience", "ALL", "sector", "tours", "endsOn", LocalDate.now().plusDays(5).toString())).status()).isEqualTo(200);
        int before = jdbc.queryForObject("SELECT count(*) FROM subscription_requests", Integer.class);
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY")).status()).isEqualTo(400);
        assertThat(post("/billing/mpesa", owner, Map.of("plan", "LITE", "cycle", "MONTHLY", "phone", "0712345678")).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_requests", Integer.class)).isEqualTo(before);
    }

    @Test
    void thePageMustAskForThePriceItShowed() {
        String admin = admin();
        save(admin, offer(3500, "December offer", null, null, true));
        String owner = token(signup("tours"));
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY", "expectedAmount", 3500)).status()).isEqualTo(200);
        // The offer ends while the page is open: the page still asks for 3,500.
        save(admin, offer(3500, "December offer", null, null, false));
        Res r = post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY", "expectedAmount", 3500));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.at("/amount").asLong()).isEqualTo(4500);
        assertThat(r.at("/message").asText()).contains("The price changed, here is the new price");
        assertThat(post("/billing/mpesa", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY", "phone", "0712345678", "expectedAmount", 3500)).status()).isEqualTo(409);
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY", "expectedAmount", 4500)).at("/amount").asLong()).isEqualTo(4500);
    }

    @Test
    void waitingRequestsExpireAfter48HoursAndANewOneUsesTheCurrentPrice() {
        String admin = admin();
        save(admin, offer(3500, "December offer", null, null, true));
        String owner = token(signup("tours"));
        UUID old = UUID.fromString(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY")).at("/requestId").asText());
        UUID fresh = UUID.fromString(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY")).at("/requestId").asText());
        jdbc.update("UPDATE subscription_requests SET created_at = now() - interval '49 hours' WHERE id = ?", old);
        save(admin, offer(3500, "December offer", null, null, false));
        Res again = post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY"));
        assertThat(again.at("/amount").asLong()).isEqualTo(4500);
        assertThat(jdbc.queryForObject("SELECT status FROM subscription_requests WHERE id = ?", String.class, old)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM subscription_requests WHERE id = ?", String.class, fresh)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT amount FROM subscription_requests WHERE id = ?::uuid", Long.class, again.at("/requestId").asText())).isEqualTo(4500);
    }

    @Test
    void savingTheSameOffersAgainWritesNoAuditEntry() {
        String admin = admin();
        save(admin, offer(3000, "Same", null, null, true));
        int n = jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE action = 'platform.plan_offers'", Integer.class);
        save(admin, offer(3000, "Same", null, null, true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE action = 'platform.plan_offers'", Integer.class)).isEqualTo(n);
    }
}
