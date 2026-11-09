package com.lezitech.suite.integrations;

import com.lezitech.suite.admin.AdminContext;
import com.lezitech.suite.admin.AdminService;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.mpesa.MpesaConfig;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Payments and SMS accounts, from two places: the business owner (Settings, Payments) and the LeziTech team
 * setting a business up from the control panel. Keys are write-only: screens only ever see the last four characters.
 */
@RestController
public class IntegrationController {

    private final IntegrationService integrations;
    private final AdminService admin;
    private final MpesaConfig cfg;
    private final JdbcTemplate jdbc;

    public IntegrationController(IntegrationService integrations, AdminService admin, MpesaConfig cfg, JdbcTemplate jdbc) {
        this.integrations = integrations;
        this.admin = admin;
        this.cfg = cfg;
        this.jdbc = jdbc;
    }

    // ---------- the owner ----------

    @GetMapping("/api/settings/payments")
    public Map<String, Object> mine() {
        TenantContext.requireRole("OWNER");
        return overview(TenantContext.tenantId());
    }

    @PutMapping("/api/settings/payments/{kind}")
    public Map<String, Object> save(@PathVariable String kind, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        return integrations.save(TenantContext.tenantId(), kind.toUpperCase(Locale.ROOT), raw, TenantContext.caller().name());
    }

    @PostMapping("/api/settings/payments/{kind}/test")
    public Map<String, Object> test(@PathVariable String kind) {
        TenantContext.requireRole("OWNER");
        return integrations.test(TenantContext.tenantId(), kind.toUpperCase(Locale.ROOT));
    }

    @DeleteMapping("/api/settings/payments/{kind}")
    public Map<String, Object> remove(@PathVariable String kind) {
        TenantContext.requireRole("OWNER");
        integrations.remove(TenantContext.tenantId(), kind.toUpperCase(Locale.ROOT));
        return Map.of("ok", true);
    }

    // ---------- the LeziTech team, setting a business up ----------

    @GetMapping("/api/admin/tenants/{id}/payments")
    public Map<String, Object> forTenant(@PathVariable UUID id) {
        AdminContext.require("payments");
        requireTenant(id);
        return overview(id);
    }

    @PutMapping("/api/admin/tenants/{id}/payments/{kind}")
    public Map<String, Object> saveForTenant(@PathVariable UUID id, @PathVariable String kind, @RequestBody Map<String, Object> raw) {
        AdminContext.require("payments");
        requireTenant(id);
        String k = kind.toUpperCase(Locale.ROOT);
        Map<String, Object> out = integrations.save(id, k, raw, "LeziTech: " + AdminContext.admin().name());
        // Never put keys in the audit log: only which fields changed.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", k);
        detail.put("enabled", out.get("enabled"));
        Object secrets = raw.get("secrets");
        if (secrets instanceof Map<?, ?> m) {
            detail.put("keysChanged", m.entrySet().stream().filter(e -> e.getValue() != null && !e.getValue().toString().isBlank())
                    .map(e -> e.getKey().toString()).toList());
        }
        admin.audit("payments.save", id, detail);
        return out;
    }

    @PostMapping("/api/admin/tenants/{id}/payments/{kind}/test")
    public Map<String, Object> testForTenant(@PathVariable UUID id, @PathVariable String kind) {
        AdminContext.require("payments");
        requireTenant(id);
        Map<String, Object> out = integrations.test(id, kind.toUpperCase(Locale.ROOT));
        admin.audit("payments.test", id, Map.of("kind", kind.toUpperCase(Locale.ROOT), "status", out.get("status")));
        return out;
    }

    @DeleteMapping("/api/admin/tenants/{id}/payments/{kind}")
    public Map<String, Object> removeForTenant(@PathVariable UUID id, @PathVariable String kind) {
        AdminContext.require("payments");
        requireTenant(id);
        integrations.remove(id, kind.toUpperCase(Locale.ROOT));
        admin.audit("payments.remove", id, Map.of("kind", kind.toUpperCase(Locale.ROOT)));
        return Map.of("ok", true);
    }

    private Map<String, Object> overview(UUID tenant) {
        List<Map<String, Object>> list = integrations.list(tenant);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("integrations", list);
        m.put("promptsOn", cfg.enabled());
        m.put("simulated", cfg.simulated());
        return m;
    }

    private void requireTenant(UUID id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, id);
        if (n == null || n == 0) {
            throw ApiException.notFound("Workspace");
        }
    }
}
