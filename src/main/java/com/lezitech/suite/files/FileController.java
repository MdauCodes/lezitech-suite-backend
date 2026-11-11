package com.lezitech.suite.files;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Showing uploaded files, and menu photos on items. ETR uploads live with the sale (package I). */
@RestController
public class FileController {

    private final FileService files;
    private final Db db;
    private final JdbcTemplate jdbc;

    public FileController(FileService files, Db db, JdbcTemplate jdbc) {
        this.files = files;
        this.db = db;
        this.jdbc = jdbc;
    }

    /** Any file of this business, for signed-in staff. */
    @GetMapping("/api/files/{id}")
    public ResponseEntity<byte[]> show(@PathVariable UUID id) {
        FileService.Stored f = files.load(id).orElseThrow(() -> ApiException.notFound("File"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate()).body(f.bytes());
    }

    /** Menu photos for the public menu. Only item photos are public; never ETRs. */
    @GetMapping("/api/public/files/{slug}/{id}")
    public ResponseEntity<byte[]> publicPhoto(@PathVariable String slug, @PathVariable UUID id) {
        List<UUID> tenant = jdbc.queryForList("SELECT id FROM tenants WHERE slug = ? AND status <> 'SUSPENDED'", UUID.class, slug);
        if (tenant.isEmpty()) {
            throw ApiException.notFound("Photo");
        }
        FileService.Stored f = TenantContext.runAs(tenant.get(0), null, () -> {
            if (!"ITEM_PHOTO".equals(files.purposeOf(id))) {
                throw ApiException.notFound("Photo");
            }
            return files.load(id).orElseThrow(() -> ApiException.notFound("Photo"));
        });
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic()).body(f.bytes());
    }

    /** Put a photo on an item (replacing the old one). */
    @PostMapping("/api/items/{id}/photo")
    @Transactional
    public Map<String, Object> setPhoto(@PathVariable UUID id, @RequestParam("file") MultipartFile upload) throws IOException {
        Object old = db.find("SELECT photo_file_id FROM items WHERE id = :id", "id", id)
                .orElseThrow(() -> ApiException.notFound("Item")).get("photoFileId");
        UUID file = files.save(FileService.Purpose.ITEM_PHOTO, upload.getBytes());
        db.exec("UPDATE items SET photo_file_id = :f WHERE id = :id", "f", file, "id", id);
        if (old != null) {
            files.remove(UUID.fromString(old.toString()));
        }
        return Map.of("ok", true, "photoFileId", file);
    }

    @DeleteMapping("/api/items/{id}/photo")
    @Transactional
    public Map<String, Object> removePhoto(@PathVariable UUID id) {
        Object old = db.find("SELECT photo_file_id FROM items WHERE id = :id", "id", id)
                .orElseThrow(() -> ApiException.notFound("Item")).get("photoFileId");
        db.exec("UPDATE items SET photo_file_id = NULL WHERE id = :id", "id", id);
        if (old != null) {
            files.remove(UUID.fromString(old.toString()));
        }
        return Map.of("ok", true);
    }
}
