package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.notify.Mailer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.lezitech.suite.notify.Mailer.esc;

/**
 * Floor map and the public "units to let" page (owner's decisions, 2026-11-14). Each floor is a grid the landlord
 * arranges, or their own plan image with units pinned on it. Vacant units, and units whose tenant gave notice ("available
 * from"), appear on the to-let page unless hidden; each shows its rent or "Price on request". An enquiry becomes a sales
 * lead for the unit and the landlord is emailed; renting the unit out from the lead wins it. A unit can be marked "used
 * by the owner": occupied, never billed, not listed. Callers run inside the workspace's transaction.
 */
@Service
public class ToLet {

    private final Db db;
    private final FileService files;
    private final Mailer mailer;

    public ToLet(Db db, FileService files, Mailer mailer) {
        this.db = db;
        this.files = files;
        this.mailer = mailer;
    }

    // ---------------- floor map ----------------

    public Map<String, Object> map() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("floors", db.list("SELECT DISTINCT coalesce(floor, '') AS floor FROM spaces WHERE active AND kind IN " + PropertyController.UNIT_KINDS
                + " ORDER BY 1").stream().map(r -> r.get("floor")).toList());
        out.put("plans", db.list("SELECT floor, file_id FROM floor_plans"));
        return out;
    }

    /** [{spaceId, x, y, w, h, pinX, pinY}] — any of them may be null (not placed). */
    public void savePositions(List<Map<String, Object>> positions) {
        for (Map<String, Object> p : positions == null ? List.<Map<String, Object>>of() : positions) {
            Body b = new Body(p);
            Integer w = b.integer("w", 1);
            Integer h = b.integer("h", 1);
            if (w == null || h == null || w < 1 || h < 1 || w > 12 || h > 12) {
                throw ApiException.bad("A unit's box must be 1 to 12 cells wide and high");
            }
            BigDecimal px = b.has("pinX") ? b.num("pinX", null) : null;
            BigDecimal py = b.has("pinY") ? b.num("pinY", null) : null;
            for (BigDecimal v : new BigDecimal[]{px, py}) {
                if (v != null && (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0)) {
                    throw ApiException.bad("A pin must be on the plan");
                }
            }
            db.exec("""
                    UPDATE spaces SET map_x = :x, map_y = :y, map_w = :w, map_h = :h, pin_x = :px, pin_y = :py WHERE id = :id""",
                    "id", UUID.fromString(b.str("spaceId")), "x", b.integer("x", null), "y", b.integer("y", null), "w", w, "h", h, "px", px, "py", py);
        }
    }

    public Map<String, Object> setPlan(String floor, byte[] bytes) {
        UUID file = files.save(FileService.Purpose.UNIT_PHOTO, bytes);
        String f = floor == null ? "" : floor.trim();
        db.find("SELECT file_id FROM floor_plans WHERE floor = :f", "f", f).ifPresent(old -> {
            db.exec("DELETE FROM floor_plans WHERE floor = :f", "f", f);
            files.remove(UUID.fromString(old.get("fileId").toString()));
        });
        db.exec("INSERT INTO floor_plans (tenant_id, floor, file_id) VALUES (:t, :f, :file)", "f", f, "file", file);
        return map();
    }

    public Map<String, Object> removePlan(String floor) {
        String f = floor == null ? "" : floor.trim();
        db.find("SELECT file_id FROM floor_plans WHERE floor = :f", "f", f).ifPresent(old -> {
            db.exec("DELETE FROM floor_plans WHERE floor = :f", "f", f);
            files.remove(UUID.fromString(old.get("fileId").toString()));
        });
        return map();
    }

    // ---------------- listing ----------------

    /** {listed, showPrice, note, ownerUse}. "Used by the owner" needs the unit empty. */
    public Map<String, Object> setListing(UUID spaceId, Body b) {
        Map<String, Object> sp = db.one("Unit", "SELECT * FROM spaces WHERE id = :id AND kind IN " + PropertyController.UNIT_KINDS, "id", spaceId);
        boolean ownerUse = b.bool("ownerUse", Boolean.TRUE.equals(sp.get("ownerUse")));
        if (ownerUse && db.find("SELECT id FROM leases WHERE space_id = :s AND status = 'ACTIVE'", "s", spaceId).isPresent()) {
            throw ApiException.conflict("Someone rents this unit. Record their move-out first, or rent it to your own business instead.");
        }
        String note = b.has("note") ? b.str("note", "").trim() : (String) sp.get("listingNote");
        if (note != null && note.length() > 300) {
            throw ApiException.bad("Keep the description to 300 characters");
        }
        db.exec("UPDATE spaces SET listed = :l, show_price = :p, listing_note = :n, owner_use = :o WHERE id = :id",
                "id", spaceId, "l", b.bool("listed", Boolean.TRUE.equals(sp.get("listed"))), "p", b.bool("showPrice", Boolean.TRUE.equals(sp.get("showPrice"))),
                "n", note == null || note.isBlank() ? null : note, "o", ownerUse);
        return db.one("Unit", "SELECT * FROM spaces WHERE id = :id", "id", spaceId);
    }

    public Map<String, Object> addPhoto(UUID spaceId, byte[] bytes) {
        db.one("Unit", "SELECT id FROM spaces WHERE id = :id", "id", spaceId);
        Integer count = db.value("SELECT count(*) FROM space_photos WHERE space_id = :s", Integer.class, "s", spaceId);
        if (count != null && count >= 8) {
            throw ApiException.bad("Up to 8 photos a unit");
        }
        UUID file = files.save(FileService.Purpose.UNIT_PHOTO, bytes);
        db.exec("INSERT INTO space_photos (tenant_id, space_id, file_id, sort) VALUES (:t, :s, :f, :n)", "s", spaceId, "f", file, "n", count == null ? 0 : count);
        return Map.of("photos", photos(spaceId));
    }

    public void removePhoto(UUID photoId) {
        Map<String, Object> p = db.one("Photo", "SELECT file_id FROM space_photos WHERE id = :id", "id", photoId);
        db.exec("DELETE FROM space_photos WHERE id = :id", "id", photoId);
        files.remove(UUID.fromString(p.get("fileId").toString()));
    }

    public List<Map<String, Object>> photos(UUID spaceId) {
        return db.list("SELECT id, file_id FROM space_photos WHERE space_id = :s ORDER BY sort, created_at", "s", spaceId);
    }

    /** Units on the to-let page: vacant (not the owner's own) and leaving soon, unless hidden. */
    public List<Map<String, Object>> listedUnits(boolean forPublic) {
        List<Map<String, Object>> units = db.list("""
                SELECT sp.id, sp.name, sp.kind, sp.type_name, sp.floor, sp.area_sqft, sp.rate, sp.service_charge, sp.show_price, sp.listing_note, sp.listed,
                       l.vacate_on AS available_from,
                       (SELECT count(*) FROM deals d JOIN pipeline_stages s ON s.id = d.stage_id WHERE d.space_id = sp.id AND NOT s.is_won AND NOT s.is_lost) AS open_leads
                FROM spaces sp LEFT JOIN leases l ON l.space_id = sp.id AND l.status = 'ACTIVE'
                WHERE sp.active AND NOT sp.owner_use AND sp.kind IN """ + " " + PropertyController.UNIT_KINDS + """
                  AND (l.id IS NULL OR l.vacate_on IS NOT NULL)""" + (forPublic ? " AND sp.listed" : "") + " ORDER BY sp.floor NULLS FIRST, sp.sort, sp.name");
        for (Map<String, Object> u : units) {
            u.put("photos", db.list("SELECT file_id FROM space_photos WHERE space_id = :s ORDER BY sort, created_at", "s", u.get("id"))
                    .stream().map(r -> r.get("fileId").toString()).toList());
            if (forPublic && !Boolean.TRUE.equals(u.get("showPrice"))) {
                u.put("rate", null);
                u.put("serviceCharge", null);
            }
        }
        return units;
    }

    public boolean isListedPhoto(UUID fileId) {
        return Boolean.TRUE.equals(db.value("""
                SELECT EXISTS (SELECT 1 FROM space_photos p JOIN spaces sp ON sp.id = p.space_id WHERE p.file_id = :f AND sp.listed AND NOT sp.owner_use)""",
                Boolean.class, "f", fileId));
    }

    // ---------------- enquiries ----------------

    /** Someone enquires about a listed unit: a lead for it, and an email to the owner and managers. */
    public Map<String, Object> enquire(Body b) {
        UUID spaceId = UUID.fromString(b.str("spaceId"));
        Map<String, Object> sp = db.one("Unit", """
                SELECT sp.id, sp.name, sp.rate FROM spaces sp WHERE sp.id = :id AND sp.active AND sp.listed AND NOT sp.owner_use""", "id", spaceId);
        String name = b.str("name", "").trim();
        String phone = b.str("phone", "").trim();
        if (name.isEmpty() || phone.replaceAll("\\D", "").length() < 9) {
            throw ApiException.bad("Enter your name and phone number");
        }
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        UUID stage = db.value("SELECT id FROM pipeline_stages WHERE NOT is_won AND NOT is_lost ORDER BY sort LIMIT 1", UUID.class);
        if (stage == null) {
            throw ApiException.bad("Enquiries aren't open here yet");
        }
        String business = clip(b.str("business", null), 120);
        String moveIn = clip(b.str("moveIn", null), 40);
        String message = clip(b.str("message", null), 500);
        UUID deal = db.insert("""
                INSERT INTO deals (tenant_id, title, contact_name, phone, email, value, stage_id, source, space_id)
                VALUES (:t, :title, :name, :phone, :email, :value, :stage, 'TO_LET', :space) RETURNING id""",
                "title", sp.get("name") + " · " + name, "name", name, "phone", phone, "email", clip(b.str("email", null), 200),
                "value", sp.get("rate"), "stage", stage, "space", spaceId);
        String note = "Enquiry from the to-let page" + (business == null ? "" : "\nTheir business: " + business) + (moveIn == null ? "" : "\nWants to move in: " + moveIn)
                + (message == null ? "" : "\n" + message);
        db.exec("INSERT INTO activities (tenant_id, deal_id, kind, body) VALUES (:t, :d, 'NOTE', :b)", "d", deal, "b", note);
        for (Map<String, Object> o : db.list("SELECT email, name FROM users WHERE tenant_id = :t AND role IN ('OWNER','MANAGER') AND active AND email IS NOT NULL")) {
            mailer.send((String) o.get("email"), (String) o.get("name"), "Enquiry for " + sp.get("name") + " from " + name,
                    name + " (" + phone + ") asked about " + sp.get("name") + ".\n" + note + "\n\nOpen Rent & tenants, To let: " + mailer.appUrl() + "/property",
                    "<p><b>" + esc(name) + "</b> (" + esc(phone) + ") asked about <b>" + esc(String.valueOf(sp.get("name"))) + "</b>.</p><p style=\"white-space:pre-line\">"
                            + esc(note) + "</p><p><a href=\"" + esc(mailer.appUrl() + "/property") + "\">Open Rent &amp; tenants, To let</a></p>");
        }
        return Map.of("received", true);
    }

    /** Open enquiries from the to-let page, newest first. */
    public List<Map<String, Object>> leads() {
        return db.list("""
                SELECT d.id, d.contact_name, d.phone, d.email, d.created_at, d.space_id, sp.name AS unit_name, s.name AS stage,
                       (SELECT body FROM activities a WHERE a.deal_id = d.id ORDER BY a.created_at LIMIT 1) AS note
                FROM deals d JOIN pipeline_stages s ON s.id = d.stage_id LEFT JOIN spaces sp ON sp.id = d.space_id
                WHERE d.source = 'TO_LET' AND NOT s.is_won AND NOT s.is_lost ORDER BY d.created_at DESC LIMIT 200""");
    }

    /** The lead became the tenant: their lead is won and linked to the new tenant. */
    public void won(UUID dealId, UUID customerId) {
        UUID wonStage = db.value("SELECT id FROM pipeline_stages WHERE is_won ORDER BY sort LIMIT 1", UUID.class);
        if (wonStage != null) {
            db.exec("UPDATE deals SET stage_id = :s, customer_id = :c, closed_at = now(), last_activity_at = now() WHERE id = :id",
                    "id", dealId, "s", wonStage, "c", customerId);
        }
    }

    private static String clip(String s, int n) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > n ? t.substring(0, n) : t;
    }
}
