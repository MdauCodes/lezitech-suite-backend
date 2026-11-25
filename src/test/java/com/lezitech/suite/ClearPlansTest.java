package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.TrialNudgeService;
import com.lezitech.suite.platform.PlatformSettings;
import com.lezitech.suite.settings.PlanActivation;
import com.lezitech.suite.settings.PlanBilling;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plans explained clearly: names for each kind of business, the sheet before moving up, proration on the next bill,
 * moving down at the next bill date, the next bill equal to what is charged, reminders, and the days of grace.
 */
class ClearPlansTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlanBilling billing;
    @Autowired
    PlanActivation activation;
    @Autowired
    TrialNudgeService nudges;
    @Autowired
    PlatformSettings platform;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM platform_settings WHERE key IN ('planOffers', 'sectorPlanNames')");
        jdbc.update("UPDATE platform_offers SET active = false WHERE sector = 'services'");
        platform.forget();
    }

    private String admin(String role) {
        return post("/admin/auth/demo", null, Map.of("role", role)).at("/token").asText();
    }

    /** A paying business on a plan with this many days left in a period of this many days. */
    private String paid(JsonNode s, String plan, int daysLeft, int periodDays) {
        jdbc.update("""
                UPDATE tenants SET status = 'ACTIVE', plan = ?, next_plan = NULL, plan_switch_at = NULL,
                       paid_until = now() + make_interval(days => ?),
                       period_start = now() + make_interval(days => ?) - make_interval(days => ?) WHERE id = ?::uuid""",
                plan, daysLeft, daysLeft, periodDays, s.at("/tenant/id").asText());
        return token(s);
    }

    private long lines(String tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM bill_lines WHERE tenant_id = ?::uuid", Long.class, tenant);
    }

    // ---------- the maths ----------

    @Test
    void prorationMathsToTheWholeShilling() {
        // (4500 - 2650) x 13 / 30 = 801.67
        assertThat(PlanBilling.prorate(2650, 4500, 13, 30)).isEqualTo(802);
        // First day of the period: the whole difference. Last day: one day's worth. On the bill date: nothing.
        assertThat(PlanBilling.prorate(2650, 4500, 30, 30)).isEqualTo(1850);
        assertThat(PlanBilling.prorate(2650, 4500, 1, 30)).isEqualTo(62);
        assertThat(PlanBilling.prorate(2650, 4500, 0, 30)).isEqualTo(0);
        // Never more than the period, never for a cheaper plan.
        assertThat(PlanBilling.prorate(2650, 4500, 40, 30)).isEqualTo(1850);
        assertThat(PlanBilling.prorate(4500, 2650, 13, 30)).isEqualTo(0);
        // A 31 day month.
        assertThat(PlanBilling.prorate(2650, 4500, 15, 31)).isEqualTo(895);
    }

    @Test
    void movingUpMidPeriodMovesThePlanNowAndAddsALineToTheNextBill() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 13, 30);
        String tenant = s.at("/tenant/id").asText();

        // The sheet says it all before anything changes.
        JsonNode sheet = get("/billing/plan-change/preview?feature=purchasing", owner).body();
        assertThat(sheet.at("/needed").asBoolean()).isTrue();
        assertThat(sheet.at("/message").asText()).contains("Buying from suppliers is part of Busy Shop.")
                .contains("Busy Shop is KES 4,500 a month (you are on Corner Shop, KES 2,650).")
                .contains("Nothing to pay today. Your plan changes now.")
                .contains("will be KES 4,500 for the next month, plus KES 802 for the 13 days of Busy Shop this month.");
        assertThat(sheet.at("/yes").asText()).isEqualTo("Yes, move me to Busy Shop");
        assertThat(sheet.at("/no").asText()).isEqualTo("Not now");
        assertThat(jdbc.queryForObject("SELECT plan FROM tenants WHERE id = ?::uuid", String.class, tenant)).isEqualTo("LITE");

        Res moved = post("/billing/plan-change", owner, Map.of("plan", "GROWTH", "reason", "Buying from suppliers"));
        assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);
        assertThat(moved.at("/proration").asLong()).isEqualTo(802);
        assertThat(jdbc.queryForObject("SELECT plan FROM tenants WHERE id = ?::uuid", String.class, tenant)).isEqualTo("GROWTH");

        // Who, when, from and to.
        Map<String, Object> change = jdbc.queryForMap("SELECT * FROM plan_changes WHERE tenant_id = ?::uuid", tenant);
        assertThat(change.get("from_plan")).isEqualTo("LITE");
        assertThat(change.get("to_plan")).isEqualTo("GROWTH");
        assertThat(change.get("kind")).isEqualTo("UPGRADE");
        assertThat(change.get("changed_by_name")).isNotNull();
        assertThat(change.get("created_at")).isNotNull();

        // Asking again changes nothing and adds nothing.
        assertThat(post("/billing/plan-change", owner, Map.of("plan", "GROWTH")).status()).isEqualTo(400);
        assertThat(lines(tenant)).isEqualTo(1);

        JsonNode bill = get("/billing/next-bill", owner).body();
        assertThat(bill.at("/planName").asText()).isEqualTo("Busy Shop");
        assertThat(bill.at("/lines/0/amount").asLong()).isEqualTo(4500);
        assertThat(bill.at("/lines/1/kind").asText()).isEqualTo("PRORATION");
        assertThat(bill.at("/lines/1/amount").asLong()).isEqualTo(802);
        assertThat(bill.at("/total").asLong()).isEqualTo(5302);
        assertThat(bill.at("/summary").asText()).contains("Nothing to pay today.").contains("KES 5,302");
    }

    @Test
    void firstDayLastDayAndBillDay() {
        for (int[] c : new int[][]{{30, 30, 1850}, {1, 30, 62}, {0, 30, 0}}) {
            JsonNode s = signup("retail");
            String owner = paid(s, "LITE", c[0], c[1]);
            Res r = post("/billing/plan-change", owner, Map.of("plan", "GROWTH"));
            assertThat(r.at("/proration").asLong()).as("days left " + c[0]).isEqualTo(c[2]);
            assertThat(lines(s.at("/tenant/id").asText())).isEqualTo(c[2] == 0 ? 0 : 1);
        }
    }

    @Test
    void aPlanOfferLowersTheDifference() {
        String admin = admin("ADMIN");
        Map<String, Object> offer = new LinkedHashMap<>();
        offer.put("price", 3500);
        offer.put("label", "Growth for 3,500");
        offer.put("startsOn", LocalDate.now().minusDays(1).toString());
        offer.put("endsOn", LocalDate.now().plusDays(10).toString());
        offer.put("on", true);
        assertThat(call("PUT", "/admin/platform-settings", admin, Map.of("planOffers", Map.of("GROWTH", offer))).status()).isEqualTo(200);
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 13, 30);
        // (3,500 - 2,650) x 13 / 30 = 368.33
        assertThat(post("/billing/plan-change", owner, Map.of("plan", "GROWTH")).at("/proration").asLong()).isEqualTo(368);
        JsonNode bill = get("/billing/next-bill", owner).body();
        assertThat(bill.at("/pay").asLong()).isEqualTo(3500);
        assertThat(bill.at("/total").asLong()).isEqualTo(3500 + 368);
        assertThat(bill.at("/lines/1/kind").asText()).isEqualTo("OFFER");
        assertThat(bill.at("/lines/1/amount").asLong()).isEqualTo(-1000);
    }

    @Test
    void aBusinessOwnOfferLowersTheDifferenceToo() {
        String admin = admin("ADMIN");
        assertThat(post("/admin/offers", admin, Map.of("kind", "PRICE", "title", "Team for KES 3,000", "plan", "GROWTH", "price", 3000,
                "audience", "PAYING", "sector", "services", "endsOn", LocalDate.now().plusDays(5).toString())).status()).isEqualTo(200);
        JsonNode s = signup("services");
        String owner = paid(s, "LITE", 13, 30);
        // (3,000 - 2,650) x 13 / 30 = 151.67
        assertThat(post("/billing/plan-change", owner, Map.of("plan", "GROWTH")).at("/proration").asLong()).isEqualTo(152);
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(3000 + 152);
    }

    // ---------- the free days ----------

    @Test
    void duringTheFreeDaysNothingIsAdded() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String tenant = s.at("/tenant/id").asText();
        jdbc.update("UPDATE tenants SET settings = jsonb_set(jsonb_set(settings, '{chosenTools}', '[]'), '{modules}', '[\"pos\"]') WHERE id = ?::uuid", tenant);

        JsonNode sheet = get("/billing/plan-change/preview?feature=purchasing", owner).body();
        assertThat(sheet.at("/trial").asBoolean()).isTrue();
        assertThat(sheet.at("/message").asText()).contains("When your free days end on")
                .contains("this setup is Busy Shop: KES 4,500 a month.").contains("Nothing to pay today.");

        Res r = post("/billing/plan-change", owner, Map.of("plan", "GROWTH", "reason", "Buying from suppliers"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.at("/kind").asText()).isEqualTo("TRIAL_SETUP");
        assertThat(r.at("/proration").asLong()).isEqualTo(0);
        assertThat(lines(tenant)).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?::uuid", String.class, tenant)).isEqualTo("TRIAL");
        JsonNode bill = get("/billing/next-bill", owner).body();
        assertThat(bill.at("/trial").asBoolean()).isTrue();
        assertThat(bill.at("/lines").size()).isEqualTo(1);
        assertThat(bill.at("/summary").asText()).startsWith("Nothing to pay today.");
    }

    @Test
    void onlyTheOwnerMovesThePlan() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 13, 30);
        String cashier = staff(owner, "CASHIER");
        assertThat(post("/billing/plan-change", cashier, Map.of("plan", "GROWTH")).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT plan FROM tenants WHERE id = ?::uuid", String.class, s.at("/tenant/id").asText())).isEqualTo("LITE");
    }

    // ---------- moving down ----------

    @Test
    void movingDownWaitsForTheNextBillDate() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String owner = paid(s, "GROWTH", 20, 30);

        JsonNode sheet = get("/billing/plan-change/preview?toPlan=LITE", owner).body();
        assertThat(sheet.at("/message").asText()).contains("starts on your next bill date")
                .contains("Until then you keep Busy Shop").contains("Nothing is refunded for the days you have already paid for.");

        Res r = post("/billing/plan-change", owner, Map.of("plan", "LITE"));
        assertThat(r.at("/kind").asText()).isEqualTo("DOWNGRADE");
        assertThat(r.at("/message").asText()).contains("Nothing is refunded");
        // Still on the bigger plan, with its tools, until the date.
        Map<String, Object> t = jdbc.queryForMap("SELECT plan, next_plan FROM tenants WHERE id = ?::uuid", tenant);
        assertThat(t.get("plan")).isEqualTo("GROWTH");
        assertThat(t.get("next_plan")).isEqualTo("LITE");
        assertThat(lines(tenant)).isEqualTo(0);
        JsonNode bill = get("/billing/next-bill", owner).body();
        assertThat(bill.at("/planName").asText()).isEqualTo("Corner Shop");
        assertThat(bill.at("/total").asLong()).isEqualTo(2650);

        // Paying the next bill early keeps the plan until the date, then the job switches it.
        Res sub = post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY", "expectedAmount", 2650));
        assertThat(sub.status()).as(sub.body().toString()).isEqualTo(200);
        activation.activate(UUID.fromString(sub.at("/requestId").asText()), "MANUAL", null);
        Map<String, Object> after = jdbc.queryForMap("SELECT plan, next_plan, plan_switch_at FROM tenants WHERE id = ?::uuid", tenant);
        assertThat(after.get("plan")).isEqualTo("GROWTH");
        assertThat(after.get("next_plan")).isEqualTo("LITE");
        assertThat(after.get("plan_switch_at")).isNotNull();
        assertThat(billing.applyScheduledMoves()).isEqualTo(0);
        jdbc.update("UPDATE tenants SET plan_switch_at = now() - interval '1 minute' WHERE id = ?::uuid", tenant);
        assertThat(billing.applyScheduledMoves()).isGreaterThanOrEqualTo(1);
        Map<String, Object> done = jdbc.queryForMap("SELECT plan, next_plan FROM tenants WHERE id = ?::uuid", tenant);
        assertThat(done.get("plan")).isEqualTo("LITE");
        assertThat(done.get("next_plan")).isNull();
    }

    @Test
    void stayingCancelsAMoveDownAndPayingAfterTheDateSwitchesAtOnce() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String owner = paid(s, "GROWTH", 20, 30);
        post("/billing/plan-change", owner, Map.of("plan", "LITE"));
        Res stay = post("/billing/plan-change", owner, Map.of("plan", "GROWTH"));
        assertThat(stay.at("/kind").asText()).as(stay.body().toString()).isEqualTo("CANCEL_DOWNGRADE");
        assertThat(jdbc.queryForObject("SELECT next_plan FROM tenants WHERE id = ?::uuid", String.class, tenant)).isNull();

        post("/billing/plan-change", owner, Map.of("plan", "LITE"));
        jdbc.update("UPDATE tenants SET paid_until = now() - interval '1 day' WHERE id = ?::uuid", tenant);
        Res sub = post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY"));
        activation.activate(UUID.fromString(sub.at("/requestId").asText()), "MANUAL", null);
        assertThat(jdbc.queryForObject("SELECT plan FROM tenants WHERE id = ?::uuid", String.class, tenant)).isEqualTo("LITE");
    }

    // ---------- the bill equals the charge ----------

    @Test
    void theNextBillTotalIsWhatIsChargedAndPayingMarksTheLinesPaid() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String owner = paid(s, "LITE", 13, 30);
        post("/billing/plan-change", owner, Map.of("plan", "GROWTH"));
        long shown = get("/billing/next-bill", owner).at("/total").asLong();
        assertThat(shown).isEqualTo(5302);

        // The price changed since the page loaded: a 409 with the new total.
        Res stale = post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY", "expectedAmount", 4500));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.at("/amount").asLong()).isEqualTo(5302);
        // Another plan than the one the bill is for must be moved to first.
        assertThat(post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY")).at("/code").asText()).isEqualTo("MOVE_FIRST");

        Res sub = post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY", "expectedAmount", shown));
        assertThat(sub.status()).as(sub.body().toString()).isEqualTo(200);
        assertThat(sub.at("/amount").asLong()).isEqualTo(shown);
        UUID request = UUID.fromString(sub.at("/requestId").asText());
        assertThat(jdbc.queryForObject("SELECT amount FROM subscription_requests WHERE id = ?", Long.class, request)).isEqualTo(shown);
        // The control panel sees the same lines.
        assertThat(jdbc.queryForObject("SELECT lines::text FROM subscription_requests WHERE id = ?", String.class, request))
                .contains("Busy Shop for the next month").contains("13 days of Busy Shop this month");
        assertThat(lines(tenant)).isEqualTo(1);

        activation.activate(request, "MANUAL", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_lines WHERE tenant_id = ?::uuid AND paid_at IS NOT NULL AND paid_request_id = ?",
                Long.class, tenant, request)).isEqualTo(1);
        JsonNode next = get("/billing/next-bill", owner).body();
        assertThat(next.at("/total").asLong()).isEqualTo(4500);
        assertThat(next.at("/lines").size()).isEqualTo(1);
    }

    @Test
    void theControlPanelSeesTheSameLines() {
        JsonNode s = signup("restaurant");
        String owner = paid(s, "LITE", 13, 30);
        post("/billing/plan-change", owner, Map.of("plan", "GROWTH", "reason", "Staff"));
        Res sub = post("/billing/subscribe", owner, Map.of("plan", "GROWTH", "cycle", "MONTHLY"));
        String admin = admin("ADMIN");
        JsonNode queue = get("/admin/requests", admin).body();
        JsonNode mine = null;
        for (JsonNode r : queue) {
            if (sub.at("/requestId").asText().equals(r.get("id").asText())) {
                mine = r;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.at("/planName").asText()).isEqualTo("Busy Kitchen");
        assertThat(mine.at("/plan").asText()).isEqualTo("GROWTH");
        assertThat(mine.at("/lines/0/label").asText()).isEqualTo("Busy Kitchen for the next month");
        assertThat(mine.at("/lines/1/kind").asText()).isEqualTo("PRORATION");
        JsonNode detail = get("/admin/tenants/" + s.at("/tenant/id").asText(), admin).body();
        assertThat(detail.at("/planName").asText()).isEqualTo("Busy Kitchen");
        assertThat(detail.at("/nextBill/total").asLong()).isEqualTo(sub.at("/amount").asLong());
        assertThat(detail.at("/planChanges/0/toName").asText()).isEqualTo("Busy Kitchen");
        assertThat(detail.at("/planChanges/0/by").asText()).isNotBlank();
    }

    // ---------- reminders, grace, lock ----------

    @Test
    void remindersGoOutOncePerBillInPlainWords() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 3, 30);
        String email = s.get("email").asText();
        EmailSender.LogSender log = new EmailSender.LogSender();
        nudges.useSender(log);

        nudges.runBills();
        nudges.runBills();
        List<EmailSender.Email> mine = log.sent.stream().filter(e -> e.to().equals(email)).toList();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).subject()).contains("Your Busynez bill on").contains("KES 2,650");
        assertThat(mine.get(0).text()).contains("Corner Shop for the next month: KES 2,650").contains("/billing").contains("3 days after the bill date");
        assertThat(mine.get(0).text().toLowerCase()).doesNotContainPattern("\\bdue\\b").doesNotContainPattern("\\bowe\\b");

        // On the day itself, once.
        jdbc.update("UPDATE tenants SET paid_until = now() WHERE id = ?::uuid", s.at("/tenant/id").asText());
        nudges.runBills();
        nudges.runBills();
        List<EmailSender.Email> day = log.sent.stream().filter(e -> e.to().equals(email)).toList();
        assertThat(day).hasSize(2);
        assertThat(day.get(1).subject()).contains("Your Busynez bill is today");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_reminders WHERE tenant_id = ?::uuid AND email_status = 'SENT'", Long.class,
                s.at("/tenant/id").asText())).isEqualTo(2);
        assertThat(owner).isNotBlank();
    }

    @Test
    void threeDaysOfGraceWithABannerThenTheLock() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String owner = paid(s, "LITE", 30, 30);
        String sugar = item(owner, "Sugar").get("id").asText();

        // Bill date was yesterday: still working, with the banner.
        jdbc.update("UPDATE tenants SET paid_until = now() - interval '1 day' WHERE id = ?::uuid", tenant);
        JsonNode me = get("/auth/me", owner).body();
        assertThat(me.at("/tenant/inGrace").asBoolean()).isTrue();
        assertThat(me.at("/tenant/locked").asBoolean()).isFalse();
        assertThat(me.at("/tenant/graceEndsAt").asText()).isNotBlank();
        assertThat(post("/orders", owner, Map.of("lines", line(sugar, 1))).status()).isEqualTo(200);
        JsonNode bill = get("/billing/next-bill", owner).body();
        assertThat(bill.at("/late").asBoolean()).isTrue();
        assertThat(bill.at("/inGrace").asBoolean()).isTrue();
        assertThat(bill.at("/summary").asText()).contains("Pay KES 2,650 by");

        // Four days past the date: changes are refused, reading and exporting still work.
        jdbc.update("UPDATE tenants SET paid_until = now() - interval '4 days' WHERE id = ?::uuid", tenant);
        Res write = post("/orders", owner, Map.of("lines", line(sugar, 1)));
        assertThat(write.status()).isEqualTo(402);
        assertThat(write.at("/message").asText()).contains("You can still look at everything");
        assertThat(get("/auth/me", owner).at("/tenant/locked").asBoolean()).isTrue();
        assertThat(get("/orders", owner).status()).isEqualTo(200);
        assertThat(get("/billing/next-bill", owner).at("/locked").asBoolean()).isTrue();
    }

    // ---------- the names ----------

    @Test
    void plansHaveTheNameOfTheKindOfBusiness() {
        Map<String, String[]> expected = Map.of(
                "restaurant", new String[]{"Starter Kitchen", "Busy Kitchen", "Kitchen Group"},
                "hotel", new String[]{"Guest House", "Full House", "Hotel Group"},
                "retail", new String[]{"Corner Shop", "Busy Shop", "Shop Chain"},
                "beauty", new String[]{"Salon Chair", "Busy Salon", "Salon Group"},
                "wholesale", new String[]{"Stockist", "Distributor", "Distribution Network"},
                "services", new String[]{"Solo", "Team", "Firm"},
                "tours", new String[]{"Trips", "Tour Company", "Tour Group"},
                "property", new String[]{"Landlord", "Property Manager", "Portfolio"},
                "pharmacy", new String[]{"Lite", "Growth", "Pro"});
        for (var e : expected.entrySet()) {
            JsonNode plans = get("/public/plans?sector=" + e.getKey(), null).body().get("plans");
            for (int i = 0; i < 3; i++) {
                assertThat(plans.get(i).get("name").asText()).as(e.getKey()).isEqualTo(e.getValue()[i]);
                assertThat(plans.get(i).get("code").asText()).isEqualTo(new String[]{"LITE", "GROWTH", "PRO"}[i]);
            }
        }
        // No kind of business given: the plans' own names, prices the same for everyone.
        JsonNode plain = get("/public/plans", null).body().get("plans");
        assertThat(plain.get(1).get("name").asText()).isEqualTo("Growth");
        assertThat(plain.get(1).get("monthly").asInt()).isEqualTo(4500);

        // A business sees its own names in its plan list, "keep running" and the refusal.
        JsonNode s = signup("restaurant");
        String owner = paid(s, "LITE", 20, 30);
        JsonNode mine = get("/billing/plans", owner).body().get("plans");
        assertThat(mine.get(0).get("name").asText()).isEqualTo("Starter Kitchen");
        assertThat(get("/billing/keep", owner).at("/plan/name").asText()).isIn("Starter Kitchen", "Busy Kitchen", "Kitchen Group");
        assertThat(get("/auth/me", owner).at("/tenant/planName").asText()).isEqualTo("Starter Kitchen");
        Res refused = post("/purchases", owner, Map.of());
        assertThat(refused.at("/message").asText()).contains("Starter Kitchen").contains("Busy Kitchen");
    }

    @Test
    void editingTheNamesNeedsTheSectorsPermission() {
        String support = admin("SUPPORT");
        Res no = call("PUT", "/admin/sectors/restaurant/plan-names", support, Map.of("LITE", "Hotplate"));
        assertThat(no.status()).isEqualTo(403);
        assertThat(get("/public/plans?sector=restaurant", null).body().at("/plans/0/name").asText()).isEqualTo("Starter Kitchen");

        String boss = admin("ADMIN");
        Res ok = call("PUT", "/admin/sectors/restaurant/plan-names", boss, Map.of("LITE", "Hotplate", "GROWTH", "", "PRO", "Kitchen Group"));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        JsonNode plans = get("/public/plans?sector=restaurant", null).body().get("plans");
        assertThat(plans.get(0).get("name").asText()).isEqualTo("Hotplate");
        assertThat(plans.get(1).get("name").asText()).isEqualTo("Busy Kitchen");
        // The control panel shows the name and the code.
        JsonNode list = get("/admin/sectors", boss).body();
        for (JsonNode row : list) {
            if ("restaurant".equals(row.get("key").asText())) {
                assertThat(row.at("/planNames/0/code").asText()).isEqualTo("LITE");
                assertThat(row.at("/planNames/0/name").asText()).isEqualTo("Hotplate");
                assertThat(row.at("/planNames/0/default").asText()).isEqualTo("Starter Kitchen");
            }
        }
        // Two plans with the same name would be confusing.
        assertThat(call("PUT", "/admin/sectors/restaurant/plan-names", boss, Map.of("LITE", "Same", "GROWTH", "Same")).status()).isEqualTo(400);
        // A blank name goes back to the default.
        call("PUT", "/admin/sectors/restaurant/plan-names", boss, Map.of());
        assertThat(get("/public/plans?sector=restaurant", null).body().at("/plans/0/name").asText()).isEqualTo("Starter Kitchen");
    }

    // ---------- review fixes ----------

    @Autowired
    com.lezitech.suite.mpesa.MpesaService mpesa;

    private UUID owner(String tenant) {
        return jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ?::uuid AND role = 'OWNER'", UUID.class, tenant);
    }

    @Test
    void movingUpClosesWaitingRequestsAndAnOldOneNeverSetsThePlanBack() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String ownerTok = paid(s, "LITE", 13, 30);
        UUID old = UUID.fromString(post("/billing/subscribe", ownerTok, Map.of("plan", "LITE", "cycle", "MONTHLY")).at("/requestId").asText());
        post("/billing/plan-change", ownerTok, Map.of("plan", "GROWTH"));
        assertThat(jdbc.queryForObject("SELECT status FROM subscription_requests WHERE id = ?", String.class, old)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT note FROM subscription_requests WHERE id = ?", String.class, old)).contains("plan changed");

        // No M-Pesa prompt for the closed request; the answer carries the new amount.
        var refused = org.assertj.core.api.Assertions.catchThrowable(() ->
                com.lezitech.suite.tenant.TenantContext.runAs(UUID.fromString(tenant), owner(tenant), () -> mpesa.startSubscriptionPayment(old, "0712345678")));
        assertThat(refused).isInstanceOf(com.lezitech.suite.common.ApiException.class).hasMessageContaining("out of date").hasMessageContaining("5,302");

        // Even if its money arrives by hand, the plan stays on Growth and the extra line stays on the bill.
        activation.activate(old, "MANUAL", null);
        assertThat(jdbc.queryForObject("SELECT plan FROM tenants WHERE id = ?::uuid", String.class, tenant)).isEqualTo("GROWTH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_lines WHERE tenant_id = ?::uuid AND paid_at IS NULL", Long.class, tenant)).isEqualTo(1);
    }

    @Test
    void aWaitingRequestThatNoLongerMatchesTheBillIsRefused() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String ownerTok = paid(s, "LITE", 13, 30);
        UUID req = UUID.fromString(post("/billing/subscribe", ownerTok, Map.of("plan", "LITE", "cycle", "MONTHLY")).at("/requestId").asText());
        jdbc.update("INSERT INTO bill_lines (tenant_id, kind, label, amount) VALUES (?::uuid, 'PRORATION', 'extra', 100)", tenant);
        var refused = org.assertj.core.api.Assertions.catchThrowable(() ->
                com.lezitech.suite.tenant.TenantContext.runAs(UUID.fromString(tenant), owner(tenant), () -> mpesa.startSubscriptionPayment(req, "0712345678")));
        assertThat(refused).hasMessageContaining("out of date").hasMessageContaining("2,750");
    }

    @Test
    void stayingAfterPayingTheLowerPlanAheadAddsTheDifference() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String ownerTok = paid(s, "GROWTH", 20, 30);
        post("/billing/plan-change", ownerTok, Map.of("plan", "LITE"));
        Res sub = post("/billing/subscribe", ownerTok, Map.of("plan", "LITE", "cycle", "MONTHLY"));
        activation.activate(UUID.fromString(sub.at("/requestId").asText()), "MANUAL", null);

        // The sheet says it before they confirm.
        JsonNode sheet = get("/billing/plan-change/preview?toPlan=GROWTH", ownerTok).body();
        assertThat(sheet.at("/message").asText()).contains("already paid the next period at Corner Shop").contains("KES 1,850");
        Res stay = post("/billing/plan-change", ownerTok, Map.of("plan", "GROWTH"));
        assertThat(stay.status()).isEqualTo(200);
        assertThat(stay.at("/proration").asLong()).isEqualTo(1850);
        JsonNode bill = get("/billing/next-bill", ownerTok).body();
        assertThat(bill.at("/total").asLong()).isEqualTo(4500 + 1850);
        assertThat(bill.at("/lines/1/label").asText()).contains("Busy Shop instead of Corner Shop");
        assertThat(jdbc.queryForObject("SELECT plan FROM tenants WHERE id = ?::uuid", String.class, tenant)).isEqualTo("GROWTH");
    }

    @Test
    void aLineIsSettledOnceEvenIfTwoRequestsIncludedIt() {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String ownerTok = paid(s, "LITE", 13, 30);
        post("/billing/plan-change", ownerTok, Map.of("plan", "GROWTH"));
        UUID a = UUID.fromString(post("/billing/subscribe", ownerTok, Map.of("plan", "GROWTH", "cycle", "MONTHLY")).at("/requestId").asText());
        UUID b = UUID.fromString(post("/billing/subscribe", ownerTok, Map.of("plan", "GROWTH", "cycle", "MONTHLY")).at("/requestId").asText());
        activation.activate(a, "MANUAL", null);
        activation.activate(b, "MANUAL", null);
        assertThat(jdbc.queryForObject("SELECT paid_request_id FROM bill_lines WHERE tenant_id = ?::uuid", UUID.class, tenant)).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_lines WHERE tenant_id = ?::uuid AND paid_request_id = ?", Long.class, tenant, b)).isEqualTo(0);
    }

    @Test
    void operatorActivationSettlesTheLinesToo() throws Exception {
        JsonNode s = signup("retail");
        String tenant = s.at("/tenant/id").asText();
        String ownerTok = paid(s, "LITE", 13, 30);
        post("/billing/plan-change", ownerTok, Map.of("plan", "GROWTH"));
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/platform/tenants/" + tenant + "/activate"))
                .header("Content-Type", "application/json").header("X-Operator-Key", "local-operator-key")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"plan\":\"GROWTH\",\"months\":1}")).build();
        assertThat(java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_lines WHERE tenant_id = ?::uuid AND paid_at IS NULL", Long.class, tenant)).isEqualTo(0);
    }

    @Test
    void theSheetOpensForStaffAttendanceOnLite() {
        JsonNode s = signup("restaurant");
        String owner = paid(s, "LITE", 13, 30);
        JsonNode sheet = get("/billing/plan-change/preview?feature=attendance", owner).body();
        assertThat(sheet.at("/needed").asBoolean()).isTrue();
        assertThat(sheet.at("/to").asText()).isEqualTo("GROWTH");
        assertThat(sheet.at("/message").asText()).contains("Staff attendance is part of Busy Kitchen.").contains("Nothing to pay today.");
        // The refusal on a Lite plan names the tool, so the app opens this sheet by itself.
        Res refused = post("/attendance/clock", owner, Map.of("kind", "IN"));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.at("/feature").asText()).isEqualTo("attendance");
    }
}
