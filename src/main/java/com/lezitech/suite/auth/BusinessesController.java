package com.lezitech.suite.auth;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One owner, several businesses: the businesses this person can open, adding another one (its own sector, items, staff
 * and reports), and switching between them without signing in again.
 */
@RestController
@RequestMapping("/api/businesses")
public class BusinessesController {

    /** A sensible ceiling until plans for several businesses are decided. */
    static final int MAX_BUSINESSES = 10;

    private final JdbcTemplate jdbc;
    private final AuthController auth;

    public BusinessesController(JdbcTemplate jdbc, AuthController auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    private UUID person() {
        return jdbc.queryForObject("SELECT person_id FROM users WHERE id = ?", UUID.class, TenantContext.userId());
    }

    private List<Map<String, Object>> mine(UUID person) {
        return jdbc.queryForList("""
                SELECT t.id AS "tenantId", t.name, t.sector, t.sub_sector AS "subSector", t.slug, t.brand_color AS "brandColor",
                       t.logo_url AS "logoUrl", u.role, u.id AS "userId"
                FROM users u JOIN tenants t ON t.id = u.tenant_id
                WHERE u.person_id = ? AND u.active AND t.status <> 'SUSPENDED'
                ORDER BY t.created_at""", person);
    }

    /** The businesses I can open, which one I'm in, and whether I may add more. */
    @GetMapping
    public Map<String, Object> list() {
        TenantContext.Caller c = TenantContext.caller();
        List<Map<String, Object>> list = mine(person());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", c.tenantId());
        out.put("businesses", list);
        out.put("canAdd", "OWNER".equals(c.role()) && list.size() < MAX_BUSINESSES);
        return out;
    }

    /**
     * Add another business of the owner's: {name, sector, subSector, withSamples, phone}. It gets its own sector, items,
     * staff and reports, the owner's sign-in, and they're taken straight into it.
     */
    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, Object> raw) throws Exception {
        TenantContext.Caller c = TenantContext.caller();
        if (!"OWNER".equals(c.role())) {
            throw ApiException.forbidden("Only the owner can add a business");
        }
        Body b = new Body(raw);
        String name = b.str("name", "").trim();
        if (name.length() < 2) {
            throw ApiException.bad("Give the new business its name");
        }
        UUID person = person();
        if (mine(person).size() >= MAX_BUSINESSES) {
            throw ApiException.bad("You have " + MAX_BUSINESSES + " businesses already. Talk to the BizNez team to add more.");
        }
        Map<String, Object> me = jdbc.queryForMap("SELECT u.name, t.group_id, t.phone, t.brand_color FROM users u JOIN tenants t ON t.id = u.tenant_id WHERE u.id = ?",
                c.userId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessName", name);
        body.put("sector", b.str("sector"));
        if (b.has("subSector")) {
            body.put("subSector", b.str("subSector"));
        }
        body.put("withSamples", b.bool("withSamples", true));
        body.put("phone", b.str("phone", me.get("phone") == null ? null : me.get("phone").toString()));
        if (b.has("brandColor")) {
            body.put("brandColor", b.str("brandColor"));
        }
        return auth.createWorkspace(body, null, new AuthController.LinkedOwner(person, (UUID) me.get("group_id"), (String) me.get("name")));
    }

    /** Open another of my businesses: a new session there, no second sign-in. */
    @PostMapping("/{tenantId}/switch")
    public Map<String, Object> open(@PathVariable UUID tenantId) {
        UUID person = person();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.role, u.name FROM users u JOIN tenants t ON t.id = u.tenant_id
                WHERE u.person_id = ? AND u.tenant_id = ? AND u.active AND t.status <> 'SUSPENDED'""", person, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.forbidden("That isn't one of your businesses");
        }
        Map<String, Object> u = rows.get(0);
        return auth.session((UUID) u.get("id"), tenantId, (String) u.get("role"), (String) u.get("name"));
    }
}
