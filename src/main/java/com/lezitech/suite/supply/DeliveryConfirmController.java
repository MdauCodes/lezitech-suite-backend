package com.lezitech.suite.supply;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The client's clerk confirms a delivery from the emailed link, with no login. The link is 32 random bytes and only
 * its SHA-256 is stored; that hash finds the business (delivery_links has no fence, and holds nothing else), and then
 * everything is read and written inside that business's own context.
 *
 * The menu's delivery info page is also GET /api/public/delivery/{slug}. A link is told apart from a business slug by
 * its shape: 43 characters of base64url that include a capital letter or an underscore (never in a slug), and
 * RoundsService makes every link that way.
 */
@RestController
@RequestMapping("/api/public/delivery")
public class DeliveryConfirmController {

    private final JdbcTemplate jdbc;
    private final RoundsService rounds;

    public DeliveryConfirmController(JdbcTemplate jdbc, RoundsService rounds) {
        this.jdbc = jdbc;
        this.rounds = rounds;
    }

    private UUID tenantOf(String token) {
        if (token == null || token.length() != 43 || !token.matches("[A-Za-z0-9_-]+")) {
            throw ApiException.notFound("This delivery link");
        }
        // A paused business's links stop working, like its other public pages.
        List<UUID> rows = jdbc.queryForList("""
                SELECT l.tenant_id FROM delivery_links l JOIN tenants t ON t.id = l.tenant_id
                WHERE l.token_hash = ? AND l.expires_at > now() AND t.status <> 'SUSPENDED'""",
                UUID.class, RoundsService.sha(token));
        if (rows.isEmpty()) {
            throw ApiException.notFound("This delivery link");
        }
        return rows.get(0);
    }

    @GetMapping("/{token:(?=.*[A-Z_])[A-Za-z0-9_-]{43}}")
    public Map<String, Object> view(@PathVariable String token) {
        return TenantContext.runAs(tenantOf(token), null, () -> rounds.publicView(token));
    }

    /** The clerk's name goes on the activity rows, so every short or sent back line shows who confirmed it. */
    @PostMapping("/{token:(?=.*[A-Z_])[A-Za-z0-9_-]{43}}")
    public Map<String, Object> confirm(@PathVariable String token, @RequestBody Map<String, Object> body) {
        UUID tenant = tenantOf(token);
        String clerk = body.get("receivedByName") == null ? "" : body.get("receivedByName").toString().trim();
        if (clerk.length() > 80) {
            clerk = clerk.substring(0, 80);
        }
        TenantContext.Caller previous = TenantContext.callerOrNull();
        TenantContext.set(new TenantContext.Caller(null, tenant, "CLIENT", clerk.isEmpty() ? "Client" : clerk + " (client)"));
        try {
            return rounds.publicConfirm(token, body);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }
}
