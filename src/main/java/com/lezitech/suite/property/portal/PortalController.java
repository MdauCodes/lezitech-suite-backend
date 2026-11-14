package com.lezitech.suite.property.portal;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.mpesa.MpesaService;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.property.LeaseLedger;
import com.lezitech.suite.property.LeaseLife;
import com.lezitech.suite.property.RentSettings;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.lezitech.suite.notify.Mailer.esc;

/**
 * The tenant's own portal (sign-in in {@link RenterAuth}): their units, what they owe, paying by M-Pesa prompt,
 * statements, documents, complaints and repairs, notices, and notice to move out. Only a tenant's portal token reaches
 * /api/portal (see JwtFilter), and everything is checked against their own leases.
 */
@RestController
public class PortalController {

    /** Pay prompts from one tenant: 4 per 10 minutes. */
    public static final RateLimit PAY = new RateLimit(4, 600_000);
    /** New complaints from one tenant: 10 a day. */
    public static final RateLimit REPORTS = new RateLimit(10, 86_400_000);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final Db db;
    private final TransactionTemplate tx;
    private final RenterAuth auth;
    private final LeaseLedger ledger;
    private final LeaseLife life;
    private final Maintenance maintenance;
    private final Notices notices;
    private final MpesaService mpesa;
    private final FileService files;
    private final Mailer mailer;

    public PortalController(Db db, PlatformTransactionManager txm, RenterAuth auth, LeaseLedger ledger, LeaseLife life, Maintenance maintenance,
                            Notices notices, MpesaService mpesa, FileService files, Mailer mailer) {
        this.db = db;
        this.tx = new TransactionTemplate(txm);
        this.auth = auth;
        this.ledger = ledger;
        this.life = life;
        this.maintenance = maintenance;
        this.notices = notices;
        this.mpesa = mpesa;
        this.files = files;
        this.mailer = mailer;
    }

    // ---------------- sign-in (public) ----------------

