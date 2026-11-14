package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Floors of the building (order, names, units in one go), and the mall directory (landlord side and the public page). */
@RestController
public class DirectoryController {

    private final Floors floors;
    private final Directory directory;
    private final FileService files;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DirectoryController(Floors floors, Directory directory, FileService files, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.floors = floors;
        this.directory = directory;
        this.files = files;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    // ---------------- floors ----------------

    @GetMapping("/api/property/floors")
    @Transactional
    public List<Map<String, Object>> floors() {
        return floors.list();
    }

    @PostMapping("/api/property/floors")
    @Transactional
    public List<Map<String, Object>> addFloor(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return floors.add(new Body(raw).str("name", ""));
    }

    @PostMapping("/api/property/floors/{id}")
    @Transactional
    public List<Map<String, Object>> renameFloor(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return floors.rename(id, new Body(raw).str("name", ""));
    }

    /** {ids: [...]} from the bottom floor to the top. */
    @PostMapping("/api/property/floors/order")
    @Transactional
    public List<Map<String, Object>> reorder(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Object ids = raw == null ? null : raw.get("ids");
        return floors.reorder(ids instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
    }

    @PostMapping("/api/property/floors/{id}/delete")
    @Transactional
    public List<Map<String, Object>> removeFloor(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        return floors.remove(id);
    }

    @PostMapping("/api/property/floors/{id}/units")
    @Transactional
    public Map<String, Object> bulkUnits(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return floors.bulkUnits(id, new Body(raw));
    }

    // ---------------- directory: landlord ----------------

    @GetMapping("/api/property/directory")
    @Transactional
    public Map<String, Object> directory() {
        return directory.admin();
    }

    @PostMapping("/api/property/directory/{id}")
    @Transactional
    public Map<String, Object> save(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return directory.adminSave(id, new Body(raw));
    }

    @PostMapping("/api/property/directory/{id}/approve")
    @Transactional
    public Map<String, Object> approve(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        return directory.decideDraft(id, true);
    }

    @PostMapping("/api/property/directory/{id}/reject")
    @Transactional
    public Map<String, Object> reject(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        return directory.decideDraft(id, false);
    }

    @PostMapping("/api/property/directory/offers/{id}/{decision}")
    @Transactional
    public Map<String, Object> offer(@PathVariable UUID id, @PathVariable String decision) {
        TenantContext.requireRole("MANAGER");
        return directory.decideOffer(id, "approve".equals(decision));
    }

    // ---------------- directory: public ----------------

    private Map<String, Object> business(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, phone, brand_color, logo_url, status, sector, settings->>'address' AS address FROM tenants WHERE slug = ?""", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status")) || !"property".equals(rows.get(0).get("sector"))) {
            throw ApiException.notFound("Page");
        }
        return rows.get(0);
    }

    @GetMapping("/api/public/mall/{slug}")
    public Map<String, Object> mall(@PathVariable String slug) {
        Map<String, Object> b = business(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("name", b.get("name"));
        biz.put("phone", b.get("phone"));
        biz.put("address", b.get("address"));
        biz.put("brandColor", b.get("brand_color"));
        biz.put("logoUrl", b.get("logo_url"));
        out.put("business", biz);
        out.put("shops", TenantContext.runAs((UUID) b.get("id"), null, () -> tx.execute(x -> directory.publicEntries())));
        return out;
    }

    @GetMapping("/api/public/mall/{slug}/logos/{fileId}")
    public ResponseEntity<byte[]> logo(@PathVariable String slug, @PathVariable UUID fileId) {
        Map<String, Object> b = business(slug);
        FileService.Stored f = TenantContext.runAs((UUID) b.get("id"), null, () -> tx.execute(x -> {
            if (!directory.isPublicLogo(fileId)) {
                throw ApiException.notFound("Logo");
            }
            return files.load(fileId).orElseThrow(() -> ApiException.notFound("Logo"));
        }));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic()).body(f.bytes());
    }
}
