package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.platform.PlatformSettings;
import com.lezitech.suite.settings.PlanActivation;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oversight of every business from the control panel: the money summary equals the business's own next bill, payments and requests
 * history, every action audited and checked against the staff role, and the business told about every change (in the app, by email).
 */
class BusinessOversightTest extends IntegrationTest {

    @Autowired
    com.lezitech.suite.admin.OversightActions actions;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlanActivation activation;
    @Autowired
    PlatformSettings platform;

    EmailSender.LogSender box;

    @BeforeEach
    void capture() {
        box = new EmailSender.LogSender();
        itMailer.useSender(box);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM platform_settings WHERE key IN ('plans', 'planOffers')");
        jdbc.update("UPDATE platform_offers SET active = false WHERE tenant_id IS NULL AND sector = 'retail'");
        jdbc.update("DELETE FROM tenant_price_holds");
        jdbc.update("DELETE FROM notice_jobs");
        platform.forget();
    }

    /** Notices for a price change or an ending offer are sent by a background job: wait until it has finished. */
    private void awaitJobs() {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until
                && jdbc.queryForObject("SELECT count(*) FROM notice_jobs WHERE done_at IS NULL", Long.class) > 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notice_jobs WHERE done_at IS NULL", Long.class)).as("notice jobs finished").isZero();
    }

    private Res put(String path, String admin, Object body) {
        Res r = call("PUT", path, admin, body);
        awaitJobs();
        return r;
    }