    @GetMapping("/api/public/portal/{slug}")
    public Map<String, Object> portalBrand(@PathVariable String slug) {
        Map<String, Object> b = auth.business(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", b.get("name"));
        out.put("phone", b.get("phone"));
        out.put("brandColor", b.get("brand_color"));
        out.put("logoUrl", b.get("logo_url"));
        return out;
    }

    @PostMapping("/api/public/portal/{slug}/code")
    public Map<String, Object> code(@PathVariable String slug, @RequestBody Map<String, Object> raw) {
        auth.requestCode(slug, new Body(raw).str("email", ""));
        return Map.of("sent", true, "message", "If that email is on a lease here, a 6-digit code is on its way. Check spam too.");
    }

    @PostMapping("/api/public/portal/{slug}/password")
    public Map<String, Object> password(@PathVariable String slug, @RequestBody Map<String, Object> raw, HttpServletRequest req) {
        Body b = new Body(raw);
        return auth.setPassword(slug, b.str("email", ""), b.str("code", ""), b.str("password", ""), req);
    }

    @PostMapping("/api/public/portal/{slug}/login")
    public Map<String, Object> login(@PathVariable String slug, @RequestBody Map<String, Object> raw, HttpServletRequest req) {
        Body b = new Body(raw);
        return auth.login(slug, b.str("email", ""), b.str("password", ""), req);
    }

    // ---------------- the tenant's portal ----------------

    record Me(UUID account, UUID customer, String name, String email) {
    }

    private Me me() {
        TenantContext.Caller c = TenantContext.callerOrNull();
        if (c == null || !"RENTER".equals(c.role())) {
            throw ApiException.forbidden("This is the tenants' page. Sign in as a tenant.");
        }
        Map<String, Object> a = db.one("Account", "SELECT customer_id, name, email FROM renter_accounts WHERE id = :id AND tenant_id = :t", "id", c.userId());
        return new Me(c.userId(), UUID.fromString(a.get("customerId").toString()), (String) a.get("name"), (String) a.get("email"));
    }

    /** The lease must be one of this tenant's. */
    private void mine(Me me, UUID leaseId) {
        if (db.find("SELECT id FROM leases WHERE id = :l AND customer_id = :c", "l", leaseId, "c", me.customer()).isEmpty()) {
            throw ApiException.notFound("Lease");
        }
    }

    private void myRequest(Me me, UUID requestId) {
        if (db.find("SELECT id FROM maintenance_requests WHERE id = :r AND customer_id = :c", "r", requestId, "c", me.customer()).isEmpty()) {
            throw ApiException.notFound("Request");
        }
    }

    @GetMapping("/api/portal/me")
    public Map<String, Object> home() {
        return tx.execute(s -> {
            Me me = me();
            Map<String, Object> biz = db.one("Business", """
                    SELECT name, phone, brand_color, logo_url, settings->>'address' AS address, coalesce(settings->'rent', '{}'::jsonb)::text AS rent
                    FROM tenants WHERE id = :t""");
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> business = new LinkedHashMap<>();
            business.put("name", biz.get("name"));
            business.put("phone", biz.get("phone"));
            business.put("address", biz.get("address"));
            business.put("brandColor", biz.get("brandColor"));
            business.put("logoUrl", biz.get("logoUrl"));
            out.put("business", business);
            out.put("payInstructions", RentSettings.withDefaults((String) biz.get("rent")).get("payInstructions"));
            out.put("me", Map.of("name", me.name(), "email", me.email()));
            out.put("mpesaPrompts", mpesa.publicConfig().get("enabled"));
            out.put("leases", db.list("""
                    SELECT l.id, sp.name AS unit_name, sp.floor, l.rent, l.service_charge, l.due_day, l.starts_on, l.ends_on, l.status, l.credit,
                           l.vacate_on, l.vacate_reason, """ + "upper(regexp_replace(coalesce(nullif(sp.account_code, ''), sp.name), '[^A-Za-z0-9]', '', 'g'))" + """
                            AS account_code,
                           (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN') AS unpaid,
                           (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN' AND o.due_on < current_date) AS overdue,
                           (SELECT min(o.due_on) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN') AS next_due,
                           (SELECT count(*) FROM lease_documents d WHERE d.lease_id = l.id) AS documents
                    FROM leases l JOIN spaces sp ON sp.id = l.space_id
                    WHERE l.customer_id = :c AND (l.status = 'ACTIVE' OR EXISTS (SELECT 1 FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN') OR l.credit > 0)
                    ORDER BY l.status, sp.name""", "c", me.customer()));
            out.put("openRequests", db.value("SELECT count(*) FROM maintenance_requests WHERE customer_id = :c AND status NOT IN ('CLOSED','CANCELLED')",
                    Long.class, "c", me.customer()));
            out.put("latestNotice", notices.forCustomer(me.customer()).stream().findFirst().orElse(null));
            return out;
        });
    }

    @GetMapping("/api/portal/leases/{id}/statement")
    public Map<String, Object> statement(@PathVariable UUID id, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return tx.execute(s -> {
            mine(me(), id);
            Map<String, Object> st = ledger.statement(id, from == null || from.isBlank() ? null : LocalDate.parse(from), to == null || to.isBlank() ? null : LocalDate.parse(to));
            return st;
        });
    }

    /** Pay rent by M-Pesa prompt to the tenant's phone; the money goes on their bills by itself. */
    @PostMapping("/api/portal/leases/{id}/pay")
    public Map<String, Object> pay(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Me me = tx.execute(s -> {
            Me m = me();
            mine(m, id);
            return m;
        });
        if (!PAY.allow(me.account().toString())) {
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN",
                    "You've asked for several payments in a few minutes. Wait a little, then try again.");
        }
        Body b = new Body(raw);
        BigDecimal amount = b.has("amount") ? b.num("amount") : null;
        if (amount != null && amount.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            throw ApiException.bad("That amount is too big for one M-Pesa payment");
        }
        return mpesa.startLeasePayment(id, b.str("phone", null), amount);
    }

    /** How a prompt is going (the tenant's own prompts only). */
    @GetMapping("/api/portal/payments/{requestId}")
    public Map<String, Object> payment(@PathVariable UUID requestId) {
        UUID lease = db.value("SELECT lease_id FROM mpesa_requests WHERE id = :id AND tenant_id = :t", UUID.class, "id", requestId);
        if (lease == null) {
            throw ApiException.notFound("Payment");
        }
        tx.executeWithoutResult(s -> mine(me(), lease));
        Map<String, Object> st = mpesa.status(requestId);
        return Map.of("status", st.get("status"), "receipt", st.get("receipt") == null ? "" : st.get("receipt"),
                "message", st.get("message") == null ? "" : st.get("message"), "amount", st.get("amount"));
    }

