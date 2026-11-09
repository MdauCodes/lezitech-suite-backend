package com.lezitech.suite.growth;

import com.lezitech.suite.admin.AdminContext;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Referral codes for owners, and for the LeziTech team: which ads and campaigns bring paying businesses. */
@RestController
public class GrowthController {

    private final JdbcTemplate jdbc;
    private final Referrals referrals;
    private final MetaConversions meta;
    private final String siteUrl;

    public GrowthController(JdbcTemplate jdbc, Referrals referrals, MetaConversions meta,
                            @Value("${suite.site-url:https://lezitech.site/suite}") String siteUrl) {
        this.jdbc = jdbc;
        this.referrals = referrals;
        this.meta = meta;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
    }

    /** The owner's "bring a business" card: their code, link and how many joined and paid through it. */
    @GetMapping("/api/billing/referral")
    public Map<String, Object> referral() {
        var tenant = TenantContext.tenantId();
        String code = referrals.codeFor(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("link", siteUrl + "?ref=" + code);
        out.put("joined", jdbc.queryForObject("SELECT count(*) FROM tenants WHERE referred_by = ?", Long.class, tenant));
        out.put("paid", jdbc.queryForObject("SELECT count(*) FROM tenants WHERE referred_by = ? AND referral_rewarded_at IS NOT NULL", Long.class, tenant));
        return out;
    }

    /**
     * Results by ad source and campaign over the last N days: setup requests, trials, paying businesses and money
     * collected. Spend comes from Meta Ads Manager; divide it by "paying" here for the true cost of a customer.
     */
    @GetMapping("/api/admin/acquisition")
    public Map<String, Object> acquisition(@RequestParam(defaultValue = "30") int days) {
        AdminContext.require("read");
        int d = Math.max(1, Math.min(days, 365));
        String group = """
                coalesce(nullif(acquisition->>'utm_source', ''), CASE WHEN acquisition->>'ref' IS NOT NULL THEN 'referral' WHEN acquisition->>'fbclid' IS NOT NULL THEN 'facebook'
                     WHEN acquisition->>'gclid' IS NOT NULL THEN 'google' ELSE 'direct' END)""";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH leads AS (
                  SELECT %1$s AS source, coalesce(acquisition->>'utm_campaign', '') AS campaign, count(*) AS requests
                  FROM setup_requests WHERE created_at > now() - make_interval(days => ?) GROUP BY 1, 2),
                trials AS (
                  SELECT %1$s AS source, coalesce(acquisition->>'utm_campaign', '') AS campaign,
                         count(*) AS trials,
                         count(*) FILTER (WHERE EXISTS (SELECT 1 FROM subscription_requests s WHERE s.tenant_id = t.id AND s.status = 'PAID')) AS paying,
                         coalesce(sum((SELECT sum(s.amount) FROM subscription_requests s WHERE s.tenant_id = t.id AND s.status = 'PAID')), 0) AS collected
                  FROM tenants t WHERE NOT is_demo AND created_at > now() - make_interval(days => ?) GROUP BY 1, 2)
                SELECT coalesce(t.source, l.source) AS source, coalesce(t.campaign, l.campaign) AS campaign,
                       coalesce(l.requests, 0) AS requests, coalesce(t.trials, 0) AS trials, coalesce(t.paying, 0) AS paying,
                       coalesce(t.collected, 0) AS collected
                FROM trials t FULL JOIN leads l ON l.source = t.source AND l.campaign = t.campaign
                ORDER BY paying DESC, trials DESC, requests DESC""".formatted(group), d, d);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("rows", rows);
        out.put("metaOn", meta.on());
        out.put("events", jdbc.queryForList("""
                SELECT event_name, status, count(*) AS n FROM marketing_events WHERE created_at > now() - make_interval(days => ?)
                GROUP BY 1, 2 ORDER BY 1, 2""", d));
        return out;
    }
}
