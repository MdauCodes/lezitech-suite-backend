package com.lezitech.suite.onboarding;

import com.lezitech.suite.common.Db;
import com.lezitech.suite.integrations.IntegrationService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first-day checklist: a few steps that get a new business really working, each ticking itself as soon as it's
 * done (nothing to tick by hand). The steps fit the kind of business: shops and restaurants sell, landlords let units.
 */
@RestController
@Transactional
public class FirstStepsController {

    private final Db db;
    private final IntegrationService integrations;

    public FirstStepsController(Db db, IntegrationService integrations) {
        this.db = db;
        this.integrations = integrations;
    }

    private boolean yes(String sql, Object... params) {
        return Boolean.TRUE.equals(db.value(sql, Boolean.class, params));
    }

    /** A step: what it is, why it matters, where it's done, the button's words (verb + what), and whether it's done. */
    private static Map<String, Object> step(String key, String title, String why, String to, String action, boolean done) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("title", title);
        s.put("why", why);
        s.put("to", to);
        s.put("action", action);
        s.put("done", done);
        return s;
    }

    @GetMapping("/api/first-steps")
    public Map<String, Object> steps() {
        Map<String, Object> t = db.one("Business", "SELECT sector, logo_url, settings->>'kraPin' AS kra, settings->>'samplesUntil' AS samples_until, settings->>'samplesRemovedAt' AS removed FROM tenants WHERE id = :t");
        String sector = (String) t.get("sector");
        String until = (String) t.get("samplesUntil");
        boolean property = "property".equals(sector);
        // "Own" means added after the samples (or anything, when they started without samples).
        String own = until == null ? "true" : "created_at > cast(:u as timestamptz)";
        Object[] p = {"u", until};
        List<Map<String, Object>> steps = new ArrayList<>();
        if (property) {
            steps.add(step("units", "Add your own units or stalls", "So every rent bill, notice and repair belongs to the right place.", "/property", "Add my units",
                    yes("SELECT exists(SELECT 1 FROM spaces WHERE " + own + ")", p)));
            steps.add(step("tenants", "Add a tenant and their lease", "BizNez then bills their rent every month and reminds them by email.", "/property", "Add a tenant and lease",
                    yes("SELECT exists(SELECT 1 FROM leases WHERE " + own + ")", p)));
        } else {
            steps.add(step("items", "Add your own products or dishes", "Type them, copy them from Excel, or edit the samples. The till sells what's here.", "/items", "Add my products",
                    yes("SELECT exists(SELECT 1 FROM items WHERE kind NOT IN ('INGREDIENT', 'BATCH') AND " + own + ")", p)));
        }
        steps.add(step("details", "Add your logo and KRA PIN", "They appear on receipts, bills and the emails your customers get.", "/settings", "Add my logo and KRA PIN",
                t.get("logoUrl") != null || (t.get("kra") != null && !((String) t.get("kra")).isBlank())));
        steps.add(step("mpesa", "Connect your M-Pesa till or paybill", "Customers then pay with a prompt on their phone and it's matched to the bill by itself.", "/settings", "Connect my M-Pesa",
                integrations.tillAccount(TenantContext.tenantId()).isPresent()));
        steps.add(step("staff", "Add your staff and give each a PIN", "Each person signs in with their own PIN, so you see who sold what.", "/team", "Add a staff member",
                yes("SELECT count(*) > 1 FROM users WHERE tenant_id = :t AND active")));
        if (!property) {
            steps.add(step("sale", "Make your first real sale", "Open the till, sell something and take the money. That's BizNez working for you.", "/pos", "Open the till and sell",
                    yes("SELECT exists(SELECT 1 FROM orders WHERE status IN ('PAID', 'CREDIT') AND kind = 'SALE' AND " + own + ")", p)));
        }
        if (until != null || t.get("removed") != null) {
            steps.add(step("samples", "Remove the samples", "Once your own are in, clear the examples in one tap so reports show only your business.", "/items", "Remove the samples",
                    t.get("removed") != null));
        }
        long done = steps.stream().filter(s -> Boolean.TRUE.equals(s.get("done"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("steps", steps);
        out.put("done", done);
        out.put("total", steps.size());
        out.put("finished", done == steps.size());
        return out;
    }
}
