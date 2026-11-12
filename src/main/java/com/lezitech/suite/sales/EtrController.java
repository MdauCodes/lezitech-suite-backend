package com.lezitech.suite.sales;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.notify.EmailJobs;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The business's own KRA receipt (ETR) for a sale: the cashier photographs or scans the receipt from the shop's ETR
 * machine and attaches it, and can email it to the customer. Free on every plan. Works without eTIMS.
 */
@RestController
@Transactional
public class EtrController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final Db db;
    private final FileService files;
    private final EmailJobs emailJobs;
    private final Mailer mailer;

    public EtrController(Db db, FileService files, EmailJobs emailJobs, Mailer mailer) {
        this.db = db;
        this.files = files;
        this.emailJobs = emailJobs;
        this.mailer = mailer;
    }

    /** Attach the ETR (photo or PDF, up to 6 MB) to a paid sale, replacing any earlier one. */
    @PostMapping("/api/orders/{id}/etr")
    public Map<String, Object> attach(@PathVariable UUID id, @RequestParam("file") MultipartFile upload) throws IOException {
        Map<String, Object> o = order(id);
        if (!"PAID".equals(o.get("status")) && !"CREDIT".equals(o.get("status"))) {
            throw ApiException.bad("Attach the KRA receipt once the sale is paid");
        }
        UUID file = files.save(FileService.Purpose.ETR, upload.getBytes());
        db.exec("UPDATE orders SET etr_file_id = :f, etr_uploaded_at = now(), etr_emailed_to = NULL, etr_emailed_at = NULL WHERE id = :id",
                "f", file, "id", id);
        if (o.get("etrFileId") != null) {
            files.remove(UUID.fromString(o.get("etrFileId").toString()));
        }
        return Map.of("ok", true, "etrFileId", file);
    }

    /** The attached ETR, for staff to look at or print. */
    @GetMapping("/api/orders/{id}/etr")
    public ResponseEntity<byte[]> show(@PathVariable UUID id) {
        Map<String, Object> o = order(id);
        if (o.get("etrFileId") == null) {
            throw ApiException.notFound("KRA receipt");
        }
        FileService.Stored f = files.load(UUID.fromString(o.get("etrFileId").toString())).orElseThrow(() -> ApiException.notFound("KRA receipt"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate()).body(f.bytes());
    }

    @DeleteMapping("/api/orders/{id}/etr")
    public Map<String, Object> remove(@PathVariable UUID id) {
        Map<String, Object> o = order(id);
        if (o.get("etrFileId") != null) {
            db.exec("UPDATE orders SET etr_file_id = NULL, etr_uploaded_at = NULL, etr_emailed_to = NULL, etr_emailed_at = NULL WHERE id = :id", "id", id);
            files.remove(UUID.fromString(o.get("etrFileId").toString()));
        }
        return Map.of("ok", true);
    }

    /** Email the ETR to the customer in the background (failures show under Needs attention, like receipts). */
    @PostMapping("/api/orders/{id}/etr/email")
    public Map<String, Object> email(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Map<String, Object> o = order(id);
        if (o.get("etrFileId") == null) {
            throw ApiException.bad("Attach the KRA receipt first");
        }
        String to = new Body(raw).str("email", "").trim().toLowerCase();
        if (!EMAIL.matcher(to).matches()) {
            throw ApiException.bad("Enter the customer's email address");
        }
        if (!mailer.on()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_OFF", "Emails aren't switched on yet. Send the ETR photo by WhatsApp instead.");
        }
        UUID job = emailJobs.queue(TenantContext.tenantId(), id, to, TenantContext.userId(), "ETR");
        return Map.of("ok", true, "queued", true, "jobId", job, "to", to);
    }

    private Map<String, Object> order(UUID id) {
        return db.one("Sale", "SELECT id, status, etr_file_id FROM orders WHERE id = :id", "id", id);
    }
}
