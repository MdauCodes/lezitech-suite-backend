package com.lezitech.suite.integrations;

import com.lezitech.suite.admin.AdminContext;
import com.lezitech.suite.admin.AdminService;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.mpesa.MpesaConfig;
import com.lezitech.suite.mpesa.PayHeroFees;
import com.lezitech.suite.mpesa.PayHeroGateway;
import java.math.BigDecimal;
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
    private final PayHeroFees fees;
    private final PayHeroGateway payHero;

    public IntegrationController(IntegrationService integrations, AdminService admin, MpesaConfig cfg, JdbcTemplate jdbc, PayHeroFees fees,
                                 PayHeroGateway payHero) {
        this.payHero = payHero;
        this.integrations = integrations;
        this.admin = admin;
        this.cfg = cfg;
        this.jdbc = jdbc;
        this.fees = fees;
    }

    // ---------- PayHero, guided for the owner (no channel numbers to hunt for) ----------

    private static String digits(Object v) {
        return String.valueOf(v == null ? "" : v).replaceAll("[^0-9]", "");
    }

    /** Save the two PayHero keys, then show the tills and paybills PayHero has for this account, and the wallet. */
    @PostMapping("/api/settings/payments/payhero/keys")
    public Map<String, Object> payHeroKeys(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        String user = String.valueOf(raw.getOrDefault("username", "")).trim();
        String pass = String.valueOf(raw.getOrDefault("password", "")).trim();
        if (user.isEmpty() || pass.isEmpty()) {
            throw ApiException.bad("Paste both the API username and the API password from PayHero");
        }
        integrations.save(TenantContext.tenantId(), "PAYHERO", Map.of("secrets", Map.of("username", user, "password", pass)), TenantContext.caller().name());
        return payHeroAccount();
    }

    /** What PayHero has for this business: its tills/paybills and the wallet balance. */
    @GetMapping("/api/settings/payments/payhero/account")
    public Map<String, Object> payHeroAccount() {
        TenantContext.requireRole("OWNER");
        var keys = integrations.payHeroKeys(TenantContext.tenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channels", payHero.channels(keys).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("type", c.type());
            m.put("shortCode", c.shortCode());
            m.put("accountNumber", c.accountNumber());
            m.put("description", c.description());
            m.put("active", c.active());
            return m;
        }).toList());
        var w = payHero.wallet(keys);
        out.put("walletBalance", w.balance());
        return out;
    }

    /** Use this till or paybill for the till's M-Pesa prompts: saved, switched on and tested in one go. */
    @PostMapping("/api/settings/payments/payhero/use")
    public Map<String, Object> payHeroUse(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        String channel = digits(raw.get("channelId"));
        if (channel.isEmpty()) {
            throw ApiException.bad("Choose which till or paybill the money goes to");
        }
        integrations.save(TenantContext.tenantId(), "PAYHERO", Map.of("settings", Map.of("channelId", channel), "enabled", true), TenantContext.caller().name());
        return integrations.test(TenantContext.tenantId(), "PAYHERO");
    }

    /** Add the business's till or paybill to PayHero from here, when it isn't there yet. */
    @PostMapping("/api/settings/payments/payhero/add-till")
    public Map<String, Object> payHeroAddTill(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        String type = String.valueOf(raw.getOrDefault("type", "till")).toLowerCase(Locale.ROOT);
        if (!List.of("till", "paybill").contains(type)) {
            throw ApiException.bad("Choose till or paybill");
        }
        String number = digits(raw.get("number"));
        if (number.length() < 5) {
            throw ApiException.bad("Type the " + type + " number customers pay to");
        }
        String account = String.valueOf(raw.getOrDefault("accountNumber", "")).trim();
        String name = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, TenantContext.tenantId());
        var keys = integrations.payHeroKeys(TenantContext.tenantId());
        var c = payHero.addChannel(keys, payHero.wallet(keys).accountId(), type, number, account, name + " (Busynez)");
        return Map.of("id", c.id(), "shortCode", c.shortCode());
    }

    /** Top up the PayHero wallet: an M-Pesa prompt goes to the owner's phone. */
    @PostMapping("/api/settings/payments/payhero/top-up")
    public Map<String, Object> payHeroTopUp(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        long amount;
        try {
            amount = new BigDecimal(String.valueOf(raw.get("amount"))).longValue();
        } catch (RuntimeException e) {
            throw ApiException.bad("Type how much to put in the PayHero wallet");
        }
        if (amount < 10) {
            throw ApiException.bad("Put at least KES 10 in the PayHero wallet");
        }
        String phone = digits(raw.get("phone"));
        if (phone.startsWith("254")) {
            phone = "0" + phone.substring(3);
        }
        if (phone.length() != 10) {
            throw ApiException.bad("Type the M-Pesa number to send the prompt to, like 0712 345 678");
        }
        var keys = integrations.payHeroKeys(TenantContext.tenantId());
        return Map.of("reference", payHero.topUp(keys, amount, phone));
    }

    /**
     * How the business treats PayHero's charge: KNOW (just show it), COST (take it off profit in reports), or CUSTOMER
     * (add it to the customer's bill as its own line when the prompt is sent; it comes off again if the prompt fails).
     */
    public static final List<String> FEE_MODES = List.of("KNOW", "COST", "CUSTOMER");

    /** PayHero's charge per payment, with a few everyday amounts worked out, and how this business treats it. */
    @GetMapping("/api/settings/payments/payhero-fees")
    public Map<String, Object> payHeroFees() {
        Map<String, Object> out = new LinkedHashMap<>(fees.table());
        List<Map<String, Object>> examples = new java.util.ArrayList<>();
        for (int a : new int[]{100, 500, 1000, 2500, 5000, 10000, 50000}) {
            examples.add(Map.of("amount", a, "fee", fees.fee(BigDecimal.valueOf(a))));
        }
        out.put("examples", examples);
        String mode = jdbc.queryForObject("SELECT settings->>'mpesaFeeMode' FROM tenants WHERE id = ?", String.class, TenantContext.tenantId());
        out.put("mode", mode == null ? "KNOW" : mode);
        return out;
    }

    @PutMapping("/api/settings/payments/fee-mode")
    public Map<String, Object> feeMode(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        String mode = String.valueOf(raw.get("mode")).toUpperCase(Locale.ROOT);
        if (!FEE_MODES.contains(mode)) {
            throw ApiException.bad("Choose how to treat PayHero's charge");
        }
        jdbc.update("UPDATE tenants SET settings = settings || jsonb_build_object('mpesaFeeMode', ?::text) WHERE id = ?", mode, TenantContext.tenantId());
        return Map.of("mode", mode);
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
            throw ApiException.notFound("Business");
        }
    }
}
