package com.lezitech.suite.property;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.files.FileService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mall directory (owner's decisions, 2026-11-14): a public page of the mall's shops, by floor, with what they sell,
 * phone, hours, logo and offers. Each occupied unit has an entry (the tenant's shop, or the landlord's own). Tenants edit
 * their entry and post offers from their tenant page; the landlord approves before it goes public, and can edit or hide
 * anything. A new tenant in a unit starts a fresh entry. Callers run inside the workspace's transaction.
 */
@Service
public class Directory {

    static final List<String> CATEGORIES = List.of("SUPERMARKET", "FASHION", "FOOD", "ELECTRONICS", "BEAUTY", "HEALTH", "BANK", "SERVICES", "HOME", "OTHER");

    private final Db db;
    private final FileService files;

    public Directory(Db db, FileService files) {
        this.db = db;
        this.files = files;
    }

    /** Make sure every occupied unit has an entry, and that a new tenant in a unit starts fresh. */
    public void sync() {
        for (Map<String, Object> u : db.list("""
                SELECT sp.id AS space_id, sp.owner_use, l.id AS lease_id, c.name AS tenant_name, c.phone, t.name AS business,
                       e.id AS entry_id, e.lease_id AS entry_lease
                FROM spaces sp LEFT JOIN leases l ON l.space_id = sp.id AND l.status = 'ACTIVE' LEFT JOIN customers c ON c.id = l.customer_id
                JOIN tenants t ON t.id = sp.tenant_id LEFT JOIN directory_entries e ON e.space_id = sp.id
                WHERE sp.active AND (l.id IS NOT NULL OR sp.owner_use) AND sp.kind IN """ + " " + PropertyController.UNIT_KINDS)) {
            Object lease = u.get("leaseId");
            String name = lease != null ? (String) u.get("tenantName") : (String) u.get("business");
            if (u.get("entryId") == null) {
                db.exec("""
                        INSERT INTO directory_entries (tenant_id, space_id, lease_id, display_name, phone) VALUES (:t, :s, :l, :n, :p)""",
                        "s", u.get("spaceId"), "l", lease, "n", name, "p", lease != null ? u.get("phone") : null);
            } else if (!String.valueOf(lease).equals(String.valueOf(u.get("entryLease")))) {
                db.exec("DELETE FROM directory_offers WHERE entry_id = :e", "e", u.get("entryId"));
                db.exec("""
                        UPDATE directory_entries SET lease_id = :l, display_name = :n, category = 'OTHER', description = NULL, phone = :p, whatsapp = NULL,
                               hours = NULL, logo_file_id = NULL, hidden = false, draft = NULL, draft_at = NULL, updated_at = now() WHERE id = :e""",
                        "e", u.get("entryId"), "l", lease, "n", name, "p", lease != null ? u.get("phone") : null);
            }
        }
    }

    private static final String LIVE = """
            SELECT e.*, sp.name AS unit_name, sp.floor, (SELECT f.sort FROM building_floors f WHERE f.name = sp.floor) AS floor_sort
            FROM directory_entries e JOIN spaces sp ON sp.id = e.space_id
            LEFT JOIN leases l ON l.space_id = sp.id AND l.status = 'ACTIVE'
            WHERE sp.active AND (l.id IS NOT NULL OR sp.owner_use) AND (e.lease_id IS NULL OR e.lease_id = l.id)""";

    /** The landlord's view: every entry, with any changes waiting, and offers. */
    public Map<String, Object> admin() {
        sync();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> entries = db.list(LIVE + " ORDER BY floor_sort NULLS LAST, sp.sort, sp.name");
        for (Map<String, Object> e : entries) {
            e.put("offers", db.list("SELECT * FROM directory_offers WHERE entry_id = :e ORDER BY created_at DESC", "e", e.get("id")));
        }
        out.put("entries", entries);
        out.put("waiting", entries.stream().filter(e -> e.get("draft") != null).count()
                + db.value("SELECT count(*) FROM directory_offers WHERE status = 'PENDING'", Long.class));
        out.put("link", "/mall/" + db.value("SELECT slug FROM tenants WHERE id = :t", String.class));
        out.put("categories", CATEGORIES);
        return out;
    }

    private Map<String, Object> clean(Body b) {
        String name = b.str("displayName", "").trim();
        if (name.isEmpty() || name.length() > 120) {
            throw ApiException.bad("Give the shop's name (up to 120 letters)");
        }
        String category = b.str("category", "OTHER").toUpperCase();
        if (!CATEGORIES.contains(category)) {
            category = "OTHER";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", name);
        m.put("category", category);
        m.put("description", clip(b.str("description", null), 300));
        m.put("phone", clip(b.str("phone", null), 30));
        m.put("whatsapp", clip(b.str("whatsapp", null), 30));
        m.put("hours", clip(b.str("hours", null), 120));
        if (b.has("logoFileId")) {
            m.put("logoFileId", b.str("logoFileId", null));
        }
        return m;
    }

    private void apply(UUID entryId, Map<String, Object> m) {
        db.exec("""
                UPDATE directory_entries SET display_name = :n, category = :c, description = :d, phone = :p, whatsapp = :w, hours = :h,
                       logo_file_id = CASE WHEN :setLogo THEN cast(:logo as uuid) ELSE logo_file_id END, updated_at = now() WHERE id = :id""",
                "id", entryId, "n", m.get("displayName"), "c", m.get("category"), "d", m.get("description"), "p", m.get("phone"),
                "w", m.get("whatsapp"), "h", m.get("hours"), "setLogo", m.containsKey("logoFileId"), "logo", m.get("logoFileId"));
    }

    /** The landlord edits an entry directly (goes public straight away), and can hide it. */
    public Map<String, Object> adminSave(UUID entryId, Body b) {
        db.one("Entry", "SELECT id FROM directory_entries WHERE id = :id", "id", entryId);
        apply(entryId, clean(b));
        if (b.has("hidden")) {
            db.exec("UPDATE directory_entries SET hidden = :h WHERE id = :id", "id", entryId, "h", b.bool("hidden", false));
        }
        return admin();
    }

    public Map<String, Object> decideDraft(UUID entryId, boolean approve) {
        Map<String, Object> e = db.one("Entry", "SELECT draft FROM directory_entries WHERE id = :id FOR UPDATE", "id", entryId);
        if (e.get("draft") == null) {
            throw ApiException.conflict("Nothing waiting for this shop");
        }
        if (approve) {
            JsonNode d = (JsonNode) e.get("draft");
            Map<String, Object> m = new LinkedHashMap<>();
            d.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().isNull() ? null : f.getValue().asText()));
            apply(entryId, m);
        }
        db.exec("UPDATE directory_entries SET draft = NULL, draft_at = NULL WHERE id = :id", "id", entryId);
        return admin();
    }

    public Map<String, Object> decideOffer(UUID offerId, boolean approve) {
        db.one("Offer", "SELECT id FROM directory_offers WHERE id = :id", "id", offerId);
        db.exec("UPDATE directory_offers SET status = :s WHERE id = :id", "id", offerId, "s", approve ? "APPROVED" : "REJECTED");
        return admin();
    }

    // ---------------- the tenant's side ----------------

    /** The tenant's own entry for a lease (synced first), with their waiting changes and offers. */
    public Map<String, Object> forLease(UUID leaseId) {
        sync();
        Map<String, Object> e = db.find(LIVE + " AND e.lease_id = :l", "l", leaseId).orElseThrow(() -> ApiException.notFound("Shop"));
        e.put("offers", db.list("SELECT id, title, details, ends_on, status FROM directory_offers WHERE entry_id = :e ORDER BY created_at DESC", "e", e.get("id")));
        e.put("categories", CATEGORIES);
        return e;
    }

    /** The tenant's changes wait for the landlord's approval. */
    public Map<String, Object> tenantSave(UUID leaseId, Body b) {
        Map<String, Object> e = forLease(leaseId);
        Map<String, Object> m = clean(b);
        if (!m.containsKey("logoFileId") && e.get("draft") instanceof JsonNode d && d.hasNonNull("logoFileId")) {
            m.put("logoFileId", d.get("logoFileId").asText());
        }
        db.exec("UPDATE directory_entries SET draft = cast(:d as jsonb), draft_at = now() WHERE id = :id", "id", e.get("id"), "d", db.toJson(m));
        return forLease(leaseId);
    }

    public Map<String, Object> tenantLogo(UUID leaseId, byte[] bytes) {
        Map<String, Object> e = forLease(leaseId);
        UUID file = files.save(FileService.Purpose.UNIT_PHOTO, bytes);
        Map<String, Object> m = new LinkedHashMap<>();
        if (e.get("draft") instanceof JsonNode d) {
            d.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().isNull() ? null : f.getValue().asText()));
        } else {
            for (String k : List.of("displayName", "category", "description", "phone", "whatsapp", "hours")) {
                m.put(k, e.get(k));
            }
        }
        m.put("logoFileId", file.toString());
        db.exec("UPDATE directory_entries SET draft = cast(:d as jsonb), draft_at = now() WHERE id = :id", "id", e.get("id"), "d", db.toJson(m));
        return forLease(leaseId);
    }

    public Map<String, Object> tenantOffer(UUID leaseId, Body b) {
        Map<String, Object> e = forLease(leaseId);
        String title = b.str("title", "").trim();
        if (title.isEmpty() || title.length() > 120) {
            throw ApiException.bad("Give the offer a short title, e.g. 20% off school shoes");
        }
        Long open = db.value("SELECT count(*) FROM directory_offers WHERE entry_id = :e AND status <> 'REJECTED' AND (ends_on IS NULL OR ends_on >= current_date)",
                Long.class, "e", e.get("id"));
        if (open != null && open >= 5) {
            throw ApiException.bad("Up to 5 offers at a time; remove an old one first");
        }
        LocalDate ends = b.has("endsOn") && !b.str("endsOn", "").isBlank() ? b.date("endsOn") : null;
        db.exec("INSERT INTO directory_offers (tenant_id, entry_id, title, details, ends_on) VALUES (:t, :e, :ti, :d, :end)",
                "e", e.get("id"), "ti", title, "d", clip(b.str("details", null), 300), "end", ends);
        return forLease(leaseId);
    }

    public Map<String, Object> tenantRemoveOffer(UUID leaseId, UUID offerId) {
        Map<String, Object> e = forLease(leaseId);
        db.exec("DELETE FROM directory_offers WHERE id = :o AND entry_id = :e", "o", offerId, "e", e.get("id"));
        return forLease(leaseId);
    }

    // ---------------- public ----------------

    public List<Map<String, Object>> publicEntries() {
        sync();
        List<Map<String, Object>> entries = db.list(LIVE + " AND NOT e.hidden ORDER BY floor_sort NULLS LAST, sp.sort, sp.name");
        for (Map<String, Object> e : entries) {
            e.put("offers", db.list("""
                    SELECT title, details, ends_on FROM directory_offers WHERE entry_id = :e AND status = 'APPROVED'
                      AND (ends_on IS NULL OR ends_on >= current_date) ORDER BY created_at DESC""", "e", e.get("id")));
            for (String k : List.of("id", "spaceId", "leaseId", "draft", "draftAt", "hidden", "tenantId", "floorSort")) {
                e.remove(k);
            }
        }
        return entries;
    }

    public boolean isPublicLogo(UUID fileId) {
        return Boolean.TRUE.equals(db.value("SELECT EXISTS (SELECT 1 FROM directory_entries WHERE logo_file_id = :f AND NOT hidden)", Boolean.class, "f", fileId));
    }

    private static String clip(String s, int n) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > n ? t.substring(0, n) : t;
    }
}
