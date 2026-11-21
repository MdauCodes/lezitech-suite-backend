package com.lezitech.suite.growth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mall-tenant offer (phase E). LeziTech sets a discount per mall in the control panel (and, if it likes, a thank-you
 * credit in KSh for the landlord per tenant who pays); the landlord switches "Offer my tenants Busynez at a discount" on.
 * Tenants then see the offer on their tenant page and statements, with a Start free link carrying the mall's code;
 * a business that signs up with it keeps the discount on every payment and shows in the control panel under its mall.
 */
@RestController
public class MallOffer {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Referrals referrals;
    private final String siteUrl;

    public MallOffer(JdbcTemplate jdbc, ObjectMapper json, Referrals referrals,
                     @Value("${suite.site-url:https://lezitech.site/suite}") String siteUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.referrals = referrals;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
    }

    /** The mall's offer settings: percent and thank-you set by LeziTech, on/off by the landlord. */
    public record Settings(int percent, int thankYou, boolean on) {
        public boolean live() {
            return on && percent > 0;
        }
    }

    public Settings settings(UUID mall) {
        List<String> raw = jdbc.queryForList("SELECT settings->'mallOffer' #>> '{}' FROM tenants WHERE id = ? AND sector = 'property'", String.class, mall);
        if (raw.isEmpty() || raw.get(0) == null) {
            return new Settings(0, 0, false);
        }
        try {
            JsonNode n = json.readTree(raw.get(0));
            return new Settings(n.path("percent").asInt(0), n.path("thankYou").asInt(0), n.path("on").asBoolean(false));
        } catch (Exception e) {
            return new Settings(0, 0, false);
        }
    }

    private void save(UUID mall, Settings s) {
        jdbc.update("UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{mallOffer}', cast(? as jsonb)) WHERE id = ?",
                "{\"percent\":" + s.percent() + ",\"thankYou\":" + s.thankYou() + ",\"on\":" + s.on() + "}", mall);
    }

    /** The signup link tenants get: the website's Start free, carrying the mall's code. */
    public String link(UUID mall) {
        return siteUrl + "?mall=" + referrals.codeFor(mall);
    }

    // ---------- the landlord (Rent & tenants) ----------

    @GetMapping("/api/property/biznez-offer")
    @Transactional
    public Map<String, Object> mine() {
        UUID mall = TenantContext.tenantId();
        Settings s = settings(mall);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", s.percent() > 0);
        out.put("percent", s.percent());
        out.put("on", s.on());
        out.put("thankYou", s.thankYou());
        out.put("link", s.percent() > 0 ? link(mall) : null);
        out.put("joined", jdbc.queryForObject("SELECT count(*) FROM tenants WHERE mall_id = ?", Long.class, mall));
        out.put("paid", jdbc.queryForObject("SELECT count(*) FROM tenants WHERE mall_id = ? AND status = 'ACTIVE'", Long.class, mall));
        return out;
    }

    @PutMapping("/api/property/biznez-offer")
    @Transactional
    public Map<String, Object> switchOn(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        UUID mall = TenantContext.tenantId();
        Settings s = settings(mall);
        if (s.percent() <= 0) {
            throw ApiException.bad("This offer isn't open for your building yet. Ask LeziTech on WhatsApp to set it up.");
        }
        save(mall, new Settings(s.percent(), s.thankYou(), new Body(raw).bool("on", false)));
        return mine();
    }

    // ---------- tenants (their page and statements; no login needed for the link itself) ----------

    @GetMapping("/api/public/mall-offer/{slug}")
    public Map<String, Object> forTenants(@PathVariable String slug) {
        List<UUID> found = jdbc.queryForList("SELECT id FROM tenants WHERE slug = ? AND sector = 'property' AND status <> 'SUSPENDED'", UUID.class, slug);
        if (found.isEmpty()) {
            return Map.of("live", false);
        }
        Settings s = settings(found.get(0));
        if (!s.live()) {
            return Map.of("live", false);
        }
        return Map.of("live", true, "percent", s.percent(), "link", link(found.get(0)));
    }

    // ---------- signup and payment ----------

    /** A new business that signed up with a mall's code gets that mall's discount (if the offer is on). */
    public void linkSignup(UUID newTenant, String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        List<UUID> malls = jdbc.queryForList("SELECT id FROM tenants WHERE referral_code = ? AND sector = 'property' AND NOT is_demo",
                UUID.class, code.trim().toUpperCase());
        if (malls.isEmpty() || malls.get(0).equals(newTenant)) {
            return;
        }
        Settings s = settings(malls.get(0));
        if (s.live()) {
            jdbc.update("UPDATE tenants SET mall_id = ?, mall_discount = ? WHERE id = ? AND mall_id IS NULL", malls.get(0), s.percent(), newTenant);
        }
    }

    /** On a mall tenant's first payment, the landlord gets the thank-you credit LeziTech set (once per tenant). */
    public void thankOnFirstPayment(UUID tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT mall_id FROM tenants WHERE id = ? AND mall_id IS NOT NULL AND mall_thanked_at IS NULL FOR UPDATE", tenant);
        if (rows.isEmpty()) {
            return;
        }
        UUID mall = (UUID) rows.get(0).get("mall_id");
        jdbc.update("UPDATE tenants SET mall_thanked_at = now() WHERE id = ?", tenant);
        int thanks = settings(mall).thankYou();
        if (thanks > 0) {
            jdbc.update("UPDATE tenants SET billing_credit = billing_credit + ? WHERE id = ?", BigDecimal.valueOf(thanks), mall);
        }
    }

    // ---------- control panel ----------

    @GetMapping("/api/admin/tenants/{id}/mall-offer")
    public Map<String, Object> adminGet(@PathVariable UUID id) {
        com.lezitech.suite.admin.AdminContext.require("read");
        Settings s = settings(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("percent", s.percent());
        out.put("thankYou", s.thankYou());
        out.put("on", s.on());
        out.put("joined", jdbc.queryForObject("SELECT count(*) FROM tenants WHERE mall_id = ?", Long.class, id));
        out.put("paid", jdbc.queryForObject("SELECT count(*) FROM tenants WHERE mall_id = ? AND status = 'ACTIVE'", Long.class, id));
        return out;
    }

    @PutMapping("/api/admin/tenants/{id}/mall-offer")
    public Map<String, Object> adminSet(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        com.lezitech.suite.admin.AdminContext.require("billing");
        Body b = new Body(raw);
        return setFromControlPanel(id, b.integer("percent", 0), b.integer("thankYou", 0));
    }

    /** LeziTech sets a mall's discount and thank-you credit (0 switches the offer off for that mall). */
    public Map<String, Object> setFromControlPanel(UUID mall, int percent, int thankYou) {
        if (percent < 0 || percent > 90 || thankYou < 0 || thankYou > 100_000) {
            throw ApiException.bad("Discount 0 to 90%, thank-you credit 0 to KSh 100,000");
        }
        Settings s = settings(mall);
        save(mall, new Settings(percent, thankYou, percent > 0 && s.on()));
        return Map.of("percent", percent, "thankYou", thankYou, "on", percent > 0 && s.on());
    }
}