    private int operator(String path, String json) {
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/platform/tenants/" + path))
                    .header("Content-Type", "application/json").header("X-Operator-Key", "local-operator-key")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)).build();
            return java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private static String id(JsonNode s) {
        return s.at("/tenant/id").asText();
    }

    private Map<String, Object> with(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private long audited(String tenant, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM platform_audit WHERE tenant_id = ?::uuid AND action = ?", Long.class, tenant, action);
    }

    private String auditReason(String tenant, String action) {
        return jdbc.queryForObject("SELECT detail->>'reason' FROM platform_audit WHERE tenant_id = ?::uuid AND action = ? ORDER BY created_at DESC LIMIT 1", String.class, tenant, action);
    }

    private List<EmailSender.Email> notices(String to) {
        return box.sent.stream().filter(e -> e.to().equals(to) && e.subject().startsWith("Busynez: ")).toList();
    }

    private JsonNode myNotices(String owner, boolean unseen) {
        return get("/billing/notices?unseen=" + unseen, owner).body();
    }

    private String tomorrow() {
        return LocalDate.now().plusDays(20).toString();
    }

    // ---------- the money summary ----------

    @Test
    void theMoneySummaryIsTheBusinessesOwnNextBill() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 13, 30);
        String t = id(s), admin = admin("SUPPORT");
        post("/billing/plan-change", owner, Map.of("plan", "GROWTH"));

        JsonNode money = get("/admin/tenants/" + t + "/money", admin).body();
        JsonNode theirs = get("/billing/next-bill", owner).body();
        assertThat(money.at("/nextBill")).isEqualTo(theirs);
        assertThat(money.at("/nextBill/total").asLong()).isEqualTo(5302);
        assertThat(money.at("/planLabel").asText()).isEqualTo("Busy Shop (Growth)");
        assertThat(money.at("/priceNow").asLong()).isEqualTo(4500);
        assertThat(money.at("/priceWithoutOffers").asLong()).isEqualTo(4500);
        assertThat(money.at("/status/key").asText()).isEqualTo("PAID");
        assertThat(money.at("/status/text").asText()).startsWith("Paid until ");
        assertThat(money.at("/cycle").asText()).isEqualTo("MONTHLY");

        // An offer for this business changes both sides the same way, and the old price is shown crossed out.
        Res offer = post("/admin/tenants/" + t + "/offers", admin("SALES"), with("kind", "PERCENT", "title", "20% off", "percent", 20, "months", 3, "endsOn", tomorrow(), "reason", "Loyal customer"));
        assertThat(offer.status()).as(offer.body().toString()).isEqualTo(200);
        money = get("/admin/tenants/" + t + "/money", admin).body();
        assertThat(money.at("/nextBill")).isEqualTo(get("/billing/next-bill", owner).body());
        assertThat(money.at("/priceNow").asLong()).isEqualTo(3600);
        assertThat(money.at("/priceWithoutOffers").asLong()).isEqualTo(4500);
        assertThat(money.at("/offer").asText()).isEqualTo("20% off");
        assertThat(money.at("/nextBill/total").asLong()).isEqualTo(3600 + 802);
    }

    @Test
    void statusInPlainWords() {
        JsonNode trial = signup("retail");
        String admin = admin("DEVELOPER");
        assertThat(get("/admin/tenants/" + id(trial) + "/money", admin).at("/status/text").asText()).startsWith("Free trial (").endsWith("left)");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE', plan = 'LITE', paid_until = now() - interval '1 day' WHERE id = ?::uuid", id(trial));
        assertThat(get("/admin/tenants/" + id(trial) + "/money", admin).at("/status/text").asText()).startsWith("In grace (");
        jdbc.update("UPDATE tenants SET paid_until = now() - interval '10 days' WHERE id = ?::uuid", id(trial));
        assertThat(get("/admin/tenants/" + id(trial) + "/money", admin).at("/status/text").asText()).startsWith("Locked since ");
    }

    // ---------- payments and requests ----------

    @Test
    void recordingAPaymentMustMatchTheNextBillOrSayWhy() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 13, 30);
        String t = id(s), admin = admin("SALES");
        post("/billing/plan-change", owner, Map.of("plan", "GROWTH")); // a proration line of 802 on the next bill
        assertThat(get("/admin/tenants/" + t + "/money", admin).at("/nextBill/total").asLong()).isEqualTo(5302);

        Res wrong = post("/admin/tenants/" + t + "/record-payment", admin, with("amount", 4500, "reference", "QWE123ABC1", "reason", "Cash at the office"));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.at("/message").asText()).contains("KES 5,302").contains("why the amount is different");
        assertThat(post("/admin/tenants/" + t + "/record-payment", admin, with("amount", 5302, "reference", "QWE123ABC1")).status()).as("a reason is needed").isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_requests WHERE tenant_id = ?::uuid AND status = 'PAID'", Long.class, t)).isZero();

        Res ok = post("/admin/tenants/" + t + "/record-payment", admin, with("amount", 5302, "reference", "qwe123abc1", "reason", "Paid at the office in cash"));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(ok.at("/matched").asBoolean()).isTrue();
        // The line is paid through the same function the M-Pesa payment uses.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_lines WHERE tenant_id = ?::uuid AND paid_at IS NULL", Long.class, t)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_lines WHERE tenant_id = ?::uuid AND paid_request_id IS NOT NULL", Long.class, t)).isEqualTo(1);
        // The same code can't be recorded twice.
        assertThat(post("/admin/tenants/" + t + "/record-payment", admin, with("amount", 4500, "reference", "QWE123ABC1", "reason", "Again", "differenceReason", "Test")).status()).isEqualTo(409);
        assertThat(audited(t, "workspace.record_payment")).isEqualTo(1);
        assertThat(auditReason(t, "workspace.record_payment")).isEqualTo("Paid at the office in cash");

        // A different amount is allowed with its reason, and both are kept.
        Res diff = post("/admin/tenants/" + t + "/record-payment", admin, with("amount", 4000, "reference", "ZZZ999TTT2", "reason", "Part paid on phone", "differenceReason", "We agreed a discount of 500"));
        assertThat(diff.status()).isEqualTo(200);
        assertThat(diff.at("/matched").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT note FROM subscription_requests WHERE id = ?::uuid", String.class, diff.at("/requestId").asText())).contains("We agreed a discount of 500");
    }

    @Test
    void paymentsAndRequestsHistory() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 13, 30);
        String t = id(s), admin = admin("ADMIN");
        // One waiting request from the owner, one recorded by hand, one plan change, one free-days gift.
        Res sub = post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY"));
        assertThat(sub.status()).isEqualTo(200);
        post("/admin/tenants/" + t + "/record-payment", admin, with("amount", 2650, "reference", "REC111AAA1", "reason", "Cash"));
        post("/admin/tenants/" + t + "/plan-change", admin, with("plan", "GROWTH", "reason", "They asked on the phone"));
        post("/admin/tenants/" + t + "/free-days", admin, with("days", 5, "reason", "Apology for the outage"));

        JsonNode pays = get("/admin/tenants/" + t + "/plan-payments", admin).body();
        assertThat(pays.at("/payments")).hasSize(1);
        assertThat(pays.at("/payments/0/amount").asLong()).isEqualTo(2650);
        assertThat(pays.at("/payments/0/method").asText()).isEqualTo("Recorded by hand");
        assertThat(pays.at("/payments/0/mpesaReceipt").asText()).isEqualTo("REC111AAA1");
        assertThat(pays.at("/payments/0/activatedBy").asText()).isNotBlank().isNotEqualTo("Automatic (M-Pesa)");
        assertThat(pays.at("/payments/0/periodFrom").asText()).isNotBlank();
        assertThat(pays.at("/payments/0/periodTo").asText()).isNotBlank();
        assertThat(pays.at("/byYear/0/year").asInt()).isEqualTo(LocalDate.now().getYear());
        assertThat(pays.at("/byYear/0/total").asLong()).isEqualTo(2650);
        String paymentId = pays.at("/payments/0/id").asText();
        JsonNode receipt = get("/admin/tenants/" + t + "/plan-payments/" + paymentId + "/receipt", admin).body();
        assertThat(receipt.at("/receiptNo").asText()).startsWith("BZ-");
        assertThat(receipt.at("/amount").asLong()).isEqualTo(2650);
        Raw csv = raw("/admin/tenants/" + t + "/plan-payments.csv", admin);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(new String(csv.bytes(), StandardCharsets.UTF_8)).contains("REC111AAA1").contains("2650");
        // Another business's payment is not readable through this one.
        JsonNode other = signup("retail");
        assertThat(get("/admin/tenants/" + id(other) + "/plan-payments/" + paymentId + "/receipt", admin).status()).isEqualTo(404);

        JsonNode reqs = get("/admin/tenants/" + t + "/plan-requests", admin).body();
        List<String> statuses = new ArrayList<>();
        reqs.at("/requests").forEach(r -> statuses.add(r.get("status").asText()));
        assertThat(statuses).contains("PAID", "SUPERSEDED");
        assertThat(reqs.at("/planChanges/0/kind").asText()).isEqualTo("UPGRADE");
        assertThat(reqs.at("/planChanges/0/by").asText()).contains("LeziTech");
        assertThat(reqs.at("/freeDays/0/detail/days").asInt()).isEqualTo(5);

        List<String> kinds = new ArrayList<>();
        get("/admin/tenants/" + t + "/timeline", admin).body().forEach(e -> kinds.add(e.get("kind").asText()));
        assertThat(kinds).contains("MONEY", "NOTICE");
    }

    // ---------- roles ----------

    @Test
    void rolesDecideWhoCanDoWhat() {
        JsonNode s = signup("retail");
        paid(s, "LITE", 13, 30);
        String t = id(s);
        String support = admin("SUPPORT"), dev = admin("DEVELOPER"), sales = admin("SALES"), adm = admin("ADMIN"), root = admin("SUPERADMIN");
        Map<String, Object> pay = with("amount", 2650, "reference", "ROLE000001", "reason", "Cash");

        // Everyone can read the money.
        for (String who : List.of(support, dev, sales, adm, root)) {
            assertThat(get("/admin/tenants/" + t + "/money", who).status()).isEqualTo(200);
            assertThat(get("/admin/tenants/" + t + "/plan-payments", who).status()).isEqualTo(200);
            assertThat(get("/admin/tenants/" + t + "/timeline", who).status()).isEqualTo(200);
        }
        assertThat(get("/admin/workspaces", dev).status()).isEqualTo(200);
        // Support can't touch money; developer can't change anything.
        for (String who : List.of(support, dev)) {
            assertThat(post("/admin/tenants/" + t + "/record-payment", who, pay).status()).isEqualTo(403);
            assertThat(post("/admin/tenants/" + t + "/free-days", who, with("days", 3, "reason", "x y z")).status()).isEqualTo(403);
            assertThat(post("/admin/tenants/" + t + "/plan-change", who, with("plan", "GROWTH", "reason", "x y z")).status()).isEqualTo(403);
            assertThat(post("/admin/tenants/" + t + "/reminder", who, with("reason", "x y z")).status()).isEqualTo(403);
            assertThat(post("/admin/tenants/" + t + "/suspend", who, with("reason", "x y z")).status()).isEqualTo(403);
        }
        assertThat(post("/admin/tenants/" + t + "/notes", dev, with("note", "hello")).status()).isEqualTo(403);
        assertThat(post("/admin/tenants/" + t + "/signin-code", dev, with("reason", "lost it")).status()).isEqualTo(403);
        // Support can add notes and fix sign-in.
        assertThat(post("/admin/tenants/" + t + "/notes", support, with("note", "Called the owner")).status()).isEqualTo(200);
        assertThat(post("/admin/tenants/" + t + "/signin-code", support, with("reason", "Owner lost the email")).status()).isEqualTo(200);
        assertThat(post("/admin/tenants/" + t + "/owner-email", support, with("email", "new-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local", "reason", "Typo at sign-up")).status())
                .as("support may only send a code to the existing email").isEqualTo(403);
        assertThat(post("/admin/tenants/" + t + "/owner-email", adm, with("email", "new-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local", "reason", "Typo at sign-up")).status()).isEqualTo(200);
        // Sales do money and reminders, not sign-in or pausing.
        assertThat(post("/admin/tenants/" + t + "/owner-email", sales, with("email", "x@test.local", "reason", "x y z")).status()).isEqualTo(403);
        assertThat(post("/admin/tenants/" + t + "/suspend", sales, with("reason", "x y z")).status()).isEqualTo(403);
        assertThat(post("/admin/tenants/" + t + "/reminder", sales, with("reason", "Gentle nudge")).status()).isEqualTo(200);
        assertThat(post("/admin/tenants/" + t + "/record-payment", sales, pay).status()).isEqualTo(200);
        // Every action needs a reason.
        assertThat(post("/admin/tenants/" + t + "/free-days", adm, with("days", 3)).status()).isEqualTo(400);
        assertThat(post("/admin/tenants/" + t + "/suspend", adm, with("reason", " ")).status()).isEqualTo(400);
        assertThat(get("/admin/tenants/" + t + "/notes", dev).body()).hasSize(1);
    }

    // ---------- the business is always told ----------

    @Test
    void everyActionIsAuditedAndTheBusinessIsToldOnce() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 13, 30);
        String t = id(s), email = s.get("email").asText(), admin = admin("ADMIN");
        int told = 0;

        // Free days.
        assertThat(post("/admin/tenants/" + t + "/free-days", admin, with("days", 4, "reason", "Outage last week")).status()).isEqualTo(200);
        assertThat(audited(t, "workspace.free_days")).isEqualTo(1);
        assertThat(notices(email)).hasSize(++told);
        assertThat(myNotices(owner, true)).hasSize(told);
        assertThat(myNotices(owner, true).at("/0/title").asText()).isEqualTo("4 free days from LeziTech");
        assertThat(myNotices(owner, true).at("/0/body").asText()).contains("LeziTech gave you 4 free days").contains("Your next bill moves to");

        // An offer, then ending it.
        Res offer = post("/admin/tenants/" + t + "/offers", admin, with("kind", "PERCENT", "title", "20% off Busy Shop", "percent", 20, "months", 2, "endsOn", tomorrow(), "reason", "Keeps us"));
        assertThat(offer.status()).isEqualTo(200);
        assertThat(audited(t, "workspace.offer_apply")).isEqualTo(1);
        assertThat(notices(email)).hasSize(++told);
        assertThat(myNotices(owner, true).at("/0/body").asText()).contains("20% off your monthly payments for 2 months").contains("Your next bill on").contains("KES 2,120");
        assertThat(post("/admin/tenants/" + t + "/offers/" + offer.at("/offerId").asText() + "/end", admin, with("reason", "Offer was a mistake")).status()).isEqualTo(200);
        assertThat(audited(t, "workspace.offer_end")).isEqualTo(1);
        assertThat(notices(email)).hasSize(++told);
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(2650);

        // A plan change shows the same sheet wording the owner would see, and the owner is told.
        JsonNode sheet = get("/admin/tenants/" + t + "/plan-change/preview?plan=GROWTH", admin).body();
        assertThat(sheet.at("/message").asText()).contains("Busy Shop is KES 4,500 a month (you are on Corner Shop, KES 2,650).");
        assertThat(post("/admin/tenants/" + t + "/plan-change", admin, with("plan", "GROWTH", "reason", "Wanted suppliers")).status()).isEqualTo(200);
        assertThat(audited(t, "workspace.plan_change")).isEqualTo(1);
        assertThat(notices(email)).hasSize(++told);
        assertThat(myNotices(owner, true).at("/0/body").asText()).contains("LeziTech changed your plan. You are on Busy Shop now.");

        // A reminder now.
        assertThat(post("/admin/tenants/" + t + "/reminder", admin, with("reason", "Bill is near")).status()).isEqualTo(200);
        assertThat(audited(t, "workspace.reminder")).isEqualTo(1);
        assertThat(notices(email)).hasSize(++told);

        // A payment by hand.
        long bill = get("/admin/tenants/" + t + "/money", admin).at("/nextBill/total").asLong();
        assertThat(post("/admin/tenants/" + t + "/record-payment", admin, with("amount", bill, "reference", "ALL000AAA1", "reason", "Cash")).status()).isEqualTo(200);
        assertThat(notices(email)).hasSize(++told);

        // Pausing and unpausing.
        assertThat(post("/admin/tenants/" + t + "/suspend", admin, with("reason", "Unpaid invoice from March")).status()).isEqualTo(200);
        assertThat(audited(t, "workspace.suspend")).isEqualTo(1);
        assertThat(notices(email)).hasSize(++told);
        assertThat(myNotices(owner, true).at("/0/body").asText()).contains("Talk to us").contains("Unpaid invoice from March");
        assertThat(post("/admin/tenants/" + t + "/reactivate", admin, with("reason", "They paid")).status()).isEqualTo(200);
        assertThat(audited(t, "workspace.reactivate")).isEqualTo(1);
        assertThat(notices(email)).hasSize(++told);

        // Every one is on the control panel's list with when it was sent and whether the owner has seen it.
        JsonNode sent = get("/admin/tenants/" + t + "/notices", admin).body();
        assertThat(sent).hasSize(told);
        assertThat(sent.at("/0/sentEmailAt").asText()).isNotBlank();
        assertThat(sent.at("/0/seenAt").isNull()).isTrue();
        // Each email went once, in the Busynez layout.
        assertThat(notices(email).get(0).html()).contains("Busynez");
        // Nothing the owner reads uses the words "due" or "owe", or long dashes.
        for (JsonNode n : sent) {
            String text = (n.get("title").asText() + " " + n.get("body").asText()).toLowerCase();
            assertThat(text).doesNotContainPattern("\\b(due|owe|owed)\\b").doesNotContain("—").doesNotContain("–");
        }
    }

    @Test
    void ownerEmailChangeTellsBothAddresses() {
        JsonNode s = signup("retail");
        String owner = token(s);
        String t = id(s), oldEmail = s.get("email").asText(), admin = admin("ADMIN");
        String fresh = "fixed-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
        assertThat(post("/admin/tenants/" + t + "/owner-email", admin, with("email", fresh, "reason", "Typo in the old one")).status()).isEqualTo(200);
        assertThat(box.sent.stream().filter(e -> e.to().equals(oldEmail)).toList()).hasSize(1);
        assertThat(box.sent.stream().filter(e -> e.to().equals(oldEmail)).findFirst().get().text()).contains(oldEmail).contains(fresh);
        assertThat(box.sent.stream().filter(e -> e.to().equals(fresh)).toList()).hasSize(1);
        assertThat(audited(t, "workspace.owner_email")).isEqualTo(1);
        // They can sign in with the new address and not the old one.
        assertThat(post("/auth/login", null, Map.of("email", fresh, "password", "password123")).status()).isEqualTo(200);
        assertThat(post("/auth/login", null, Map.of("email", oldEmail, "password", "password123")).status()).isEqualTo(401);
        assertThat(get("/billing/notices", owner).status()).as("the owner's old sessions are signed out").isEqualTo(401);
        // An address already in use is refused.
        JsonNode other = signup("retail");
        assertThat(post("/admin/tenants/" + t + "/owner-email", admin, with("email", other.get("email").asText(), "reason", "Swap")).status()).isEqualTo(409);
    }

    @Test
    void pausedMeansTheSameLockAsAnUnpaidBillWithATalkToUsMessage() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 13, 30);
        String t = id(s), admin = admin("ADMIN");
        assertThat(post("/admin/tenants/" + t + "/suspend", admin, with("reason", "Chargeback")).status()).isEqualTo(200);
        Res blocked = post("/items", owner, Map.of("name", "Soda", "price", 50));
        assertThat(blocked.status()).isEqualTo(402);
        assertThat(blocked.at("/message").asText()).contains("Talk to us");
        // They can still look, see the notice and say "Got it".
        assertThat(get("/items", owner).status()).isEqualTo(200);
        JsonNode mine = myNotices(owner, true);
        assertThat(mine).hasSize(1);
        assertThat(post("/billing/notices/" + mine.at("/0/id").asText() + "/seen", owner, Map.of()).status()).isEqualTo(200);
        assertThat(get("/admin/tenants/" + t + "/money", admin).at("/status/text").asText()).isEqualTo("Suspended (Chargeback)");
        // Back to what it was: still paying, paid until the same date.
        assertThat(post("/admin/tenants/" + t + "/reactivate", admin, with("reason", "Sorted")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?::uuid", String.class, t)).isEqualTo("ACTIVE");
        assertThat(post("/items", owner, Map.of("name", "Soda", "price", 50)).status()).isNotEqualTo(402);
    }

    @Test
    void noticesAreVisibleToTheRightBusinessOnlyAndGotItWorks() {
        JsonNode a = signup("retail"), b = signup("retail");
        String ownerA = paid(a, "LITE", 13, 30), ownerB = paid(b, "LITE", 13, 30);
        String admin = admin("SALES");
        post("/admin/tenants/" + id(a) + "/free-days", admin, with("days", 2, "reason", "Thank you"));
        JsonNode mine = myNotices(ownerA, true);
        assertThat(mine).hasSize(1);
        String noticeId = mine.at("/0/id").asText();
        // B sees nothing and can't touch A's notice.
        assertThat(myNotices(ownerB, false)).isEmpty();
        assertThat(post("/billing/notices/" + noticeId + "/seen", ownerB, Map.of()).status()).isEqualTo(404);
        // The database itself hides the rows from any request not made as that business.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_notices", Long.class)).isZero();
        // A manager sees none (it is the owner's money).
        assertThat(get("/billing/notices?unseen=true", staff(ownerA, "MANAGER")).body()).isEmpty();
        // Got it: gone from the unseen list, still listed under Messages from Busynez, and the control panel sees it was seen.
        assertThat(post("/billing/notices/" + noticeId + "/seen", ownerA, Map.of()).status()).isEqualTo(200);
        assertThat(myNotices(ownerA, true)).isEmpty();
        assertThat(myNotices(ownerA, false)).hasSize(1);
        assertThat(myNotices(ownerA, false).at("/0/seenAt").asText()).isNotBlank();
        assertThat(get("/admin/tenants/" + id(a) + "/notices", admin).at("/0/seenAt").asText()).isNotBlank();
        assertThat(get("/admin/tenants/" + id(b) + "/notices", admin).body()).isEmpty();
    }

    // ---------- platform-wide prices and offers ----------

    private Res setPlans(String admin, int lite, int growth, int pro) {
        List<Map<String, Object>> plans = new ArrayList<>();
        for (JsonNode p : get("/admin/platform-settings", admin).at("/plans")) {
            int price = switch (p.get("code").asText()) {
                case "LITE" -> lite;
                case "GROWTH" -> growth;
                default -> pro;
            };
            plans.add(with("code", p.get("code").asText(), "name", p.get("name").asText(), "monthly", price, "users", p.get("users").asInt(),
                    "locations", p.get("locations").asInt(), "blurb", p.get("blurb").asText()));
        }
        return put("/admin/platform-settings", admin, Map.of("plans", plans, "reason", "Costs went up"));
    }

    @Test
    void aPriceRiseReachesAPayingBusinessAtItsBillAfterNextWithSevenDaysNotice() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 20, 30);
        JsonNode trial = signup("retail");
        String trialOwner = token(trial);
        String t = id(s), admin = admin("ADMIN");
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(2650);

        assertThat(setPlans(admin, 3000, 4500, 9000).status()).isEqualTo(200);

        // The next bill keeps the old price. A business on free days pays the new one.
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(2650);
        assertThat(get("/admin/tenants/" + t + "/money", admin).at("/nextBill/total").asLong()).isEqualTo(2650);
        // It was told at once, weeks ahead (never less than 7 days before it starts).
        JsonNode n = myNotices(owner, true);
        assertThat(n).hasSize(1);
        assertThat(n.at("/0/title").asText()).contains("will cost more from");
        assertThat(n.at("/0/body").asText()).contains("from KES 2,650 to KES 3,000 a month").contains("Your next bill on").contains("stays at the old price");
        assertThat(myNotices(trialOwner, true)).isEmpty();
        Long startsInDays = jdbc.queryForObject("SELECT (paid_until + interval '1 month')::date - now()::date FROM tenants WHERE id = ?::uuid", Long.class, t);
        assertThat(startsInDays).isGreaterThanOrEqualTo(7);

        // Paying the next bill moves them to the bill after next, which is at the new price.
        UUID req = UUID.fromString(post("/billing/subscribe", owner, Map.of("plan", "LITE", "cycle", "MONTHLY")).at("/requestId").asText());
        assertThat(jdbc.queryForObject("SELECT amount FROM subscription_requests WHERE id = ?", Long.class, req)).isEqualTo(2650);
        activation.activate(req, "MANUAL", null);
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(3000);
        assertThat(get("/admin/tenants/" + t + "/money", admin).at("/nextBill/total").asLong()).isEqualTo(3000);

        // A price that goes down shows at once and is good news.
        assertThat(setPlans(admin, 2500, 4500, 9000).status()).isEqualTo(200);
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(2500);
        assertThat(myNotices(owner, true).at("/0/title").asText()).startsWith("Good news:");
    }

    @Test
    void aPlanOfferAndAnOfferForEveryoneTellTheBusinesses() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 20, 30);
        JsonNode trial = signup("retail");
        String trialOwner = token(trial);
        String admin = admin("ADMIN");
        Map<String, Object> lite = with("price", 2000, "label", "Launch offer", "startsOn", null, "endsOn", LocalDate.now().plusDays(30).toString(), "on", true);
        assertThat(put("/admin/platform-settings", admin, Map.of("planOffers", Map.of("LITE", lite))).status()).isEqualTo(200);
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(2000);
        assertThat(myNotices(owner, true).at("/0/body").asText()).contains("is now KES 2,000 a month").contains("it was KES 2,650").contains("Your next bill on");
        assertThat(myNotices(trialOwner, true)).as("a trial on another plan is not told about the Lite offer").isEmpty();

        // Switching the offer off early is a rise for a paying business: it keeps the offer price for its next bill.
        lite.put("on", false);
        assertThat(put("/admin/platform-settings", admin, Map.of("planOffers", Map.of("LITE", lite))).status()).isEqualTo(200);
        assertThat(get("/billing/next-bill", owner).at("/total").asLong()).isEqualTo(2000);
        assertThat(myNotices(owner, true).at("/0/title").asText()).contains("will cost more from");

        // An offer for everyone in a kind of business tells each business it reaches.
        Res made = post("/admin/offers", admin, with("kind", "PERCENT", "title", "10% off for retail", "percent", 10, "months", 2, "audience", "ALL", "sector", "retail", "endsOn", tomorrow()));
        assertThat(made.status()).isEqualTo(200);
        assertThat(myNotices(trialOwner, true).at("/0/title").asText()).isEqualTo("Good news: 10% off for retail");
    }

    // ---------- the Workspaces list ----------

    @Test
    void theWorkspacesListHasFiltersTotalsAndCsv() {
        JsonNode grace = signup("retail"), locked = signup("retail"), ending = signup("retail"), paidMonth = signup("retail"), never = signup("retail"), paused = signup("retail");
        paid(grace, "LITE", 0, 30);
        jdbc.update("UPDATE tenants SET paid_until = now() - interval '1 day' WHERE id = ?::uuid", id(grace));
        paid(locked, "LITE", 0, 30);
        jdbc.update("UPDATE tenants SET paid_until = now() - interval '9 days' WHERE id = ?::uuid", id(locked));
        jdbc.update("UPDATE tenants SET trial_ends_at = now() + interval '2 days' WHERE id = ?::uuid", id(ending));
        paid(paidMonth, "GROWTH", 20, 30);
        String admin = admin("DEVELOPER"), sales = admin("SALES");
        assertThat(post("/admin/tenants/" + id(paidMonth) + "/record-payment", sales, with("amount", 4500, "reference", "LST000AAA1", "reason", "Cash")).status()).isEqualTo(200);
        assertThat(post("/admin/tenants/" + id(paused) + "/suspend", admin("ADMIN"), with("reason", "Test")).status()).isEqualTo(200);

        Map<String, JsonNode> by = Map.of("GRACE", grace, "LOCKED", locked, "TRIAL3", ending, "PAID_MONTH", paidMonth, "NEVER_PAID", never, "SUSPENDED", paused);
        for (Map.Entry<String, JsonNode> e : by.entrySet()) {
            JsonNode list = get("/admin/workspaces?filter=" + e.getKey() + "&size=200", admin).body();
            List<String> ids = new ArrayList<>();
            list.at("/rows").forEach(r -> ids.add(r.get("id").asText()));
            assertThat(ids).as(e.getKey()).contains(id(e.getValue()));
            for (Map.Entry<String, JsonNode> other : by.entrySet()) {
                if (!other.getKey().equals(e.getKey()) && !(e.getKey().equals("NEVER_PAID") || e.getKey().equals("LOCKED"))) {
                    assertThat(ids).as(e.getKey() + " leaves out " + other.getKey()).doesNotContain(id(other.getValue()));
                }
            }
            assertThat(list.at("/totals/businesses").asLong()).isEqualTo(list.at("/total").asLong());
        }
        assertThat(get("/admin/workspaces?filter=NOPE", admin).status()).isEqualTo(400);
        // The paid-this-month business is not in "never paid"; the others without a payment are.
        List<String> never2 = new ArrayList<>();
        get("/admin/workspaces?filter=NEVER_PAID&size=200", admin).at("/rows").forEach(r -> never2.add(r.get("id").asText()));
        assertThat(never2).contains(id(never), id(grace)).doesNotContain(id(paidMonth));

        // One business found by its owner's email: its columns equal what its own pages say, and the totals are its own.
        JsonNode one = get("/admin/workspaces?q=" + paidMonth.get("email").asText(), admin).body();
        assertThat(one.at("/rows")).hasSize(1);
        JsonNode row = one.at("/rows/0");
        assertThat(row.at("/planName").asText()).isEqualTo("Busy Shop");
        assertThat(row.at("/pricePerMonth").asLong()).isEqualTo(4500);
        assertThat(row.at("/status/text").asText()).startsWith("Paid until ");
        assertThat(row.at("/lastPaymentAmount").asLong()).isEqualTo(4500);
        assertThat(row.at("/lastPaymentMethod").asText()).isEqualTo("Recorded by hand");
        assertThat(row.at("/nextBill").asText()).isNotBlank();
        assertThat(one.at("/totals/businesses").asLong()).isEqualTo(1);
        assertThat(one.at("/totals/moneyAMonth").asLong()).isEqualTo(4500);
        assertThat(one.at("/totals/moneyInThisMonth").asLong()).isEqualTo(4500);

        // Paged.
        JsonNode page = get("/admin/workspaces?size=2&page=1", admin).body();
        assertThat(page.at("/rows")).hasSize(2);
        assertThat(page.at("/total").asLong()).isGreaterThanOrEqualTo(6);
        // CSV of the same filtered list.
        Raw csv = raw("/admin/workspaces.csv?q=" + paidMonth.get("email").asText(), admin);
        String text = new String(csv.bytes(), StandardCharsets.UTF_8);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.contentType()).startsWith("text/csv");
        assertThat(text).contains("Business,Sector,Plan,Price a month (KES),Status,Next bill date").contains("Busy Shop").contains("4500");
        assertThat(text.lines().count()).isEqualTo(2);

        // The money overview.
        JsonNode money = get("/admin/money-overview", admin).body();
        assertThat(money.at("/received").asLong()).isGreaterThanOrEqualTo(4500);
        assertThat(money.at("/expected").asLong()).isEqualTo(money.at("/received").asLong() + money.at("/outstanding").asLong());
        assertThat(money.at("/grace/count").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(money.at("/locked/count").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(money.at("/byPlan")).isNotEmpty();
        assertThat(money.at("/bySector")).isNotEmpty();
        assertThat(money.at("/last30").size()).isBetween(1, 30);
    }

    @Test
    void usageNotesAndWhatsAppForTheTeam() {
        JsonNode s = signup("restaurant");
        String owner = paid(s, "LITE", 13, 30);
        String t = id(s), admin = admin("SUPPORT");
        staff(owner, "CASHIER");
        JsonNode usage = get("/admin/tenants/" + t + "/usage-detail", admin).body();
        assertThat(usage.at("/staff").size()).isGreaterThanOrEqualTo(2);
        assertThat(usage.at("/branches").size()).isGreaterThanOrEqualTo(1);
        assertThat(usage.at("/sales30/count").isNumber()).isTrue();
        assertThat(usage.has("tools")).isTrue();

        post("/admin/tenants/" + t + "/notes", admin, with("note", "First call: wants Mondays off"));
        post("/admin/tenants/" + t + "/notes", admin, with("note", "Second call: happy"));
        JsonNode notes = get("/admin/tenants/" + t + "/notes", admin).body();
        assertThat(notes).hasSize(2);
        assertThat(notes.at("/0/note").asText()).isEqualTo("Second call: happy");
        assertThat(notes.at("/0/adminName").asText()).isNotBlank();
        assertThat(notes.at("/0/createdAt").asText()).isNotBlank();

        // With a phone number, a WhatsApp text is ready for the team to send, and they mark it sent.
        jdbc.update("UPDATE tenants SET phone = '0712345678' WHERE id = ?::uuid", t);
        post("/admin/tenants/" + t + "/free-days", admin("SALES"), with("days", 2, "reason", "Because"));
        JsonNode sent = get("/admin/tenants/" + t + "/notices", admin).body();
        assertThat(sent.at("/0/whatsappTo").asText()).isEqualTo("0712345678");
        assertThat(sent.at("/0/whatsappText").asText()).contains("this is LeziTech");
        assertThat(sent.at("/0/sentWhatsappAt").isNull()).isTrue();
        assertThat(post("/admin/tenants/" + t + "/notices/" + sent.at("/0/id").asText() + "/whatsapp-sent", admin, Map.of()).status()).isEqualTo(200);
        assertThat(get("/admin/tenants/" + t + "/notices", admin).at("/0/sentWhatsappAt").asText()).isNotBlank();
    }

    // ---------- review fixes ----------

    @Test
    void aPausedBusinessThatPaysKeepsThePaymentExtendsTheDateAndStaysPaused() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 5, 30);
        String t = id(s), admin = admin("ADMIN");
        assertThat(post("/admin/tenants/" + t + "/suspend", admin, with("reason", "Chargeback")).status()).isEqualTo(200);
        java.sql.Timestamp before = jdbc.queryForObject("SELECT paid_until FROM tenants WHERE id = ?::uuid", java.sql.Timestamp.class, t);
        long bill = get("/admin/tenants/" + t + "/money", admin).at("/nextBill/total").asLong();
        assertThat(post("/admin/tenants/" + t + "/record-payment", admin, with("amount", bill, "reference", "PAU111AAA1", "reason", "Paid in cash while paused")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?::uuid", String.class, t)).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT suspended_from FROM tenants WHERE id = ?::uuid", String.class, t)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT paid_until FROM tenants WHERE id = ?::uuid", java.sql.Timestamp.class, t)).isAfter(before);
        assertThat(audited(t, "workspace.paid_while_paused")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_notes WHERE tenant_id = ?::uuid AND note LIKE 'Paid while paused%'", Long.class, t)).isEqualTo(1);
        assertThat(post("/items", owner, Map.of("name", "Soda", "price", 50)).status()).as("still locked").isEqualTo(402);
        // Only "Open again" switches it on.
        assertThat(post("/admin/tenants/" + t + "/reactivate", admin, with("reason", "Sorted")).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?::uuid", String.class, t)).isEqualTo("ACTIVE");
    }

    @Test
    void aPaymentCodeCanBeRecordedOnlyOnceAndIsAtMostThirtyCharacters() {
        JsonNode a = signup("retail"), b = signup("retail");
        String admin = admin("ADMIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_subscription_requests_paid_code'", Long.class)).isEqualTo(1);
        long billA = get("/admin/tenants/" + id(a) + "/money", admin).at("/nextBill/total").asLong();
        long billB = get("/admin/tenants/" + id(b) + "/money", admin).at("/nextBill/total").asLong();
        assertThat(post("/admin/tenants/" + id(a) + "/record-payment", admin, with("amount", billA, "reference", "ONCE00AAA1", "reason", "Cash")).status()).isEqualTo(200);
        Res again = post("/admin/tenants/" + id(b) + "/record-payment", admin, with("amount", billB, "reference", "once00aaa1", "reason", "Cash"));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.at("/message").asText()).contains("already recorded");
        Res tooLong = post("/admin/tenants/" + id(b) + "/record-payment", admin, with("amount", billB, "reference", "X".repeat(31), "reason", "Cash"));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.at("/message").asText()).contains("30");
    }

    @Test
    void theOperatorConsoleFollowsTheSameRules() {
        JsonNode s = signup("retail");
        String owner = token(s), t = id(s);
        assertThat(operator(t + "/activate", "{\"plan\":\"GROWTH\",\"months\":1}")).as("a reason is needed").isEqualTo(400);
        assertThat(operator(t + "/extend-trial", "{\"days\":5}")).isEqualTo(400);
        assertThat(operator(t + "/suspend", "{}")).isEqualTo(400);
        assertThat(operator(t + "/extend-trial", "{\"days\":5,\"reason\":\"They asked\"}")).isEqualTo(200);
        assertThat(audited(t, "workspace.free_days")).isEqualTo(1);
        assertThat(operator(t + "/activate", "{\"plan\":\"GROWTH\",\"months\":1,\"reason\":\"Paid at the office\"}")).isEqualTo(200);
        assertThat(audited(t, "workspace.record_payment")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?::uuid", String.class, t)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT activated_by FROM subscription_requests WHERE tenant_id = ?::uuid AND status = 'PAID'", String.class, t)).isEqualTo("Operator console");
        assertThat(operator(t + "/suspend", "{\"reason\":\"Chargeback\"}")).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?::uuid", String.class, t)).isEqualTo("SUSPENDED");
        assertThat(audited(t, "workspace.suspend")).isEqualTo(1);
        JsonNode mine = myNotices(owner, false);
        assertThat(mine.size()).as("told about the free days, the payment and the pause").isEqualTo(3);
    }

    @Test
    void ownerEmailOnlyForAdminsAndAFailedEmailToTheOldAddressIsFlagged() {
        JsonNode s = signup("retail");
        String t = id(s), admin = admin("ADMIN");
        itMailer.useSender(new EmailSender() {
            @Override
            public void send(Email email) {
                throw new IllegalStateException("mail is down");
            }

            @Override
            public String name() {
                return "down";
            }
        });
        String fresh = "flag-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
        assertThat(post("/admin/tenants/" + t + "/owner-email", admin, with("email", fresh, "reason", "Typo in the old one")).status()).isEqualTo(200);
        assertThat(audited(t, "workspace.owner_email_old_not_told")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_notes WHERE tenant_id = ?::uuid AND note LIKE '%did not go%'", Long.class, t)).isEqualTo(1);
    }

    @Test
    void aPriceRiseHoldsOnlyBusinessesOnThatPlan() {
        JsonNode lite = signup("retail"), pro = signup("retail");
        paid(lite, "LITE", 20, 30);
        paid(pro, "PRO", 20, 30);
        assertThat(setPlans(admin("ADMIN"), 3000, 4500, 9000).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_price_holds WHERE tenant_id = ?::uuid", Long.class, id(lite))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_price_holds WHERE tenant_id = ?::uuid", Long.class, id(pro))).isZero();
    }

    @Test
    void aPlanOfferEndingOnItsOwnDateTellsPayingBusinessesSevenDaysBeforeOnlyOnce() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 20, 30);
        String admin = admin("ADMIN");
        Map<String, Object> lite = with("price", 2000, "label", "Launch offer", "startsOn", null, "endsOn", LocalDate.now().plusDays(5).toString(), "on", true);
        assertThat(put("/admin/platform-settings", admin, Map.of("planOffers", Map.of("LITE", lite))).status()).isEqualTo(200);
        long before = myNotices(owner, false).size();
        actions.warnAboutOffersEnding();
        awaitJobs();
        JsonNode n = myNotices(owner, false);
        assertThat(n.size()).isEqualTo(before + 1);
        assertThat(n.at("/0/title").asText()).startsWith("The special price for").contains("ends on");
        actions.warnAboutOffersEnding();
        awaitJobs();
        assertThat(myNotices(owner, false).size()).as("told once").isEqualTo(before + 1);
    }

    @Test
    void aPausedBusinessStillShowsInTheSwitcherWithItsReason() {
        JsonNode s = signup("retail");
        String owner = paid(s, "LITE", 13, 30);
        assertThat(post("/admin/tenants/" + id(s) + "/suspend", admin("ADMIN"), with("reason", "Chargeback")).status()).isEqualTo(200);
        JsonNode list = get("/businesses", owner).at("/businesses");
        assertThat(list).hasSize(1);
        assertThat(list.at("/0/paused").asBoolean()).isTrue();
        assertThat(list.at("/0/pausedReason").asText()).isEqualTo("Chargeback");
    }
}