    @GetMapping("/api/portal/leases/{id}/documents")
    public List<Map<String, Object>> documents(@PathVariable UUID id) {
        return tx.execute(s -> {
            mine(me(), id);
            return db.list("SELECT d.id, d.name, d.created_at, f.content_type FROM lease_documents d JOIN files f ON f.id = d.file_id WHERE d.lease_id = :l ORDER BY d.created_at DESC", "l", id);
        });
    }

    @GetMapping("/api/portal/documents/{docId}")
    public ResponseEntity<byte[]> document(@PathVariable UUID docId) {
        FileService.Stored f = tx.execute(s -> {
            Me me = me();
            Map<String, Object> d = db.one("Document", "SELECT lease_id, file_id FROM lease_documents WHERE id = :id", "id", docId);
            mine(me, UUID.fromString(d.get("leaseId").toString()));
            return files.load(UUID.fromString(d.get("fileId").toString())).orElseThrow(() -> ApiException.notFound("Document"));
        });
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).cacheControl(CacheControl.noStore()).body(f.bytes());
    }

    /** The tenant gives notice that they're moving out. The landlord is told and it shows under "Needs you". */
    @PostMapping("/api/portal/leases/{id}/vacate")
    public Map<String, Object> vacate(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return tx.execute(s -> {
            Me me = me();
            mine(me, id);
            Body b = new Body(raw);
            LocalDate on = b.has("moveOutOn") ? b.date("moveOutOn") : null;
            if (on == null || !on.isAfter(LeaseLedger.today())) {
                throw ApiException.bad("Choose the day you plan to move out (after today)");
            }
            String reason = b.str("reason", "").trim();
            if (reason.length() > 300) {
                reason = reason.substring(0, 300);
            }
            Map<String, Object> l = db.one("Lease", "SELECT l.status, sp.name AS unit FROM leases l JOIN spaces sp ON sp.id = l.space_id WHERE l.id = :id", "id", id);
            if (!"ACTIVE".equals(l.get("status"))) {
                throw ApiException.conflict("This lease has already ended");
            }
            db.exec("UPDATE leases SET vacate_on = :d, vacate_reason = :r, vacate_given_at = now() WHERE id = :id", "id", id, "d", on, "r", reason.isEmpty() ? null : reason);
            life.event(id, "NOTICE_TO_VACATE", me.name() + " gave notice to move out on " + DAY.format(on) + (reason.isEmpty() ? "" : ": " + reason), Map.of());
            for (Map<String, Object> o : db.list("SELECT email, name FROM users WHERE tenant_id = :t AND role IN ('OWNER','MANAGER') AND active AND email IS NOT NULL")) {
                mailer.send((String) o.get("email"), (String) o.get("name"), me.name() + " gave notice to move out of " + l.get("unit"),
                        me.name() + " (" + l.get("unit") + ") plans to move out on " + DAY.format(on) + "." + (reason.isEmpty() ? "" : "\nReason: " + reason)
                                + "\n\nOpen Rent & tenants: " + mailer.appUrl() + "/property",
                        "<p><b>" + esc(me.name()) + "</b> (" + esc((String) l.get("unit")) + ") plans to move out on <b>" + DAY.format(on) + "</b>.</p>"
                                + (reason.isEmpty() ? "" : "<p>Reason: " + esc(reason) + "</p>")
                                + "<p><a href=\"" + esc(mailer.appUrl() + "/property") + "\">Open Rent &amp; tenants</a></p>");
            }
            return Map.of("vacateOn", on.toString());
        });
    }

    // ---------------- complaints and repairs ----------------

    @GetMapping("/api/portal/requests")
    public List<Map<String, Object>> requests() {
        return tx.execute(s -> {
            Me me = me();
            List<Map<String, Object>> list = maintenance.list("m.customer_id = :c", "c", me.customer());
            list.forEach(r -> {
                r.remove("cost");
                r.remove("assignedTo");
            });
            return list;
        });
    }

    @GetMapping("/api/portal/requests/{id}")
    public Map<String, Object> request(@PathVariable UUID id) {
        return tx.execute(s -> {
            myRequest(me(), id);
            return maintenance.detail(id, true);
        });
    }

    @PostMapping("/api/portal/requests")
    public Map<String, Object> report(@RequestBody Map<String, Object> raw) {
        return tx.execute(s -> {
            Me me = me();
            if (!REPORTS.allow(me.account().toString())) {
                throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN",
                        "You've reported many problems today. Call the management office for anything urgent.");
            }
            Body b = new Body(raw);
            UUID lease = UUID.fromString(b.str("leaseId"));
            mine(me, lease);
            Map<String, Object> r = maintenance.create(lease, b, "TENANT", me.name());
            r.remove("cost");
            r.remove("assignedTo");
            return r;
        });
    }

    @PostMapping("/api/portal/requests/{id}/photos")
    public Map<String, Object> photo(@PathVariable UUID id, @RequestParam("file") MultipartFile upload,
                                     @RequestParam(value = "caption", required = false) String caption) throws IOException {
        byte[] bytes = upload.getBytes();
        return tx.execute(s -> {
            Me me = me();
            myRequest(me, id);
            return maintenance.addPhoto(id, bytes, "TENANT", me.name(), caption);
        });
    }

    @PostMapping("/api/portal/requests/{id}/messages")
    public Map<String, Object> message(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return tx.execute(s -> {
            Me me = me();
            myRequest(me, id);
            return maintenance.tenantMessage(id, me.name(), new Body(raw).str("message", ""));
        });
    }

    @PostMapping("/api/portal/requests/{id}/rate")
    public Map<String, Object> rate(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return tx.execute(s -> {
            myRequest(me(), id);
            Body b = new Body(raw);
            return maintenance.rate(id, b.integer("rating", 0), b.str("comment", null));
        });
    }

    /** A photo on one of the tenant's own requests. */
    @GetMapping("/api/portal/files/{fileId}")
    public ResponseEntity<byte[]> file(@PathVariable UUID fileId) {
        FileService.Stored f = tx.execute(s -> {
            Me me = me();
            boolean ownLogo = Boolean.TRUE.equals(db.value("""
                    SELECT EXISTS (SELECT 1 FROM directory_entries e JOIN leases l ON l.id = e.lease_id
                                   WHERE l.customer_id = :c AND (e.logo_file_id = :f OR e.draft->>'logoFileId' = cast(:f as text)))""",
                    Boolean.class, "c", me.customer(), "f", fileId));
            if (!ownLogo && !maintenance.photoBelongsToCustomer(fileId, me.customer())) {
                throw ApiException.notFound("Photo");
            }
            return files.load(fileId).orElseThrow(() -> ApiException.notFound("Photo"));
        });
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate()).body(f.bytes());
    }

    // ---------------- their shop in the mall directory ----------------

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.property.Directory directory;

    @GetMapping("/api/portal/leases/{id}/shop")
    public Map<String, Object> shop(@PathVariable UUID id) {
        return tx.execute(s -> {
            mine(me(), id);
            return directory.forLease(id);
        });
    }

    @PostMapping("/api/portal/leases/{id}/shop")
    public Map<String, Object> saveShop(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return tx.execute(s -> {
            mine(me(), id);
            return directory.tenantSave(id, new Body(raw));
        });
    }

    @PostMapping("/api/portal/leases/{id}/shop/logo")
    public Map<String, Object> shopLogo(@PathVariable UUID id, @RequestParam("file") MultipartFile upload) throws IOException {
        byte[] bytes = upload.getBytes();
        return tx.execute(s -> {
            mine(me(), id);
            return directory.tenantLogo(id, bytes);
        });
    }

    @PostMapping("/api/portal/leases/{id}/shop/offers")
    public Map<String, Object> shopOffer(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return tx.execute(s -> {
            mine(me(), id);
            return directory.tenantOffer(id, new Body(raw));
        });
    }

    @PostMapping("/api/portal/leases/{id}/shop/offers/{offerId}/delete")
    public Map<String, Object> removeShopOffer(@PathVariable UUID id, @PathVariable UUID offerId) {
        return tx.execute(s -> {
            mine(me(), id);
            return directory.tenantRemoveOffer(id, offerId);
        });
    }

    @GetMapping("/api/portal/notices")
    public List<Map<String, Object>> notices() {
        return tx.execute(s -> notices.forCustomer(me().customer()));
    }
}
