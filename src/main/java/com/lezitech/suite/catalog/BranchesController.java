package com.lezitech.suite.catalog;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.settings.PlanLimits;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Branches: other places of the same business selling the same things at the same prices. Customers are shared, reports
 * add up (and show each branch), stock is kept per branch and moved between them. Each staff member works at one
 * branch; owners and managers can work in one or see them all.
 */
@RestController
@RequestMapping("/api/branches")
@Transactional
public class BranchesController {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final Db db;
    private final PlanLimits limits;

    public BranchesController(Db db, PlanLimits limits) {
        this.db = db;
        this.limits = limits;
    }

    /** The branches with today's sales and staff, and where I'm working. */
    @GetMapping
    public Map<String, Object> list() {
        java.sql.Timestamp today = java.sql.Timestamp.from(LocalDate.now(NAIROBI).atStartOfDay(NAIROBI).toInstant());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branches", db.list("""
                SELECT l.id, l.name, l.address, l.phone, l.is_default AS main, l.active,
                       (SELECT count(*) FROM users u WHERE u.location_id = l.id AND u.active) AS staff,
                       coalesce((SELECT sum(o.total) FROM orders o WHERE o.location_id = l.id AND o.status IN ('PAID', 'CREDIT')
                                   AND o.kind = 'SALE' AND o.closed_at >= :today), 0) AS sales_today
                FROM locations l WHERE l.parent_id IS NULL AND (l.kind = 'SHOP' OR l.is_default)
                ORDER BY l.is_default DESC, l.name""", "today", today));
        out.put("workingAt", db.value("SELECT location_id FROM users WHERE id = :u", UUID.class));
        out.put("canManage", List.of("OWNER", "MANAGER").contains(TenantContext.caller().role()));
        return out;
    }

    /** Add a branch: {name, address, phone}. */
    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        String name = b.str("name", "").trim();
        if (name.length() < 2) {
            throw ApiException.bad("Give the branch a name, e.g. Westlands branch");
        }
        if (db.value("SELECT count(*) FROM locations WHERE lower(name) = lower(:n)", Long.class, "n", name) > 0) {
            throw ApiException.conflict("There's already a place called " + name);
        }
        limits.checkLocations(1);
        UUID id = db.insert("""
                INSERT INTO locations (tenant_id, name, kind, address, phone) VALUES (:t, :name, 'SHOP', :address, :phone) RETURNING id""",
                "name", clip(name, 120), "address", clip(b.str("address", null), 200), "phone", clip(b.str("phone", null), 30));
        return db.one("Branch", "SELECT id, name, address, phone, is_default AS main, active FROM locations WHERE id = :id", "id", id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> save(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        Map<String, Object> l = db.one("Branch", "SELECT is_default FROM locations WHERE id = :id", "id", id);
        boolean active = b.bool("active", true);
        if (!active && Boolean.TRUE.equals(l.get("isDefault"))) {
            throw ApiException.bad("The main place can't be closed");
        }
        db.exec("UPDATE locations SET name = coalesce(:name, name), address = :address, phone = :phone, active = :active WHERE id = :id",
                "id", id, "name", b.has("name") ? clip(b.str("name").trim(), 120) : null, "address", clip(b.str("address", null), 200),
                "phone", clip(b.str("phone", null), 30), "active", active);
        if (!active) {
            db.exec("UPDATE users SET location_id = NULL WHERE location_id = :id AND tenant_id = :t", "id", id);
        }
        return db.one("Branch", "SELECT id, name, address, phone, is_default AS main, active FROM locations WHERE id = :id", "id", id);
    }

    /** Where I work now (owners and managers): {locationId} or empty for all branches / the main place. */
    @PostMapping("/work-at")
    public Map<String, Object> workAt(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        UUID loc = place(new Body(raw));
        db.exec("UPDATE users SET location_id = :l WHERE id = :u AND tenant_id = :t", "l", loc);
        return list();
    }

    /** Which branch a staff member works at: {userId, locationId} (empty = the main place). */
    @PostMapping("/assign")
    public Map<String, Object> assign(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        UUID user = UUID.fromString(b.str("userId"));
        UUID loc = place(b);
        int n = db.exec("UPDATE users SET location_id = :l WHERE id = :id AND tenant_id = :t", "id", user, "l", loc);
        if (n == 0) {
            throw ApiException.notFound("Staff member");
        }
        return Map.of("ok", true);
    }

    private UUID place(Body b) {
        if (!b.has("locationId") || b.str("locationId").isBlank()) {
            return null;
        }
        UUID loc = UUID.fromString(b.str("locationId"));
        Map<String, Object> l = db.one("Branch", "SELECT active, is_default FROM locations WHERE id = :id", "id", loc);
        if (!Boolean.TRUE.equals(l.get("active"))) {
            throw ApiException.bad("That branch is closed");
        }
        // The main place is the same as "no branch".
        return Boolean.TRUE.equals(l.get("isDefault")) ? null : loc;
    }

    private static String clip(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }
}
