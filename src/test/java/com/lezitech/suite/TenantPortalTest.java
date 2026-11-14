package com.lezitech.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.auth.LoginThrottle;
import com.lezitech.suite.notify.EmailSender;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.property.portal.PortalController;
import com.lezitech.suite.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Malls & property, package M5: the tenant portal, complaints and repairs, notices, documents, notice to vacate. */
class TenantPortalTest extends IntegrationTest {

    @Autowired
    Mailer mailer;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Nairobi"));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private EmailSender.LogSender log;

    @BeforeEach
    void capture() {
        log = new EmailSender.LogSender();
        mailer.useSender(log);
        PortalController.PAY.clear();
        PortalController.REPORTS.clear();
    }

    record Setup(String owner, String slug, String lease, String email) {
    }

    private Setup landlordWithTenant(String unitName, String email) {
        JsonNode s = signup("property");
        String owner = token(s);
        return new Setup(owner, s.at("/tenant/slug").asText(), lease(owner, unitName, email), email);
    }

    private String lease(String owner, String unitName, String email) {
        String unit = null;
        for (JsonNode u : get("/property/units", owner).body()) {
            if (unitName.equals(u.get("name").asText())) {
                unit = u.get("id").asText();
            }
        }
        Map<String, Object> body = new HashMap<>(Map.of("spaceId", unit, "tenantName", "Njeri Boutique", "phone", "0712345678",
                "rent", 10000, "serviceCharge", 0, "dueDay", 5, "startsOn", YearMonth.from(TODAY).atDay(1).toString()));
        if (email != null) {
            body.put("email", email);
        }
        Res r = post("/property/leases", owner, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.at("/id").asText();
    }

    private String codeSentTo(String email) {
        for (int i = log.sent.size() - 1; i >= 0; i--) {
            EmailSender.Email e = log.sent.get(i);
            if (e.to().equals(email)) {
                Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(e.text());
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    private String signIn(Setup s) {
        assertThat(post("/property/leases/" + s.lease() + "/portal-invite", s.owner(), Map.of()).at("/invited").asBoolean()).isTrue();
        Res in = post("/public/portal/" + s.slug() + "/password", null, Map.of("email", s.email(), "code", codeSentTo(s.email()), "password", "tenant-pass-1"));
        assertThat(in.status()).as(in.body().toString()).isEqualTo(200);
        return in.at("/token").asText();
    }

    private static String mail() {
        return "tenant-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    }

    @Test
    void theLandlordInvitesTheTenantWhoSetsAPasswordAndCanReachOnlyTheirPortal() {
        Setup s = landlordWithTenant("G03", mail());
        assertThat(post("/property/leases/" + s.lease() + "/portal-invite", s.owner(), Map.of()).at("/invited").asBoolean()).isTrue();
        String code = codeSentTo(s.email());
        assertThat(code).isNotNull();
        String wrong = code.equals("000000") ? "111111" : "000000";
        assertThat(post("/public/portal/" + s.slug() + "/password", null, Map.of("email", s.email(), "code", wrong, "password", "tenant-pass-1")).status()).isEqualTo(400);
        assertThat(post("/public/portal/" + s.slug() + "/password", null, Map.of("email", s.email(), "code", code, "password", "short")).status()).isEqualTo(400);
        Res in = post("/public/portal/" + s.slug() + "/password", null, Map.of("email", s.email(), "code", code, "password", "tenant-pass-1"));
        assertThat(in.status()).as(in.body().toString()).isEqualTo(200);
        String tenant = in.at("/token").asText();
        // The code is used up.
        assertThat(post("/public/portal/" + s.slug() + "/password", null, Map.of("email", s.email(), "code", code, "password", "tenant-pass-2")).status()).isEqualTo(400);

        // Email and password from now on.
        assertThat(post("/public/portal/" + s.slug() + "/login", null, Map.of("email", s.email(), "password", "nope-nope")).status()).isEqualTo(401);
        assertThat(post("/public/portal/" + s.slug() + "/login", null, Map.of("email", s.email().toUpperCase(), "password", "tenant-pass-1")).status()).isEqualTo(200);

        // A tenant's sign-in opens their portal only; a staff sign-in doesn't open the portal.
        assertThat(get("/portal/me", tenant).at("/leases/0/unitName").asText()).isEqualTo("G03");
        assertThat(get("/property/units", tenant).status()).isEqualTo(401);
        assertThat(get("/orders", tenant).status()).isEqualTo(401);
        assertThat(get("/portal/me", s.owner()).status()).isEqualTo(403);
        // The units list shows the tenant has an active portal.
        for (JsonNode u : get("/property/units", s.owner()).body()) {
            if ("G03".equals(u.get("name").asText())) {
                assertThat(u.get("portal").asText()).isEqualTo("ACTIVE");
            }
        }

        // Asking for a code: unknown email gets nothing (and isn't told so); a tenant on a lease who wasn't invited gets one.
        int before = log.sent.size();
        assertThat(post("/public/portal/" + s.slug() + "/code", null, Map.of("email", "nobody@test.local")).status()).isEqualTo(200);
        assertThat(log.sent.size()).isEqualTo(before);
        String other = mail();
        lease(s.owner(), "G04", other);
        post("/public/portal/" + s.slug() + "/code", null, Map.of("email", other));
        String code2 = codeSentTo(other);
        assertThat(code2).isNotNull();
        assertThat(post("/public/portal/" + s.slug() + "/password", null, Map.of("email", other, "code", code2, "password", "other-pass-1")).status()).isEqualTo(200);
        // A password change ends older sign-ins.
        post("/public/portal/" + s.slug() + "/code", null, Map.of("email", s.email()));
        post("/public/portal/" + s.slug() + "/password", null, Map.of("email", s.email(), "code", codeSentTo(s.email()), "password", "tenant-pass-9"));
        assertThat(get("/portal/me", tenant).status()).isEqualTo(401);
    }

    @Test
    void theTenantSeesWhatTheyOweAndPaysByMpesaPrompt() throws Exception {
        Setup s = landlordWithTenant("G05", mail());
        post("/property/invoices/generate", s.owner(), Map.of("period", YearMonth.from(TODAY).toString()));
        String tenant = signIn(s);
        JsonNode me = get("/portal/me", tenant).body();
        assertThat(me.at("/leases/0/unpaid").decimalValue()).isEqualByComparingTo("10000");
        assertThat(me.at("/leases/0/accountCode").asText()).isEqualTo("G05");
        assertThat(get("/portal/leases/" + s.lease() + "/statement", tenant).at("/balance").decimalValue()).isEqualByComparingTo("10000");

        Res pay = post("/portal/leases/" + s.lease() + "/pay", tenant, Map.of("phone", "0712345678"));
        assertThat(pay.status()).as(pay.body().toString()).isEqualTo(200);
        JsonNode st = null;
        for (int i = 0; i < 50; i++) {
            st = get("/portal/payments/" + pay.at("/id").asText(), tenant).body();
            if (!"PENDING".equals(st.get("status").asText())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(st.get("status").asText()).isEqualTo("PAID");
        assertThat(get("/portal/me", tenant).at("/leases/0/unpaid").decimalValue()).isEqualByComparingTo("0");

        // Another tenant's lease is out of reach.
        String otherLease = lease(s.owner(), "G06", null);
        assertThat(get("/portal/leases/" + otherLease + "/statement", tenant).status()).isEqualTo(404);
        assertThat(post("/portal/leases/" + otherLease + "/pay", tenant, Map.of("amount", 100)).status()).isEqualTo(404);
    }

    @Test
    void complaintsGoToTheTechnicianGivenTheJobAndTheTenantFollowsAndRatesIt() {
        Setup s = landlordWithTenant("S03", mail());
        String tenant = signIn(s);
        Res reported = post("/portal/requests", tenant, Map.of("leaseId", s.lease(), "category", "WATER", "title", "Tap leaking in the back",
                "details", "Water on the floor every morning", "priority", "URGENT"));
        assertThat(reported.status()).as(reported.body().toString()).isEqualTo(200);
        String req = reported.at("/id").asText();
        assertThat(upload("/portal/requests/" + req + "/photos", tenant, PNG, "leak.png").status()).isEqualTo(200);
        assertThat(get("/portal/requests", tenant).body().toString()).contains("Tap leaking in the back");

        // The owner sees it and gives it to a technician; a staff-only note stays hidden from the tenant.
        assertThat(get("/property/maintenance", s.owner()).body().toString()).contains("Tap leaking in the back");
        String tech = staff(s.owner(), "TECHNICIAN");
        String techId = userId(s.owner(), "TECHNICIAN");
        String otherTech = staff(s.owner(), "TECHNICIAN");
        assertThat(post("/property/maintenance/" + req, s.owner(), Map.of("assignedTo", techId, "message", "Plumber is coming tomorrow")).status()).isEqualTo(200);
        post("/property/maintenance/" + req, s.owner(), Map.of("message", "Charge them if it's their fault", "internal", true));
        JsonNode seen = get("/portal/requests/" + req, tenant).body();
        assertThat(seen.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(seen.get("updates").toString()).contains("Plumber is coming tomorrow").doesNotContain("Charge them");
        assertThat(log.sent.stream().anyMatch(e -> e.to().equals(s.email()) && e.subject().contains("Update on your request"))).isTrue();

        // Only the technician given the job sees it; they can't open the landlord's screens.
        assertThat(get("/jobs", tech).body().toString()).contains("Tap leaking in the back");
        assertThat(get("/jobs", otherTech).body().toString()).doesNotContain("Tap leaking in the back");
        assertThat(get("/jobs/" + req, otherTech).status()).isEqualTo(404);
        assertThat(get("/property/units", tech).status()).isEqualTo(403);
        assertThat(post("/jobs/" + req, tech, Map.of("status", "CANCELLED")).status()).isEqualTo(403);
        assertThat(upload("/jobs/" + req + "/photos", tech, PNG, "fixed.png").status()).isEqualTo(200);
        assertThat(post("/jobs/" + req, tech, Map.of("status", "DONE", "message", "Replaced the washer")).status()).isEqualTo(200);

        // The tenant sees the photos (theirs and the technician's), rates the fix, and it closes.
        JsonNode done = get("/portal/requests/" + req, tenant).body();
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        String photo = null;
        for (JsonNode u : done.get("updates")) {
            if (u.get("photos").size() > 0) {
                photo = u.get("photos").get(0).asText();
            }
        }
        assertThat(raw("/portal/files/" + photo, tenant).status()).isEqualTo(200);
        assertThat(post("/portal/requests/" + req + "/rate", tenant, Map.of("rating", 5, "comment", "Quick, thank you")).at("/status").asText()).isEqualTo("CLOSED");

        // The owner charges the repair to the tenant: it's on their statement.
        assertThat(post("/property/maintenance/" + req + "/charge", s.owner(), Map.of("amount", 1500, "what", "New washer")).status()).isEqualTo(200);
        assertThat(get("/portal/leases/" + s.lease() + "/statement", tenant).at("/balance").decimalValue()).isEqualByComparingTo("1500");
        assertThat(post("/property/maintenance/" + req + "/charge", s.owner(), Map.of("amount", 1500)).status()).isEqualTo(409);
    }

    @Test
    void noticesDocumentsAndNoticeToVacate() {
        Setup s = landlordWithTenant("O2", mail());
        String tenant = signIn(s);

        // Notices: to everyone (reaches them, by email too) and to another floor (doesn't).
        Res all = post("/property/notices", s.owner(), Map.of("title", "Water off on Saturday", "body", "From 8am to noon for repairs.", "audience", "ALL"));
        assertThat(all.status()).as(all.body().toString()).isEqualTo(200);
        post("/property/notices", s.owner(), Map.of("title", "Ground floor painting", "body", "Monday.", "audience", "FLOOR", "floor", "Ground"));
        String notices = get("/portal/notices", tenant).body().toString();
        assertThat(notices).contains("Water off on Saturday").doesNotContain("Ground floor painting");
        assertThat(log.sent.stream().anyMatch(e -> e.to().equals(s.email()) && e.subject().startsWith("Water off on Saturday"))).isTrue();

        // A document shared with the tenant: they can open it; nobody else's.
        byte[] pdf = "%PDF-1.4 lease".getBytes();
        assertThat(upload("/property/leases/" + s.lease() + "/documents?name=Signed%20lease", s.owner(), pdf, "lease.pdf").status()).isEqualTo(200);
        JsonNode docs = get("/portal/leases/" + s.lease() + "/documents", tenant).body();
        assertThat(docs.get(0).get("name").asText()).isEqualTo("Signed lease");
        assertThat(raw("/portal/documents/" + docs.get(0).get("id").asText(), tenant).status()).isEqualTo(200);

        // Notice to move out: the owner is told and sees it under Needs you.
        assertThat(post("/portal/leases/" + s.lease() + "/vacate", tenant, Map.of("moveOutOn", TODAY.toString())).status()).isEqualTo(400);
        Res gave = post("/portal/leases/" + s.lease() + "/vacate", tenant, Map.of("moveOutOn", TODAY.plusDays(30).toString(), "reason", "Moving to a bigger office"));
        assertThat(gave.status()).as(gave.body().toString()).isEqualTo(200);
        assertThat(get("/property/attention", s.owner()).body().get("vacating").toString()).contains("Moving to a bigger office");
        assertThat(get("/portal/me", tenant).at("/leases/0/vacateOn").asText()).isEqualTo(TODAY.plusDays(30).toString());
        assertThat(post("/property/leases/" + s.lease() + "/vacate/clear", s.owner(), Map.of()).status()).isEqualTo(200);
        assertThat(get("/property/attention", s.owner()).body().get("vacating")).isEmpty();
    }

    @Autowired
    LoginThrottle throttle;

    static List<String> none() {
        return List.of();
    }
}
