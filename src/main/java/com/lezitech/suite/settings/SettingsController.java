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
    @org.springframework.beans.factory.annotation.Autowired
    private PlanBilling billing;

    /** Each plan with the keys of the extra tools it includes (products are never limited, so no product count). */
    private List<Map<String, Object>> plansWithTools() {
        return plansWithTools(null);
    }

    /** The same with the names a kind of business sees (null: the plans' own names). The code stays. */
    private List<Map<String, Object>> plansWithTools(String sector) {
        return platform.plans().stream().map(p -> {
            // price = normal, priceNow = after the plan offer (offerLabel, offerEnds say what it is and when it ends)
            Map<String, Object> m = platform.withPriceNow(p);
            m.remove("parts");
            m.remove("items");
            if (sector != null) {
                m.put("name", platform.planName(sector, (String) p.get("code")));
            }
            m.put("tools", platform.planFeatures((String) p.get("code")));
            return m;
        }).toList();
    }

    /** The plans for this business, each with what it would pay now (any offer taken off). */
    private List<Map<String, Object>> plansForTenant() {
        java.util.UUID t = TenantContext.tenantId();
        String sector = jdbc.queryForList("SELECT sector FROM tenants WHERE id = ?", String.class, t).stream().findFirst().orElse(null);
        Map<String, Object> kind = platform.kindPlan(t);
        if (kind != null) {
            // One plan, made for this kind of business: its name, its price, and the offer taken off.
            Map<String, Object> m = platform.kindPlanView(kind);
            m.remove("parts");
            m.remove("items");
            m.put("tools", platform.planFeatures((String) kind.get("plan")));
            var ctx1 = offers.context(t);
            var month = offers.price(ctx1, (String) kind.get("plan"), "MONTHLY");
            var year = offers.price(ctx1, (String) kind.get("plan"), "ANNUAL");
            m.put("pay", month.amount());
            m.put("payYear", year.amount());
            m.put("offer", month.offerTitle() != null ? month.offerTitle() : year.offerTitle());
            return List.of(m);
        }
        var ctx = offers.context(t);
        return plansWithTools(sector).stream().map(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>(p);
            var month = offers.price(ctx, (String) p.get("code"), "MONTHLY");
            var year = offers.price(ctx, (String) p.get("code"), "ANNUAL");
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
        out.put("plan", Map.of("code", plan.get("code"), "name", platform.planName(t, (String) plan.get("code"))));
        out.put("nextBill", billing.nextBill(t, "MONTHLY"));
        out.put("pay", price.amount());
        out.put("full", price.full());
        out.put("offer", price.offerTitle());
        out.put("needs", planFeatures.needed(t));
        out.put("onePlan", platform.kindPlan(t) != null);
        out.put("offers", offers.forTenant(t));
        return out;
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SectorCatalog sectors;
    @org.springframework.beans.factory.annotation.Autowired
    private PlanLimits limits;

    @org.springframework.beans.factory.annotation.Autowired
    private PlanActivation planActivation;
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
        var modules = pack.get("modules").deepCopy();
        SectorCatalog.fitModulesToFeatures((com.fasterxml.jackson.databind.node.ArrayNode) modules, pack.get("features"));
        patch.set("modules", modules);
        patch.set("features", pack.get("features"));
        jdbc.update("UPDATE tenants SET settings = settings || cast(? as jsonb) WHERE id = ?", patch.toString(), TenantContext.tenantId());
        return Map.of("ok", true);
    }

    /** The plans and prices, for the sign-up screen ("this fits the Growth plan, KES 4,500 a month after the trial"). */
    @GetMapping("/api/public/plans")
    public Map<String, Object> publicPlans(@org.springframework.web.bind.annotation.RequestParam(required = false) String sector) {
        return Map.of("plans", plansWithTools(sector == null || sector.isBlank() ? null : sector), "tools", planFeatures.describe(), "trialDays", platform.trialDays());
    }

    @GetMapping("/api/billing/plans")
    @org.springframework.transaction.annotation.Transactional(readOnly = true) // usage counts need the workspace's row-level security set
    public Map<String, Object> plans() {
        TenantContext.caller();
        java.util.List<String> parts = jdbc.queryForList("SELECT unnest(products) FROM tenants WHERE id = ?", String.class, TenantContext.tenantId());
        // Extra lines already on the next bill (moving up mid-period) are charged with whichever plan is paid for.
        long extra = billing.quote(TenantContext.tenantId(), billing.billPlan(TenantContext.tenantId()), "MONTHLY").extra();
        return Map.ofEntries(Map.entry("plans", plansForTenant()), Map.entry("tools", planFeatures.describe()), Map.entry("needs", planFeatures.needed(TenantContext.tenantId())),
                Map.entry("fits", planFeatures.fittingPlan(TenantContext.tenantId()).get("code")), Map.entry("trialDays", platform.trialDays()), Map.entry("usage", limits.usage()), Map.entry("parts", parts),
                Map.entry("extra", extra), Map.entry("nextBill", billing.nextBill(TenantContext.tenantId(), "MONTHLY")),
                Map.entry("requests", jdbc.queryForList("""
                SELECT plan, billing_cycle, amount, status, created_at FROM subscription_requests
                WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 5""", TenantContext.tenantId())));
    }

    /** The next bill: its date and every line, and what moving to another plan would do (the plan sheet). */
    @GetMapping("/api/billing/next-bill")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> nextBill(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "MONTHLY") String cycle) {
        TenantContext.caller();
        return billing.nextBill(TenantContext.tenantId(), "ANNUAL".equalsIgnoreCase(cycle) ? "ANNUAL" : "MONTHLY");
    }

    @GetMapping("/api/billing/plan-change/preview")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> previewChange(@org.springframework.web.bind.annotation.RequestParam(required = false) String feature,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) String limit,
                                             @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int adding,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) String toPlan) {
        TenantContext.caller();
        Map<String, Object> out = new java.util.LinkedHashMap<>(billing.preview(TenantContext.tenantId(), feature, limit, adding, toPlan));
        out.put("owner", "OWNER".equals(TenantContext.caller().role()));
        return out;
    }

    /** Move to another plan. The sheet's "Yes" button sends this; see {@link PlanBilling#change}. */
    @PostMapping("/api/billing/plan-change")
    public Map<String, Object> changePlan(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        return billing.change(b.str("plan"), b.str("reason", null));
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
        Map<String, Object> kindPlan = platform.kindPlan(TenantContext.tenantId());
        if (kindPlan != null && !plan.equals(kindPlan.get("plan"))) {
            throw ApiException.bad("Your plan is " + kindPlan.get("name") + ". It is the only plan for your kind of business, so choose that one.");
        }
        platform.plans().stream().filter(x -> x.get("code").equals(plan)).findFirst()
                .orElseThrow(() -> ApiException.bad("Unknown plan"));
        // A paid business moves to another plan through the plan sheet first, so the bill has the right lines.
        Map<String, Object> own = jdbc.queryForMap("SELECT status, plan, next_plan, paid_until FROM tenants WHERE id = ?", TenantContext.tenantId());
        java.sql.Timestamp until = (java.sql.Timestamp) own.get("paid_until");
        String billed = own.get("next_plan") != null ? (String) own.get("next_plan") : (String) own.get("plan");
        if ("ACTIVE".equals(own.get("status")) && billed != null && !plan.equals(billed)) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "MOVE_FIRST", "To pay for " + platform.planName(TenantContext.tenantId(), plan) + ", move to it first. Open Your plan and choose it, and the next bill will be worked out for you.");
        }
        // The price after the best live offer for this business, plus any extra lines already on the next bill: one total.
        var quote = billing.quote(TenantContext.tenantId(), plan, cycle);
        var price = quote.price();
        BigDecimal amount = BigDecimal.valueOf(quote.total());
        // The page asks for the price it showed. If it changed since (an offer ended or began), say so with the new price and ask again.
        if (raw.get("expectedAmount") != null) {
            BigDecimal shown;
            try {
                shown = new BigDecimal(String.valueOf(raw.get("expectedAmount")));
            } catch (NumberFormatException e) {
                throw ApiException.bad("Reload the page and try again");
            }
            if (shown.compareTo(amount) != 0) {
                throw ApiException.conflict("The price changed, here is the new price: KES " + String.format("%,d", quote.total()) + ".")
                        .with("amount", amount).with("full", price.full()).with("offer", price.offerTitle())
                        .with("lines", quote.lines().stream().map(PlanBilling.Line::map).toList());
            }
        }
        if (amount.signum() == 0 && price.credit() == 0) {
            throw ApiException.bad("This plan has no price to pay right now, so nothing was asked of you. Please try again later or WhatsApp us.");
        }
        // Waiting requests older than 48 hours are closed: a new one is made at today's price.
        planActivation.expireStale();
        java.util.UUID id = jdbc.queryForObject("""
                INSERT INTO subscription_requests (tenant_id, plan, billing_cycle, amount, mpesa_phone, offer_id, credit_used, lines) VALUES (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb)) RETURNING id""",
                java.util.UUID.class, TenantContext.tenantId(), plan, cycle, amount, b.str("mpesaPhone", null), price.offerId(), BigDecimal.valueOf(price.credit()),
                billing.linesJson(quote));
        if (amount.signum() == 0 && price.credit() > 0) {
            // Thank-you credit covers it all: nothing to pay, the plan is on now.
            planActivation.activate(id, "CREDIT", null);
            return Map.of("ok", true, "amount", amount, "requestId", id, "paid", true,
                    "message", "Paid with your thank-you credit. Your " + quote.planName() + " plan is on.");
        }
        return Map.of("ok", true, "amount", amount, "requestId", id,
                "message", "Request received. Pay KES " + amount.toPlainString()
                        + " to LeziTech and we activate your plan within one working hour.");
    }
}
