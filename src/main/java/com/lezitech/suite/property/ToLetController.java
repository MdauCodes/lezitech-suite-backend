package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The landlord's floor map and to-let listing, and the public "units to let" page with its enquiry form. */
@RestController
public class ToLetController {

    /** Enquiries from one phone or network: 8 an hour. */
    public static final RateLimit ENQUIRIES = new RateLimit(8, 3_600_000);

    private final ToLet toLet;
    private final FileService files;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ToLetController(ToLet toLet, FileService files, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.toLet = toLet;
        this.files = files;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    @GetMapping("/api/property/map")
    @Transactional
    public Map<String, Object> map() {
        return toLet.map();
    }

    @PostMapping("/api/property/map/positions")
    @Transactional
    public Map<String, Object> positions(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        toLet.savePositions(new Body(raw).list("positions"));
        return Map.of("saved", true);
    }

    @PostMapping("/api/property/map/plan")
    @Transactional
    public Map<String, Object> plan(@RequestParam("floor") String floor, @RequestParam("file") MultipartFile upload) throws IOException {
        TenantContext.requireRole("MANAGER");
        return toLet.setPlan(floor, upload.getBytes());
    }

    @PostMapping("/api/property/map/plan/delete")
    @Transactional
    public Map<String, Object> removePlan(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return toLet.removePlan(new Body(raw).str("floor", ""));
    }

    @GetMapping("/api/property/to-let")
    @Transactional
    public Map<String, Object> listing() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("units", toLet.listedUnits(false));
        out.put("leads", toLet.leads());
        out.put("link", "/let/" + jdbc.queryForObject("SELECT slug FROM tenants WHERE id = ?", String.class, TenantContext.tenantId()));
        return out;
    }

    @PostMapping("/api/property/units/{id}/listing")
    @Transactional
    public Map<String, Object> setListing(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return toLet.setListing(id, new Body(raw));
    }

    @GetMapping("/api/property/units/{id}/photos")
    @Transactional
    public List<Map<String, Object>> photos(@PathVariable UUID id) {
        return toLet.photos(id);
    }

    @PostMapping("/api/property/units/{id}/photos")
    @Transactional
    public Map<String, Object> addPhoto(@PathVariable UUID id, @RequestParam("file") MultipartFile upload) throws IOException {
        TenantContext.requireRole("MANAGER");
        return toLet.addPhoto(id, upload.getBytes());
    }

    @PostMapping("/api/property/unit-photos/{id}/delete")
    @Transactional
    public Map<String, Object> removePhoto(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        toLet.removePhoto(id);
        return Map.of("removed", true);
    }

    // ---------------- public ----------------

    private Map<String, Object> business(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, phone, brand_color, logo_url, status, sector, settings->>'address' AS address,
                       coalesce(settings->'rent', '{}'::jsonb)::text AS rent FROM tenants WHERE slug = ?""", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status")) || !"property".equals(rows.get(0).get("sector"))) {
            throw ApiException.notFound("Page");
        }
        return rows.get(0);
    }

    @GetMapping("/api/public/to-let/{slug}")
    public Map<String, Object> publicListing(@PathVariable String slug) {
        Map<String, Object> b = business(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("name", b.get("name"));
        biz.put("phone", b.get("phone"));
        biz.put("address", b.get("address"));
        biz.put("brandColor", b.get("brand_color"));
        biz.put("logoUrl", b.get("logo_url"));
        out.put("business", biz);
        out.put("serviceIncludes", RentSettings.withDefaults((String) b.get("rent")).get("serviceIncludes"));
        out.put("units", TenantContext.runAs((UUID) b.get("id"), null, () -> tx.execute(x -> {
            List<Map<String, Object>> units = toLet.listedUnits(true);
            units.forEach(u -> {
                u.remove("openLeads");
                u.remove("listed");
            });
            return units;
        })));
        return out;
    }

    @GetMapping("/api/public/to-let/{slug}/photos/{fileId}")
    public ResponseEntity<byte[]> publicPhoto(@PathVariable String slug, @PathVariable UUID fileId) {
        Map<String, Object> b = business(slug);
        FileService.Stored f = TenantContext.runAs((UUID) b.get("id"), null, () -> tx.execute(x -> {
            if (!toLet.isListedPhoto(fileId)) {
                throw ApiException.notFound("Photo");
            }
            return files.load(fileId).orElseThrow(() -> ApiException.notFound("Photo"));
        }));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic()).body(f.bytes());
    }

    @PostMapping("/api/public/to-let/{slug}/enquire")
    public Map<String, Object> enquire(@PathVariable String slug, @RequestBody Map<String, Object> raw, HttpServletRequest req) {
        if (!ENQUIRIES.allow(req.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "You've sent several enquiries. Please call the office instead.");
        }
        Map<String, Object> b = business(slug);
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'OWNER' ORDER BY created_at LIMIT 1", UUID.class, b.get("id"));
        return TenantContext.runAs((UUID) b.get("id"), owner, () -> tx.execute(x -> toLet.enquire(new Body(raw))));
    }
}
