package com.lezitech.suite.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Workspace branding and switches, plus plans and upgrade requests. Tenants has no RLS: always filter by id. */
@RestController
public class SettingsController {

    /** Monthly KES prices; annual prepay gets two months free. */
    public static final List<Map<String, Object>> PLANS = List.of(
            Map.of("code", "LITE", "name", "Lite", "monthly", 2000, "users", 2, "locations", 1,
                    "blurb", "One product (POS, CRM or ERP), 1 location, 2 users"),
            Map.of("code", "GROWTH", "name", "Growth", "monthly", 4500, "users", 8, "locations", 3,
                    "blurb", "Any two products, 3 locations, 8 users, spreadsheet import"),
            Map.of("code", "PRO", "name", "Pro", "monthly", 9000, "users", 30, "locations", 10,
                    "blurb", "POS + CRM + ERP, 10 locations, 30 users, priority support"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SectorCatalog sectors;

    public SettingsController(JdbcTemplate jdbc, ObjectMapper json, SectorCatalog sectors) {
        this.jdbc = jdbc;
        this.json = json;
        this.sectors = sectors;
    }

    @PutMapping("/api/settings")
    public Map<String, Object> update(@RequestBody Map<String, Object> raw) throws Exception {
        TenantContext.requireRole("OWNER");
        Body b = new Body(raw);
        String color = b.str("brandColor", null);
        if (color != null && !color.matches("^#[0-9a-fA-F]{6}$")) {
            throw ApiException.bad("Brand colour must look like #0f766e");
        }
        String logo = b.str("logoUrl", null);
        if (logo != null && logo.length() > 400_000) {
            throw ApiException.bad("Logo is too large; use an image under 300 KB");
        }
        jdbc.update("""
                UPDATE tenants SET name = coalesce(?, name), brand_color = coalesce(?, brand_color), logo_url = ?,
                       phone = ?, sub_sector = coalesce(?, sub_sector),
                       settings = settings || cast(? as jsonb)
                WHERE id = ?""",
                b.str("name", null), color, logo, b.str("phone", null), b.str("subSector", null),
                json.writeValueAsString(b.map("settings")), TenantContext.tenantId());
        return Map.of("ok", true);
    }

    /** Re-apply the sector pack's screens and switches (after experimenting in Settings). */
    @PostMapping("/api/settings/reset-modules")
    public Map<String, Object> resetModules() {
        TenantContext.requireRole("OWNER");
        String sector = jdbc.queryForObject("SELECT sector FROM tenants WHERE id = ?", String.class, TenantContext.tenantId());
        var pack = sectors.get(sector);
        var patch = json.createObjectNode();
        patch.set("modules", pack.get("modules"));
        patch.set("features", pack.get("features"));
        jdbc.update("UPDATE tenants SET settings = settings || cast(? as jsonb) WHERE id = ?", patch.toString(), TenantContext.tenantId());
        return Map.of("ok", true);
    }

    @GetMapping("/api/billing/plans")
    public Map<String, Object> plans() {
        TenantContext.caller();
        return Map.of("plans", PLANS, "requests", jdbc.queryForList("""
                SELECT plan, billing_cycle, amount, status, created_at FROM subscription_requests
                WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 5""", TenantContext.tenantId()));
    }

    /**
     * Record that the owner wants to subscribe. The operator confirms the M-Pesa payment and
     * activates the plan from the platform console (no money moves through this endpoint).
     */
    @PostMapping("/api/billing/subscribe")
    public Map<String, Object> subscribe(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        Body b = new Body(raw);
        String plan = b.str("plan").toUpperCase();
        String cycle = b.str("cycle", "MONTHLY").toUpperCase();
        Map<String, Object> p = PLANS.stream().filter(x -> x.get("code").equals(plan)).findFirst()
                .orElseThrow(() -> ApiException.bad("Unknown plan"));
        int monthly = (Integer) p.get("monthly");
        BigDecimal amount = BigDecimal.valueOf("ANNUAL".equals(cycle) ? monthly * 10L : monthly);
        java.util.UUID id = jdbc.queryForObject("""
                INSERT INTO subscription_requests (tenant_id, plan, billing_cycle, amount, mpesa_phone) VALUES (?, ?, ?, ?, ?) RETURNING id""",
                java.util.UUID.class, TenantContext.tenantId(), plan, cycle, amount, b.str("mpesaPhone", null));
        return Map.of("ok", true, "amount", amount, "requestId", id,
                "message", "Request received. Pay KES " + amount.toPlainString()
                        + " to Lezitech and we activate your plan within one working hour.");
    }
}
