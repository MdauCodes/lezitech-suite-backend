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

/** Public sector list for the signup page, and the LeziTech operator's tenant console. */
@RestController
public class PlatformController {

    private final JdbcTemplate jdbc;
    private final SectorCatalog sectors;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.settings.PlanBilling billing;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.admin.OversightActions actions;
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

    /**
     * Activate a plan for 1 or 12 months. Same rules as the control panel: a reason is required, it is recorded as a payment at the next
     * bill's amount (so the period, proration lines and the unique payment code all apply), audited, and the business is told.
     */
    @PostMapping("/api/platform/tenants/{id}/activate")
    public Map<String, Object> activate(@RequestHeader(value = "X-Operator-Key", required = false) String key, @PathVariable UUID id,
                                        @RequestBody Map<String, Object> raw) {
        guard(key);
        return asOperator(() -> actions.operatorActivate(id, raw, OPERATOR));
    }

    /** Give 1 to 90 free days (a trial is extended, a paying business's next bill moves). A reason is required. */
    @PostMapping("/api/platform/tenants/{id}/extend-trial")
    public Map<String, Object> extend(@RequestHeader(value = "X-Operator-Key", required = false) String key, @PathVariable UUID id,
                                      @RequestBody Map<String, Object> raw) {
        guard(key);
        return asOperator(() -> actions.freeDays(id, raw, OPERATOR));
    }

    /** Pause a business. A reason is required; the owner is told. */
    @PostMapping("/api/platform/tenants/{id}/suspend")
    public Map<String, Object> suspend(@RequestHeader(value = "X-Operator-Key", required = false) String key, @PathVariable UUID id,
                                       @RequestBody(required = false) Map<String, Object> raw) {
        guard(key);
        return asOperator(() -> actions.suspend(id, raw, OPERATOR));
    }

    private static final String OPERATOR = "Operator console";

    /** The operator key stands in for a superadmin for one call, so the audit and notices name "Operator console". */
    private <T> T asOperator(java.util.function.Supplier<T> work) {
        com.lezitech.suite.admin.AdminContext.set(new com.lezitech.suite.admin.AdminContext.Admin(null, "SUPERADMIN", OPERATOR));
        try {
            return work.get();
        } finally {
            com.lezitech.suite.admin.AdminContext.clear();
        }
    }

    private void guard(String key) {
        if (key == null || !MessageDigest.isEqual(operatorKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Operator key required");
        }
    }
}
