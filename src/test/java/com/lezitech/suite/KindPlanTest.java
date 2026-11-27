package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.platform.PlatformSettings;
import com.lezitech.suite.settings.PlanActivation;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A plan of its own for one kind of business: Busynez Supply for suppliers to hotels and hospitals. */
class KindPlanTest extends IntegrationTest {

    static final String KIND = "wholesale/hotelSupplier";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlanActivation activation;
    @Autowired
    PlatformSettings platform;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM platform_settings WHERE key IN ('plans', 'planOffers', 'kindPlans')");
        jdbc.update("DELETE FROM tenant_price_holds");
        jdbc.update("DELETE FROM notice_jobs");
        platform.forget();
    }

    private JsonNode supply() {
        String email = "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        Res r = post("/auth/signup", null, Map.of("businessName", "Supply " + email.substring(6, 10), "sector", "wholesale", "subSector", "Supplier to hotels and hospitals",
                "name", "Owner", "email", email, "password", "password123", "withSamples", true));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    private String admin(String role) {
        return post("/admin/auth/demo", null, Map.of("role", role)).at("/token").asText();
    }

    private static String id(JsonNode s) {
        return s.at("/tenant/id").asText();
    }

    private String paid(JsonNode s, String plan) {
        jdbc.update("""
                UPDATE tenants SET status = 'ACTIVE', plan = ?, next_plan = NULL, plan_switch_at = NULL,
                       paid_until = now() + make_interval(days => 20), period_start = now() - make_interval(days => 10) WHERE id = ?::uuid""", plan, id(s));
        return token(s);
    }

    private void awaitJobs() {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until && jdbc.queryForObject("SELECT count(*) FROM notice_jobs WHERE done_at IS NULL", Long.class) > 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private JsonNode notices(String owner) {
        return get("/billing/notices", owner).body();
    }

    private Map<String, Object> kind(int monthly, Object offer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", "PRO");
        m.put("name", "Busynez Supply");
        m.put("monthly", monthly);
        m.put("blurb", "Everything a supplier needs");
        m.put("offer", offer);
        return m;
    }

    private Map<String, Object> offer(Object price, boolean on) {
        Map<String, Object> o = new HashMap<>();
        o.put("price", price);
        o.put("label", "Launch price");
        o.put("startsOn", null);
        o.put("endsOn", null);
        o.put("on", on);
        return o;
    }

    private Res saveKind(String admin, Map<String, Object> plan) {
        Res r = call("PUT", "/admin/platform-settings", admin, Map.of("kindPlans", Map.of(KIND, plan), "reason", "Test"));
        awaitJobs();
        return r;
    }

    private JsonNode plan(String owner, String code) {
        for (JsonNode p : get("/billing/plans", owner).body().get("plans")) {
            if (code.equals(p.path("code").asText())) {
                return p;
            }
        }
        throw new IllegalStateException("no " + code);
    }

    @Test
    void aSupplyBusinessSeesOnePlanAndOtherBusinessesSeeThree() {
        String owner = token(supply());
        JsonNode plans = get("/billing/plans", owner).body().get("plans");
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).get("code").asText()).isEqualTo("PRO");
        assertThat(plans.get(0).get("name").asText()).isEqualTo("Busynez Supply");
        assertThat(plans.get(0).get("pay").asLong()).isEqualTo(3000);
        assertThat(plans.get(0).get("price").asLong()).as("the crossed-out price").isEqualTo(4500);
        assertThat(plans.get(0).get("offerLabel").asText()).isEqualTo("Launch price");
        assertThat(get("/billing/keep", owner).at("/plan/name").asText()).isEqualTo("Busynez Supply");
        assertThat(get("/billing/keep", owner).at("/pay").asLong()).isEqualTo(3000);
        assertThat(get("/billing/keep", owner).at("/full").asLong()).isEqualTo(4500);

        JsonNode retail = get("/billing/plans", token(signup("retail"))).body().get("plans");
        assertThat(retail).hasSize(3);
        assertThat(retail.get(0).get("pay").asLong()).isEqualTo(2650);
        assertThat(retail.get(1).get("pay").asLong()).isEqualTo(4500);
        assertThat(retail.get(2).get("pay").asLong()).isEqualTo(9000);
    }

    @Test
    void subscribingActivatesProAtTheKindPriceAndOtherPlansAreRefused() {
        JsonNode s = supply();
        String owner = token(s);
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY")).status()).isEqualTo(400);
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY")).status()).isEqualTo(400);
        Res req = post("/billing/subscribe", owner, Map.of("plan", "PRO", "cycle", "MONTHLY", "expectedAmount", 3000));
        assertThat(req.status()).as(req.body().toString()).isEqualTo(200);
        assertThat(req.at("/amount").asLong()).isEqualTo(3000);
        activation.activate(UUID.fromString(req.at("/requestId").asText()), "MANUAL", null);
        assertThat(jdbc.queryForObject("SELECT plan FROM tenants WHERE id = ?::uuid", String.class, id(s))).isEqualTo("PRO");
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(3000);
        assertThat(post("/billing/plan-change", owner, Map.of("plan", "LITE")).status()).as("no other plan to move to").isEqualTo(400);
    }

    @Test
    void turningTheKindOfferOffMakesThePriceTheListPrice() {
        String owner = token(supply());
        String admin = admin("ADMIN");
        Res r = saveKind(admin, kind(4500, offer(3000, false)));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(plan(owner, "PRO").get("pay").asLong()).isEqualTo(4500);
        assertThat(plan(owner, "PRO").get("offerLabel").isNull()).isTrue();
        assertThat(get("/admin/platform-settings", admin).at("/kindPlans/" + KIND.replace("/", "~1") + "/monthly").asInt()).isEqualTo(4500);
    }

    @Test
    void theControlPanelChecksTheKindPlans() {
        String admin = admin("ADMIN");
        assertThat(saveKind(admin, kind(4500, offer(4500, true))).status()).as("offer not below the price").isEqualTo(400);
        assertThat(saveKind(admin, kind(4500, offer(5000, true))).status()).isEqualTo(400);
        assertThat(saveKind(admin, kind(4500, offer(0, true))).status()).isEqualTo(400);
        assertThat(saveKind(admin, kind(-1, null)).status()).isEqualTo(400);
        Map<String, Object> bad = kind(4500, null);
        bad.put("plan", "ULTRA");
        assertThat(saveKind(admin, bad).status()).isEqualTo(400);
        assertThat(call("PUT", "/admin/platform-settings", admin, Map.of("kindPlans", Map.of("wholesale/nothing", kind(4500, null)))).status()).isEqualTo(400);
        assertThat(saveKind(admin("DEVELOPER"), kind(4000, null)).status()).as("a developer cannot change prices").isEqualTo(403);
        assertThat(platform.kindPlans().get(KIND).get("monthly")).isEqualTo(4500);
    }

    @Test
    void aPlatformProPriceChangeLeavesASupplyBusinessAlone() {
        JsonNode supply = supply(), retail = signup("retail");
        String supplyOwner = paid(supply, "PRO"), retailOwner = paid(retail, "PRO");
        String admin = admin("ADMIN");
        List<Map<String, Object>> plans = new ArrayList<>();
        for (JsonNode p : get("/admin/platform-settings", admin).at("/plans")) {
            plans.add(new HashMap<>(Map.of("code", p.get("code").asText(), "name", p.get("name").asText(), "monthly", "PRO".equals(p.get("code").asText()) ? 10000 : p.get("monthly").asInt(),
                    "users", p.get("users").asInt(), "locations", p.get("locations").asInt(), "blurb", p.get("blurb").asText())));
        }
        Res r = call("PUT", "/admin/platform-settings", admin, Map.of("plans", plans, "reason", "Costs went up"));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        awaitJobs();
        assertThat(notices(retailOwner)).hasSize(1);
        assertThat(notices(supplyOwner)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_price_holds WHERE tenant_id = ?::uuid", Long.class, id(retail))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_price_holds WHERE tenant_id = ?::uuid", Long.class, id(supply))).isZero();
        assertThat(get("/billing/next-bill", supplyOwner).at("/total").asLong()).isEqualTo(3000);
    }

    @Test
    void aSupplyPriceChangeNotifiesAndHoldsOnlySupplyBusinesses() {
        JsonNode supply = supply(), retail = signup("retail");
        String supplyOwner = paid(supply, "PRO"), retailOwner = paid(retail, "PRO");
        String admin = admin("ADMIN");
        assertThat(get("/billing/next-bill", supplyOwner).at("/total").asLong()).isEqualTo(3000);

        Res r = saveKind(admin, kind(4500, offer(3000, false))); // the launch offer ends: 3,000 becomes 4,500
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(r.at("/told").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode n = notices(supplyOwner);
        assertThat(n).hasSize(1);
        assertThat(n.at("/0/body").asText()).contains("from KES 3,000 to KES 4,500 a month").contains("stays at the old price");
        assertThat(notices(retailOwner)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_price_holds WHERE tenant_id = ?::uuid", Long.class, id(supply))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_price_holds WHERE tenant_id = ?::uuid", Long.class, id(retail))).isZero();
        assertThat(get("/billing/next-bill", supplyOwner).at("/total").asLong()).as("held at the old price for the next bill").isEqualTo(3000);
        assertThat(get("/billing/next-bill", retailOwner).at("/total").asLong()).isEqualTo(9000);

        // Saving the same thing again tells no one a second time.
        assertThat(saveKind(admin, kind(4500, offer(3000, false))).at("/told").asInt()).isZero();
        assertThat(notices(supplyOwner)).hasSize(1);
    }

    @Test
    void anOwnerCannotMoveIntoOrOutOfSupplyButOtherChangesWork() {
        String supplyOwner = token(supply());
        assertThat(call("PUT", "/settings", supplyOwner, Map.of("subSector", "Distributor")).status()).as("out of Supply").isEqualTo(400);
        Res other = post("/auth/signup", null, Map.of("businessName", "Wholesale " + UUID.randomUUID().toString().substring(0, 4), "sector", "wholesale", "subSector", "Distributor",
                "name", "Owner", "email", "o-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local", "password", "password123", "withSamples", true));
        assertThat(other.status()).isEqualTo(200);
        String owner = token(other.body());
        assertThat(call("PUT", "/settings", owner, Map.of("subSector", "Supplier to hotels and hospitals")).status()).as("into Supply").isEqualTo(400);
        assertThat(call("PUT", "/settings", owner, Map.of("subSector", "Manufacturer")).status()).as("no kind plan involved").isEqualTo(200);
    }

    @Test
    void aSupplyBusinessPayingOnAnotherPlanKeepsItAtItsNormalPriceUntilItMoves() {
        JsonNode s = supply();
        String owner = paid(s, "LITE");
        JsonNode plans = get("/billing/plans", owner).body().get("plans");
        assertThat(plans).hasSize(2);
        assertThat(plan(owner, "LITE").get("pay").asLong()).isEqualTo(2650);
        assertThat(plan(owner, "PRO").get("pay").asLong()).isEqualTo(3000);
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(2650);
        assertThat(get("/billing/keep", owner).at("/pay").asLong()).isEqualTo(2650);
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY")).status()).isEqualTo(400);
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY")).at("/amount").asLong()).isEqualTo(2650);
        // An administrator may not put it on a plan that is neither its own nor the kind plan, but may move it to the kind plan.
        String admin = admin("ADMIN");
        assertThat(call("POST", "/admin/tenants/" + id(s) + "/plan-change", admin, Map.of("plan", "GROWTH", "reason", "Test")).status()).isEqualTo(400);
        assertThat(post("/billing/plan-change", owner, Map.of("plan", "PRO")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT plan FROM tenants WHERE id = ?::uuid", String.class, id(s))).isEqualTo("PRO");
        assertThat(get("/billing/plans", owner).body().get("plans")).hasSize(1);
        assertThat(plan(owner, "PRO").get("pay").asLong()).isEqualTo(3000);
    }

    @Test
    void aMoveDownChosenBeforeCanStillBePaid() {
        JsonNode s = supply();
        String owner = paid(s, "GROWTH");
        jdbc.update("UPDATE tenants SET next_plan = 'LITE' WHERE id = ?::uuid", id(s));
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY")).status()).isEqualTo(200);
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY")).status()).isNotEqualTo(400);
    }

    @Test
    void aKindPlanInUseCannotBeRemovedOrGetAnotherPlanCode() {
        supply();
        String admin = admin("ADMIN");
        Res gone = call("PUT", "/admin/platform-settings", admin, Map.of("kindPlans", Map.of()));
        assertThat(gone.status()).isEqualTo(400);
        assertThat(gone.body().toString()).contains("businesses are on the plan for Busynez Supply");
        Map<String, Object> other = kind(4500, null);
        other.put("plan", "GROWTH");
        assertThat(saveKind(admin, other).status()).isEqualTo(400);
        assertThat(platform.kindPlans()).containsKey(KIND);
    }
}
