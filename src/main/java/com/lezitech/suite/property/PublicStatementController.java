package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tenant's own statement, from the link in their reminders: no sign-in, the long random token in the link is what
 * gives access. Shows only that lease, and nothing about other tenants.
 */
@RestController
public class PublicStatementController {

    /** Statement views from one phone or network: 60 an hour (a wrong link can't be guessed by trying many). */
    public static final RateLimit PER_CLIENT = new RateLimit(60, 3_600_000);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LeaseLedger ledger;

    public PublicStatementController(JdbcTemplate jdbc, PlatformTransactionManager txm, LeaseLedger ledger) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.ledger = ledger;
    }

    @GetMapping("/api/public/statement/{slug}/{token}")
    public Map<String, Object> statement(@PathVariable String slug, @PathVariable String token, HttpServletRequest req) {
        if (!PER_CLIENT.allow(req.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Too many tries. Please wait a while and open the link again.");
        }
        if (token == null || !token.matches("[0-9a-f]{32,64}")) {
            throw ApiException.notFound("Statement");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, phone, brand_color, logo_url, status, settings->>'address' AS address,
                       coalesce(settings->'rent', '{}'::jsonb)::text AS rent FROM tenants WHERE slug = ?""", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Statement");
        }
        Map<String, Object> t = rows.get(0);
        UUID tenant = (UUID) t.get("id");
        Map<String, Object> s = TenantContext.runAs(tenant, null, () -> tx.execute(x -> {
            List<UUID> lease = jdbc.queryForList("SELECT id FROM leases WHERE tenant_id = ? AND statement_token = ?", UUID.class, tenant, token);
            if (lease.isEmpty()) {
                throw ApiException.notFound("Statement");
            }
            return ledger.statement(lease.get(0), null, null);
        }));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("name", t.get("name"));
        business.put("phone", t.get("phone"));
        business.put("address", t.get("address"));
        business.put("brandColor", t.get("brand_color"));
        business.put("logoUrl", t.get("logo_url"));
        out.put("business", business);
        out.put("payInstructions", RentSettings.withDefaults((String) t.get("rent")).get("payInstructions"));
        // Internal ids stay inside the app.
        Map<String, Object> lease = new LinkedHashMap<>((Map<String, Object>) s.get("lease"));
        lease.remove("id");
        s.put("lease", lease);
        for (Object l : (List<?>) s.get("lines")) {
            ((Map<?, ?>) l).remove("ref");
        }
        for (Object o : (List<?>) s.get("open")) {
            ((Map<?, ?>) o).remove("id");
        }
        out.putAll(s);
        return out;
    }
}
