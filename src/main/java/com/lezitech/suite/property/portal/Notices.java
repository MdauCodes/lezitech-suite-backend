package com.lezitech.suite.property.portal;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.lezitech.suite.notify.Mailer.esc;

/** Notices from the landlord to tenants: everyone, one floor, or chosen leases. Shown in the portal and emailed. */
@Service
public class Notices {

    private final Db db;
    private final Mailer mailer;

    public Notices(Db db, Mailer mailer) {
        this.db = db;
        this.mailer = mailer;
    }

    private static final String RECIPIENTS = """
            SELECT DISTINCT l.id AS lease_id, coalesce(a.email, c.email) AS email, c.name
            FROM leases l JOIN spaces sp ON sp.id = l.space_id JOIN customers c ON c.id = l.customer_id
            LEFT JOIN renter_accounts a ON a.customer_id = c.id AND a.tenant_id = l.tenant_id AND a.active
            WHERE l.status = 'ACTIVE' AND (:aud = 'ALL' OR (:aud = 'FLOOR' AND sp.floor = :floor)
                  OR (:aud = 'LEASES' AND jsonb_exists(cast(:leases as jsonb), l.id::text)))""";

    public Map<String, Object> post(String title, String body, String audienceRaw, String floor, List<String> leaseIds) {
        if (title == null || title.isBlank() || title.length() > 150) {
            throw ApiException.bad("Give the notice a short title");
        }
        if (body == null || body.isBlank()) {
            throw ApiException.bad("Write the notice");
        }
        String audience = audienceRaw == null ? "ALL" : audienceRaw.toUpperCase();
        if (!List.of("ALL", "FLOOR", "LEASES").contains(audience)) {
            throw ApiException.bad("Choose who it's for");
        }
        if ("FLOOR".equals(audience) && (floor == null || floor.isBlank())) {
            throw ApiException.bad("Choose the floor");
        }
        List<String> leases = leaseIds == null ? List.of() : leaseIds;
        if ("LEASES".equals(audience) && leases.isEmpty()) {
            throw ApiException.bad("Choose at least one tenant");
        }
        List<Map<String, Object>> to = db.list(RECIPIENTS, "aud", audience, "floor", floor, "leases", db.toJson(leases));
        if (to.isEmpty()) {
            throw ApiException.bad("No current tenants match; nobody would get this notice");
        }
        TenantContext.Caller c = TenantContext.caller();
        UUID id = db.insert("""
                INSERT INTO property_notices (tenant_id, title, body, audience, floor, lease_ids, created_by, created_by_name)
                VALUES (:t, :title, :body, :aud, :floor, cast(:leases as jsonb), :u, :name) RETURNING id""",
                "title", title.trim(), "body", body.trim(), "aud", audience, "floor", "FLOOR".equals(audience) ? floor.trim() : null,
                "leases", db.toJson("LEASES".equals(audience) ? leases : List.of()), "u", c.userId(), "name", c.name());
        Map<String, Object> biz = db.one("Business", "SELECT name, slug, brand_color FROM tenants WHERE id = :t");
        String business = (String) biz.get("name");
        String link = mailer.appUrl() + "/t/" + biz.get("slug");
        int emailed = 0;
        List<String> sentTo = new ArrayList<>();
        for (Map<String, Object> r : to) {
            String email = (String) r.get("email");
            if (email == null || email.isBlank() || sentTo.contains(email.toLowerCase())) {
                continue;
            }
            sentTo.add(email.toLowerCase());
            String err = mailer.trySend(email.trim(), (String) r.get("name"), title.trim() + " · " + business,
                    body.trim() + "\n\n" + business + "\nYour tenant page: " + link,
                    RenterAuth.branded((String) biz.get("brandColor"), business, "<p><b>" + esc(title.trim()) + "</b></p><p style=\"white-space:pre-wrap\">"
                            + esc(body.trim()) + "</p>" + RenterAuth.button((String) biz.get("brandColor"), link, "Open your tenant page")));
            if (err == null) {
                emailed++;
            }
        }
        db.exec("UPDATE property_notices SET emailed = :n WHERE id = :id", "id", id, "n", emailed);
        return Map.of("id", id, "tenants", to.size(), "emailed", emailed);
    }

    public List<Map<String, Object>> all() {
        return db.list("SELECT * FROM property_notices ORDER BY created_at DESC LIMIT 100");
    }

    /** The notices that reach this tenant (any of their current leases). */
    public List<Map<String, Object>> forCustomer(UUID customerId) {
        return db.list("""
                SELECT n.id, n.title, n.body, n.created_at, n.created_by_name FROM property_notices n
                WHERE n.audience = 'ALL'
                   OR (n.audience = 'FLOOR' AND EXISTS (SELECT 1 FROM leases l JOIN spaces sp ON sp.id = l.space_id
                                                        WHERE l.customer_id = :c AND l.status = 'ACTIVE' AND sp.floor = n.floor))
                   OR (n.audience = 'LEASES' AND EXISTS (SELECT 1 FROM leases l WHERE l.customer_id = :c AND jsonb_exists(n.lease_ids, l.id::text)))
                ORDER BY n.created_at DESC LIMIT 50""", "c", customerId);
    }
}
