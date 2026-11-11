package com.lezitech.suite.files;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Uploaded files for a business: menu photos and the business's own ETR (KRA receipt). The type is checked from the
 * file's first bytes, not its name; photos are shrunk in the browser before upload. Bytes go to the {@link FileStore},
 * a row in {@code files} (fenced to the business) says who owns them.
 */
@Service
@Transactional // row-level security is set per transaction
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    public enum Purpose {
        ITEM_PHOTO(2 * 1024 * 1024, "image/jpeg", "image/png", "image/webp"),
        ETR(6 * 1024 * 1024, "image/jpeg", "image/png", "application/pdf");

        final int maxBytes;
        final java.util.Set<String> types;

        Purpose(int maxBytes, String... types) {
            this.maxBytes = maxBytes;
            this.types = java.util.Set.of(types);
        }
    }

    public record Stored(UUID id, String contentType, byte[] bytes) {
    }

    private final Db db;
    private final FileStore store;

    public FileService(Db db,
                       @Value("${suite.files.store:disk}") String kind,
                       @Value("${suite.files.dir:./.files}") String dir,
                       @Value("${suite.files.r2.account-id:}") String account,
                       @Value("${suite.files.r2.access-key:}") String accessKey,
                       @Value("${suite.files.r2.secret-key:}") String secretKey,
                       @Value("${suite.files.r2.bucket:}") String bucket) {
        this.db = db;
        if ("r2".equalsIgnoreCase(kind)) {
            if (account.isBlank() || accessKey.isBlank() || secretKey.isBlank() || bucket.isBlank()) {
                throw new IllegalStateException("FILES_STORE=r2 needs R2_ACCOUNT_ID, R2_ACCESS_KEY, R2_SECRET_KEY and R2_BUCKET");
            }
            this.store = new R2FileStore(account, accessKey, secretKey, bucket);
        } else {
            this.store = new DiskFileStore(Path.of(dir));
        }
        log.info("Uploaded files are kept in {}", store.name());
    }

    /** Check and keep an upload for the signed-in business; returns the file id. */
    public UUID save(Purpose purpose, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.bad("The file is empty");
        }
        if (bytes.length > purpose.maxBytes) {
            throw ApiException.bad("The file is too big. Keep it under " + purpose.maxBytes / (1024 * 1024) + " MB");
        }
        String type = sniff(bytes);
        if (type == null || !purpose.types.contains(type)) {
            throw ApiException.bad(purpose == Purpose.ETR ? "Upload the ETR as a photo (JPG or PNG) or a PDF"
                    : "Upload a photo (JPG, PNG or WebP)");
        }
        UUID tenant = TenantContext.tenantId();
        String key = tenant + "/" + purpose.name().toLowerCase() + "/" + UUID.randomUUID() + ext(type);
        store.put(key, bytes, type);
        return db.insert("""
                INSERT INTO files (tenant_id, purpose, store_key, content_type, size_bytes, created_by)
                VALUES (:t, :purpose, :key, :type, :size, :u) RETURNING id""",
                "purpose", purpose.name(), "key", key, "type", type, "size", bytes.length);
    }

    /** A file of the current business (row-level security keeps other businesses' files out of reach). */
    public Optional<Stored> load(UUID id) {
        return db.find("SELECT id, store_key, content_type FROM files WHERE id = :id", "id", id).map(f -> {
            byte[] bytes = store.get((String) f.get("storeKey"));
            if (bytes == null) {
                throw ApiException.notFound("File");
            }
            return new Stored(id, (String) f.get("contentType"), bytes);
        });
    }

    public void remove(UUID id) {
        db.find("SELECT store_key FROM files WHERE id = :id", "id", id).ifPresent(f -> {
            db.exec("DELETE FROM files WHERE id = :id", "id", id);
            try {
                store.delete((String) f.get("storeKey"));
            } catch (RuntimeException e) {
                log.warn("Couldn't delete stored file {}: {}", id, e.getMessage());
            }
        });
    }

    public String purposeOf(UUID id) {
        return db.find("SELECT purpose FROM files WHERE id = :id", "id", id).map(f -> (String) f.get("purpose")).orElse(null);
    }

    /** The real type from the first bytes. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        return null;
    }

    private static String ext(String type) {
        return Map.of("image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp", "application/pdf", ".pdf").get(type);
    }
}
