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
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlatformSettings platform;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlanFeatures planFeatures;

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.Offers offers;

    /** Each plan with the keys of the extra tools it includes (products are never limited, so no product count). */
    private List<Map<String, Object>> plansWithTools() {
        return platform.plans().stream().map(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>(p);
            m.remove("parts");
            m.remove("items");
            m.put("tools", platform.planFeatures((String) p.get("code")));
            return m;
        }).toList();
    }

    /** The plans for this business, each with what it would pay now (any offer taken off). */
    private List<Map<String, Object>> plansForTenant() {
        java.util.UUID t = TenantContext.tenantId();
        return plansWithTools().stream().map(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>(p);
            var month = offers.price(t, (String) p.get("code"), "MONTHLY");
            var year = offers.price(t, (String) p.get("code"), "ANNUAL");
            m.put("pay", month.amount());
            m.put("payYear", year.amount());
            m.put("offer", month.offerTitle() != null ? month.offerTitle() : year.offerTitle());
            return m;
        }).toList();
    }

    /**
     * "Keep everything running": the plan that holds what this business set up (its tools, staff and branches),
     * what that costs now, and the offers meant for it. Shown on the dashboard as the free days run out.
     */
    @GetMapping("/api/billing/keep")
    public Map<String, Object> keep() {
        java.util.UUID t = TenantContext.tenantId();
        Map<String, Object> plan = planFeatures.fittingPlan(t);
        var price = offers.price(t, (String) plan.get("code"), "MONTHLY");
        Map<String, Object> tenant = jdbc.queryForMap("SELECT status, trial_ends_at, paid_until, phone FROM tenants WHERE id = ?", t);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", tenant.get("status"));
        out.put("trialEndsAt", ((java.sql.Timestamp) tenant.get("trial_ends_at")).toInstant().toString());
        out.put("paidUntil", tenant.get("paid_until") == null ? null : ((java.sql.Timestamp) tenant.get("paid_until")).toInstant().toString());
        out.put("phone", tenant.get("phone"));
        out.put("plan", Map.of("code", plan.get("code"), "name", plan.get("name")));
        out.put("pay", price.amount());
        out.put("full", price.full());
        out.put("offer", price.offerTitle());
        out.put("needs", planFeatures.needed(t));
        out.put("offers", offers.forTenant(t));
        return out;
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SectorCatalog sectors;
    @org.springframework.beans.factory.annotation.Autowired
    private PlanLimits limits;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.config.AccessPolicy accessPolicy;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.activity.ActivityLog activity;

    public SettingsController(JdbcTemplate jdbc, ObjectMapper json, SectorCatalog sectors) {
        this.jdbc = jdbc;
        this.json = json;
        this.sectors = sectors;
    }

    @PutMapping("/api/settings")
    @org.springframework.transaction.annotation.Transactional // the activity log entry needs the business's row-level security set
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
        Map<String, Object> settings = new java.util.LinkedHashMap<>(b.map("settings"));
        if (settings.containsKey("access")) {
            settings.put("access", com.lezitech.suite.config.AccessPolicy.clean(settings.get("access"), com.lezitech.suite.team.TeamController.ROLES));
            String before = jdbc.queryForObject("SELECT coalesce(settings->'access', '{}'::jsonb)::text FROM tenants WHERE id = ?", String.class, TenantContext.tenantId());
            String after = json.writeValueAsString(settings.get("access"));
            if (!json.readTree(before).equals(json.readTree(after))) {
                activity.record("ACCESS_CHANGE", com.lezitech.suite.activity.ActivityLog.NOTICE, "Changed who can do what", null, null, null, null,
                        Map.of("before", before, "after", after));
            }
        }
        if (settings.containsKey("ordering")) {
            // Shallow merge in the database would drop choices not sent, so merge with what's saved first.
            Map<String, Object> saved = new java.util.LinkedHashMap<>(json.readValue(jdbc.queryForObject(
                    "SELECT coalesce(settings->'ordering', '{}'::jsonb)::text FROM tenants WHERE id = ?", String.class, TenantContext.tenantId()), Map.class));
            saved.putAll(com.lezitech.suite.restaurant.OrderingSettings.clean(settings.get("ordering")));
            settings.put("ordering", saved);
        }
        if (settings.containsKey("loyalty")) {
            settings.put("loyalty", com.lezitech.suite.restaurant.OrderingSettings.cleanLoyalty(settings.get("loyalty")));
        }
        if (raw.get("products") instanceof java.util.List<?> list) {
            java.util.List<String> products = list.stream().map(String::valueOf).map(String::toUpperCase)
                    .filter(java.util.Set.of("POS", "CRM", "ERP")::contains).distinct().toList();
            if (products.isEmpty()) {
                throw ApiException.bad("Keep at least one part switched on");
            }
            // Every plan may use every part; plans differ by their extra tools (PlanFeatures), not by parts (16 Nov).
            jdbc.update("UPDATE tenants SET products = cast(? as text[]) WHERE id = ?", "{" + String.join(",", products) + "}", TenantContext.tenantId());
        }
        accessPolicy.forget(TenantContext.tenantId());
        jdbc.update("""
                UPDATE tenants SET name = coalesce(?, name), brand_color = coalesce(?, brand_color), logo_url = ?,
                       phone = ?, sub_sector = coalesce(?, sub_sector),
                       settings = settings || cast(? as jsonb)
                WHERE id = ?""",
                b.str("name", null), color, logo, b.str("phone", null), b.str("subSector", null),
                json.writeValueAsString(settings), TenantContext.tenantId());
        return Map.of("ok", true);
    }

    /** Ordering from the table and tips, with defaults filled in (any staff member: the till and menu use them). */
    @org.springframework.web.bind.annotation.GetMapping("/api/settings/ordering")
    public Map<String, Object> ordering() throws Exception {
        var t = jdbc.queryForMap("SELECT sector, coalesce(settings->'ordering', '{}'::jsonb)::text AS o FROM tenants WHERE id = ?", TenantContext.tenantId());
        return com.lezitech.suite.restaurant.OrderingSettings.withDefaults(json.readValue((String) t.get("o"), Map.class), (String) t.get("sector"));
    }

    /** Re-apply the sector pack's screens and switches (after experimenting in Settings). */
    @PostMapping("/api/settings/reset-modules")
    public Map<String, Object> resetModules() {
        TenantContext.requireRole("OWNER");
        var t = jdbc.queryForMap("SELECT sector, sub_sector FROM tenants WHERE id = ?", TenantContext.tenantId());
        var pack = sectors.get((String) t.get("sector"), (String) t.get("sub_sector"));
        var patch = json.createObjectNode();
        patch.set("modules", pack.get("modules"));
        patch.set("features", pack.get("features"));
        jdbc.update("UPDATE tenants SET settings = settings || cast(? as jsonb) WHERE id = ?", patch.toString(), TenantContext.tenantId());
        return Map.of("ok", true);
    }

    /** The plans and prices, for the sign-up screen ("this fits the Growth plan, KES 4,500 a month after the trial"). */
    @GetMapping("/api/public/plans")
    public Map<String, Object> publicPlans() {
        return Map.of("plans", plansWithTools(), "tools", planFeatures.describe(), "trialDays", platform.trialDays());
    }

    @GetMapping("/api/billing/plans")
    @org.springframework.transaction.annotation.Transactional(readOnly = true) // usage counts need the workspace's row-level security set
    public Map<String, Object> plans() {
        TenantContext.caller();
        java.util.List<String> parts = jdbc.queryForList("SELECT unnest(products) FROM tenants WHERE id = ?", String.class, TenantContext.tenantId());
        return Map.of("plans", plansForTenant(), "tools", planFeatures.describe(), "needs", planFeatures.needed(TenantContext.tenantId()),
                "fits", planFeatures.fittingPlan(TenantContext.tenantId()).get("code"), "trialDays", platform.trialDays(), "usage", limits.usage(), "parts", parts, "requests", jdbc.queryForList("""
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
        platform.plans().stream().filter(x -> x.get("code").equals(plan)).findFirst()
                .orElseThrow(() -> ApiException.bad("Unknown plan"));
        // The price after the best live offer for this business.
        var price = offers.price(TenantContext.tenantId(), plan, cycle);
        BigDecimal amount = BigDecimal.valueOf(price.amount());
        java.util.UUID id = jdbc.queryForObject("""
                INSERT INTO subscription_requests (tenant_id, plan, billing_cycle, amount, mpesa_phone, offer_id) VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                java.util.UUID.class, TenantContext.tenantId(), plan, cycle, amount, b.str("mpesaPhone", null), price.offerId());
        return Map.of("ok", true, "amount", amount, "requestId", id,
                "message", "Request received. Pay KES " + amount.toPlainString()
                        + " to LeziTech and we activate your plan within one working hour.");
    }
}
