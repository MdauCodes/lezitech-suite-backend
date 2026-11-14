package com.lezitech.suite.notify;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A business's logo at a plain web address, for emails: mail apps block logos embedded in the email itself, so the
 * email links here instead.
 */
@RestController
public class LogoController {

    private final JdbcTemplate jdbc;

    public LogoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/public/logo/{slug}")
    public ResponseEntity<byte[]> logo(@PathVariable String slug) {
        List<String> rows = jdbc.queryForList("SELECT logo_url FROM tenants WHERE slug = ? AND status <> 'SUSPENDED'", String.class, slug);
        String logo = rows.isEmpty() ? null : rows.get(0);
        if (logo == null || logo.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (logo.startsWith("http")) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(logo)).build();
        }
        int comma = logo.indexOf(',');
        if (!logo.startsWith("data:image/") || comma < 0 || !logo.substring(0, comma).endsWith(";base64")) {
            return ResponseEntity.notFound().build();
        }
        String type = logo.substring(5, logo.indexOf(';'));
        if (!List.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(type)) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(logo.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
