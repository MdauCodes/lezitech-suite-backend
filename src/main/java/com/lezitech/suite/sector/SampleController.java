package com.lezitech.suite.sector;

import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The sample records a trial starts with (a café menu, guests, tenants who "owe" rent…), and removing them once the owner
 * starts for real. Everything the seeder makes is created at signup, before the owner can add anything, and
 * settings.samplesUntil records that moment: rows created up to it are samples. The owner's own login and the business's
 * structure (branches, tables and floors, sales stages) stay. Old sales keep their lines: a removed item's name stays on
 * them, and anything still needed by a record that stays is switched off rather than deleted.
 */
@RestController
@RequestMapping("/api/samples")
@Transactional
public class SampleController {

    private final Db db;

    public SampleController(Db db) {
        this.db = db;
    }

    /** present: samples are still there. startedOwn: the owner has added their own items, customers or sales since. */
    @GetMapping
    public Map<String, Object> status() {
        String until = until();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean present = until != null && Boolean.TRUE.equals(db.value("""
                SELECT exists(SELECT 1 FROM items WHERE tenant_id = :t AND active AND created_at <= cast(:u as timestamptz))
                    OR exists(SELECT 1 FROM customers WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz))
                    OR exists(SELECT 1 FROM spaces WHERE tenant_id = :t AND active AND created_at <= cast(:u as timestamptz))""", Boolean.class, "u", until));
        out.put("present", present);
        out.put("startedOwn", present && Boolean.TRUE.equals(db.value("""
                SELECT exists(SELECT 1 FROM items WHERE tenant_id = :t AND created_at > cast(:u as timestamptz))
                    OR exists(SELECT 1 FROM customers WHERE tenant_id = :t AND created_at > cast(:u as timestamptz))
                    OR exists(SELECT 1 FROM spaces WHERE tenant_id = :t AND created_at > cast(:u as timestamptz))
                    OR exists(SELECT 1 FROM orders WHERE tenant_id = :t AND created_at > cast(:u as timestamptz) AND status IN ('PAID', 'CREDIT'))""",
                Boolean.class, "u", until)));
        return out;
    }

    @PostMapping("/remove")
    @Transactional
    public Map<String, Object> remove() {
        TenantContext.requireRole("MANAGER");
        String until = until();
        if (until == null) {
            return Map.of("ok", true, "removed", 0);
        }
        Object[] u = {"u", until};
        int n = 0;
        // Sample bills first (hotel stays, trip bookings, rent), then what they belonged to.
        n += db.exec("DELETE FROM orders WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz)", u);
        n += db.exec("DELETE FROM trip_bookings WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz)", u);
        n += db.exec("DELETE FROM trips WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz)", u);
        n += db.exec("DELETE FROM stays WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz)", u);
        n += db.exec("DELETE FROM leases WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz)", u);
        // Sample rooms and units: removed, or switched off if one of the owner's own bookings uses it.
        n += db.exec("""
                DELETE FROM spaces s WHERE s.tenant_id = :t AND s.created_at <= cast(:u as timestamptz)
                  AND NOT EXISTS (SELECT 1 FROM stays st WHERE st.space_id = s.id)
                  AND NOT EXISTS (SELECT 1 FROM leases l WHERE l.space_id = s.id)""", u);
        n += db.exec("UPDATE spaces SET active = false WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz) AND active", u);
        n += db.exec("DELETE FROM deals WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz)", u);
        n += db.exec("DELETE FROM suppliers WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz)", u);
        n += db.exec("""
                DELETE FROM customers c WHERE c.tenant_id = :t AND c.created_at <= cast(:u as timestamptz)
                  AND NOT EXISTS (SELECT 1 FROM leases l WHERE l.customer_id = c.id)""", u);
        // Sample items: removed (their stock goes with them); one on a purchase order of the owner's is switched off.
        n += db.exec("""
                DELETE FROM items i WHERE i.tenant_id = :t AND i.created_at <= cast(:u as timestamptz)
                  AND NOT EXISTS (SELECT 1 FROM purchase_lines p WHERE p.item_id = i.id)
                  AND NOT EXISTS (SELECT 1 FROM trips tr WHERE tr.item_id = i.id)""", u);
        n += db.exec("UPDATE items SET active = false WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz) AND active", u);
        n += db.exec("""
                DELETE FROM categories c WHERE c.tenant_id = :t AND c.created_at <= cast(:u as timestamptz)
                  AND NOT EXISTS (SELECT 1 FROM items i WHERE i.category_id = c.id AND i.active)""", u);
        // Sample staff (they have no real email) stop being able to sign in; their names stay on old sales.
        n += db.exec("UPDATE users SET active = false WHERE tenant_id = :t AND created_at <= cast(:u as timestamptz) AND email LIKE '%.suite.local' AND active", u);
        db.exec("""
                UPDATE tenants SET settings = (settings - 'samplesUntil') || jsonb_build_object('samplesRemovedAt', now())
                WHERE id = :t""");
        return Map.of("ok", true, "removed", n);
    }

    /** The moment sample seeding finished (text; compared in SQL as a timestamptz), or null once removed. */
    private String until() {
        return db.value("SELECT settings->>'samplesUntil' FROM tenants WHERE id = :t", String.class);
    }
}
