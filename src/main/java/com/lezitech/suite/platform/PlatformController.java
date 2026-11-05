package com.lezitech.suite.platform;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.sector.SectorCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public sector list for the signup page, and the Lezitech operator's tenant console. */
@RestController
public class PlatformController {

    private final JdbcTemplate jdbc;
    private final SectorCatalog sectors;
    private final byte[] operatorKey;

    public PlatformController(JdbcTemplate jdbc, SectorCatalog sectors, @Value("${suite.operator-key}") String operatorKey) {
        this.jdbc = jdbc;
        this.sectors = sectors;
        this.operatorKey = operatorKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/public/sectors")
    public List<?> sectors() {
        List<String> off = jdbc.queryForList("""
                SELECT jsonb_array_elements_text(value) FROM platform_settings WHERE key = 'disabled_sectors'""", String.class);
        return sectors.all().stream().filter(p -> !off.contains(p.get("key").asText())).toList();
    }

    @GetMapping("/api/platform/tenants")
    public List<Map<String, Object>> tenants(@RequestHeader(value = "X-Operator-Key", required = false) String key) {
        guard(key);
        return jdbc.queryForList("""
                SELECT t.id, t.slug, t.name, t.sector, t.sub_sector, t.status, t.plan, t.trial_ends_at, t.paid_until, t.created_at,
                       (SELECT email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' ORDER BY created_at LIMIT 1) AS owner_email,
                       (SELECT count(*) FROM users u WHERE u.tenant_id = t.id) AS users,
                       (SELECT plan || ' ' || billing_cycle || ' ' || amount FROM subscription_requests r
                         WHERE r.tenant_id = t.id AND r.status = 'PENDING' ORDER BY created_at DESC LIMIT 1) AS pending_request
                FROM tenants t ORDER BY t.created_at DESC""");
    }

    /** Activate a paid plan for N months, or extend a trial by N days. */
    @PostMapping("/api/platform/tenants/{id}/activate")
    public Map<String, Object> activate(@RequestHeader(value = "X-Operator-Key", required = false) String key, @PathVariable UUID id,
                                        @RequestBody Map<String, Object> raw) {
        guard(key);
        Body b = new Body(raw);
        int months = b.integer("months", 1);
        jdbc.update("""
                UPDATE tenants SET status = 'ACTIVE', plan = ?,
                       paid_until = greatest(coalesce(paid_until, now()), now()) + make_interval(months => ?) WHERE id = ?""",
                b.str("plan").toUpperCase(), months, id);
        jdbc.update("UPDATE subscription_requests SET status = 'PAID' WHERE tenant_id = ? AND status = 'PENDING'", id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/platform/tenants/{id}/extend-trial")
    public Map<String, Object> extend(@RequestHeader(value = "X-Operator-Key", required = false) String key, @PathVariable UUID id,
                                      @RequestBody Map<String, Object> raw) {
        guard(key);
        int days = new Body(raw).integer("days", 7);
        jdbc.update("UPDATE tenants SET status = 'TRIAL', trial_ends_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plus(days, ChronoUnit.DAYS)), id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/platform/tenants/{id}/suspend")
    public Map<String, Object> suspend(@RequestHeader(value = "X-Operator-Key", required = false) String key, @PathVariable UUID id) {
        guard(key);
        jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", id);
        return Map.of("ok", true);
    }

    private void guard(String key) {
        if (key == null || !MessageDigest.isEqual(operatorKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Operator key required");
        }
    }
}
