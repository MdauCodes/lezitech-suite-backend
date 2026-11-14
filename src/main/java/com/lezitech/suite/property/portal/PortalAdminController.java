package com.lezitech.suite.property.portal;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.property.LeaseLife;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The landlord's side of the tenant portal: invitations, complaints and repairs, notices, documents, notices to vacate. */
@RestController
@RequestMapping("/api/property")
@Transactional
public class PortalAdminController {

    private final Db db;
    private final RenterAuth auth;
    private final Maintenance maintenance;
    private final Notices notices;
    private final FileService files;
    private final LeaseLife life;

    public PortalAdminController(Db db, RenterAuth auth, Maintenance maintenance, Notices notices, FileService files, LeaseLife life) {
        this.db = db;
        this.auth = auth;
        this.maintenance = maintenance;
        this.notices = notices;
        this.files = files;
        this.life = life;
    }

    /** Invite the tenant on this lease to their portal, or send them a new code. */
    @PostMapping("/leases/{id}/portal-invite")
    public Map<String, Object> invite(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Map<String, Object> l = db.one("Lease", "SELECT l.customer_id, c.name, c.email FROM leases l JOIN customers c ON c.id = l.customer_id WHERE l.id = :id", "id", id);
        String email = new Body(raw).str("email", (String) l.get("email"));
        if (email != null && !email.isBlank() && !email.trim().equalsIgnoreCase(String.valueOf(l.get("email")))) {
            db.exec("UPDATE customers SET email = :e WHERE id = :c", "e", email.trim().toLowerCase(), "c", l.get("customerId"));
        }
        Map<String, Object> out = auth.invite(TenantContext.tenantId(), UUID.fromString(l.get("customerId").toString()), email, (String) l.get("name"));
        life.event(id, "PORTAL_INVITE", "Invited to the tenant page (" + out.get("email") + ")", Map.of());
        return out;
    }

    // ---------------- complaints and repairs ----------------

    @GetMapping("/maintenance")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "open") String show) {
        return "all".equals(show) ? maintenance.list("true")
                : maintenance.list("m.status NOT IN ('CLOSED','CANCELLED')");
    }

    @GetMapping("/maintenance/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return maintenance.detail(id, false);
    }

    /** Staff report a problem themselves (a tenant's unit, or a common area with no lease). */
    @PostMapping("/maintenance")
    public Map<String, Object> create(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        UUID lease = b.has("leaseId") && !b.str("leaseId", "").isBlank() ? UUID.fromString(b.str("leaseId")) : null;
        return maintenance.create(lease, b, "STAFF", TenantContext.caller().name());
    }

    @PostMapping("/maintenance/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return maintenance.staffUpdate(id, new Body(raw), TenantContext.caller().name(), false);
    }

    @PostMapping("/maintenance/{id}/photos")
    public Map<String, Object> photo(@PathVariable UUID id, @RequestParam("file") MultipartFile upload,
                                     @RequestParam(value = "caption", required = false) String caption) throws IOException {
        return maintenance.addPhoto(id, upload.getBytes(), "STAFF", TenantContext.caller().name(), caption);
    }

    @PostMapping("/maintenance/{id}/charge")
    public Map<String, Object> charge(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        return maintenance.charge(id, b.num("amount"), b.str("what", null));
    }

    /** Who a job can be given to: caretakers and technicians, and managers. */
    @GetMapping("/maintenance-staff")
    public List<Map<String, Object>> staff() {
        return db.list("SELECT id, name, role FROM users WHERE tenant_id = :t AND active AND role IN ('TECHNICIAN','MANAGER','OWNER') ORDER BY role = 'TECHNICIAN' DESC, name");
    }

    // ---------------- notices ----------------

    @GetMapping("/notices")
    public List<Map<String, Object>> notices() {
        return notices.all();
    }

    @PostMapping("/notices")
    public Map<String, Object> post(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        Object ids = b.raw("leaseIds");
        List<String> leases = ids instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return notices.post(b.str("title", ""), b.str("body", ""), b.str("audience", "ALL"), b.str("floor", null), leases);
    }

    // ---------------- documents ----------------

    @GetMapping("/leases/{id}/documents")
    public List<Map<String, Object>> documents(@PathVariable UUID id) {
        return db.list("SELECT d.id, d.name, d.file_id, d.created_at, f.content_type FROM lease_documents d JOIN files f ON f.id = d.file_id WHERE d.lease_id = :l ORDER BY d.created_at DESC", "l", id);
    }

    @PostMapping("/leases/{id}/documents")
    public Map<String, Object> addDocument(@PathVariable UUID id, @RequestParam("file") MultipartFile upload, @RequestParam("name") String name) throws IOException {
        TenantContext.requireRole("MANAGER");
        db.one("Lease", "SELECT id FROM leases WHERE id = :id", "id", id);
        String n = name == null || name.isBlank() ? "Document" : name.trim();
        if (n.length() > 150) {
            n = n.substring(0, 150);
        }
        UUID file = files.save(FileService.Purpose.LEASE_DOC, upload.getBytes());
        UUID doc = db.insert("INSERT INTO lease_documents (tenant_id, lease_id, file_id, name) VALUES (:t, :l, :f, :n) RETURNING id", "l", id, "f", file, "n", n);
        life.event(id, "DOCUMENT", "Shared a document with the tenant: " + n, Map.of());
        return Map.of("id", doc, "name", n);
    }

    @PostMapping("/documents/{id}/delete")
    public Map<String, Object> removeDocument(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        Map<String, Object> d = db.one("Document", "SELECT file_id, lease_id, name FROM lease_documents WHERE id = :id", "id", id);
        db.exec("DELETE FROM lease_documents WHERE id = :id", "id", id);
        files.remove(UUID.fromString(d.get("fileId").toString()));
        life.event(UUID.fromString(d.get("leaseId").toString()), "DOCUMENT", "Removed the document " + d.get("name"), Map.of());
        return Map.of("removed", true);
    }

    /** The tenant changed their mind (or it was a mistake): the notice to vacate is withdrawn. */
    @PostMapping("/leases/{id}/vacate/clear")
    public Map<String, Object> clearVacate(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        db.exec("UPDATE leases SET vacate_on = NULL, vacate_reason = NULL, vacate_given_at = NULL WHERE id = :id", "id", id);
        life.event(id, "NOTICE_WITHDRAWN", "Notice to move out withdrawn", Map.of());
        return Map.of("cleared", true);
    }

    static void requireText(String s, String message) {
        if (s == null || s.isBlank()) {
            throw ApiException.bad(message);
        }
    }
}
