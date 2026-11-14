package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Floors of a building, in their real order (basements, ground, 1st, 2nd...), for buildings of any height (owner,
 * 2026-11-14). A floor's units can be made in one go ("20 shops, S301 to S320, 150 sq ft, KES 25,000"). Units keep their
 * floor by name ({@code spaces.floor}); floors already typed on units are picked up and put in a sensible order.
 * Callers run inside the workspace's transaction.
 */
@Service
public class Floors {

    /** SQL ordering units by their floor's place in the building, then their own order. */
    public static final String UNIT_ORDER = "(SELECT f.sort FROM building_floors f WHERE f.name = sp.floor) NULLS LAST, sp.sort, sp.name";

    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    private final Db db;

    public Floors(Db db) {
        this.db = db;
    }

    /** A guess at a floor's place from its name: basements below ground (0), "3rd" or "Floor 3" at 3. */
    static Integer guess(String name) {
        String n = name.toLowerCase().trim();
        Matcher m = NUMBER.matcher(n);
        Integer num = m.find() ? Integer.valueOf(m.group(1)) : null;
        if (n.startsWith("b") && (n.contains("basement") || n.matches("b\\d*"))) {
            return -(num == null ? 1 : num);
        }
        if (n.contains("lower ground") || n.equals("lg")) {
            return -1;
        }
        if (n.contains("ground") || n.equals("g") || n.equals("gf")) {
            return 0;
        }
        if (n.contains("mezz")) {
            return 1;
        }
        if (n.contains("roof") || n.contains("top")) {
            return 1000;
        }
        return num;
    }

    /** Floors typed on units but not set up yet are added, in a sensible place. */
    public void pickUpTyped() {
        for (Map<String, Object> r : db.list("""
                SELECT DISTINCT sp.floor FROM spaces sp WHERE sp.floor IS NOT NULL AND sp.floor <> ''
                  AND NOT EXISTS (SELECT 1 FROM building_floors f WHERE f.name = sp.floor)""")) {
            String name = (String) r.get("floor");
            Integer g = guess(name);
            int sort = g != null ? g * 10 : nextSort();
            db.exec("INSERT INTO building_floors (tenant_id, name, sort) VALUES (:t, :n, :s) ON CONFLICT DO NOTHING", "n", name, "s", sort);
        }
    }

    private int nextSort() {
        Integer max = db.value("SELECT max(sort) FROM building_floors WHERE sort < 10000", Integer.class);
        return max == null ? 0 : max + 10;
    }

    public List<Map<String, Object>> list() {
        pickUpTyped();
        return db.list("""
                SELECT f.id, f.name, f.sort,
                       (SELECT count(*) FROM spaces sp WHERE sp.floor = f.name AND sp.active AND sp.kind IN """ + " " + PropertyController.UNIT_KINDS + """
                       ) AS units,
                       (SELECT count(*) FROM spaces sp JOIN leases l ON l.space_id = sp.id AND l.status = 'ACTIVE' WHERE sp.floor = f.name AND sp.active) AS let
                FROM building_floors f ORDER BY f.sort, f.name""");
    }

    /** A new floor, placed by its name's guess (or at the top). */
    public List<Map<String, Object>> add(String nameRaw) {
        String name = clean(nameRaw);
        if (db.find("SELECT id FROM building_floors WHERE lower(name) = lower(:n)", "n", name).isPresent()) {
            throw ApiException.conflict("There's already a floor called " + name);
        }
        pickUpTyped();
        Integer g = guess(name);
        db.exec("INSERT INTO building_floors (tenant_id, name, sort) VALUES (:t, :n, :s)", "n", name, "s", g != null ? g * 10 : nextSort());
        return list();
    }

    public List<Map<String, Object>> rename(UUID id, String nameRaw) {
        String name = clean(nameRaw);
        Map<String, Object> f = db.one("Floor", "SELECT name FROM building_floors WHERE id = :id", "id", id);
        String old = (String) f.get("name");
        if (!old.equals(name) && db.find("SELECT id FROM building_floors WHERE lower(name) = lower(:n) AND id <> :id", "n", name, "id", id).isPresent()) {
            throw ApiException.conflict("There's already a floor called " + name);
        }
        db.exec("UPDATE spaces SET floor = :n WHERE floor = :o", "n", name, "o", old);
        db.exec("UPDATE floor_plans SET floor = :n WHERE floor = :o", "n", name, "o", old);
        db.exec("UPDATE building_floors SET name = :n WHERE id = :id", "n", name, "id", id);
        return list();
    }

    /** The floors from bottom to top, as the landlord ordered them. */
    public List<Map<String, Object>> reorder(List<String> ids) {
        int i = 0;
        for (String id : ids) {
            db.exec("UPDATE building_floors SET sort = :s WHERE id = :id", "s", (i++) * 10, "id", UUID.fromString(id));
        }
        return list();
    }

    public List<Map<String, Object>> remove(UUID id) {
        Map<String, Object> f = db.one("Floor", "SELECT name FROM building_floors WHERE id = :id", "id", id);
        Long units = db.value("SELECT count(*) FROM spaces WHERE floor = :n AND active", Long.class, "n", f.get("name"));
        if (units != null && units > 0) {
            throw ApiException.conflict("This floor still has units. Move or remove them first.");
        }
        db.exec("DELETE FROM building_floors WHERE id = :id", "id", id);
        return list();
    }

    /**
     * Make a floor's units in one go: {firstName "S301", count 20, kind, areaSqft, rate, serviceCharge, typeName}. Names
     * count up from the first (S301, S302 ... S320). Refused if any name is taken.
     */
    public Map<String, Object> bulkUnits(UUID floorId, Body b) {
        Map<String, Object> f = db.one("Floor", "SELECT name FROM building_floors WHERE id = :id", "id", floorId);
        String first = b.str("firstName", "").trim().toUpperCase();
        int count = b.integer("count", 0);
        if (first.isEmpty() || count < 1 || count > 200) {
            throw ApiException.bad("Give the first unit's name (e.g. S301) and how many units (1 to 200)");
        }
        String kind = b.str("kind", "SHOP").toUpperCase();
        if (!Set.of("SHOP", "STALL", "OFFICE", "APARTMENT").contains(kind)) {
            throw ApiException.bad("Choose the kind: shop, stall, office or apartment");
        }
        List<String> names = names(first, count);
        List<String> taken = new ArrayList<>();
        for (String n : names) {
            if (db.find("SELECT id FROM spaces WHERE upper(name) = :n AND active", "n", n).isPresent()) {
                taken.add(n);
            }
        }
        if (!taken.isEmpty()) {
            throw ApiException.conflict("These unit names are already used: " + String.join(", ", taken.subList(0, Math.min(10, taken.size())))
                    + (taken.size() > 10 ? "…" : ""));
        }
        Integer sort = db.value("SELECT coalesce(max(sort), 0) + 1 FROM spaces", Integer.class);
        BigDecimal rate = b.num("rate", BigDecimal.ZERO);
        BigDecimal svc = b.num("serviceCharge", BigDecimal.ZERO);
        if (rate.signum() < 0 || svc.signum() < 0) {
            throw ApiException.bad("Rent can't be negative");
        }
        Integer area = b.integer("areaSqft", null);
        for (String n : names) {
            db.exec("""
                    INSERT INTO spaces (tenant_id, name, kind, type_name, capacity, rate, service_charge, area_sqft, floor, sort)
                    VALUES (:t, :n, :k, :type, 1, :r, :s, :a, :f, :o)""",
                    "n", n, "k", kind, "type", b.str("typeName", null), "r", rate, "s", svc, "a", area, "f", f.get("name"), "o", sort++);
        }
        return Map.of("created", names.size(), "first", names.get(0), "last", names.get(names.size() - 1));
    }

    /** "S301", 3 → S301, S302, S303 (keeps leading zeros: "G01" → G01, G02). Without a number: "KIOSK" → KIOSK1, KIOSK2. */
    static List<String> names(String first, int count) {
        Matcher m = Pattern.compile("^(.*?)(\\d+)$").matcher(first);
        String prefix = m.matches() ? m.group(1) : first;
        String digits = m.matches() ? m.group(2) : "1";
        long start = Long.parseLong(digits);
        int width = m.matches() ? digits.length() : 1;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String num = String.valueOf(start + i);
            while (num.length() < width) {
                num = "0" + num;
            }
            String n = prefix + num;
            if (n.length() > 60) {
                throw ApiException.bad("The unit names are too long");
            }
            out.add(n);
        }
        return out;
    }

    private static String clean(String raw) {
        String n = raw == null ? "" : raw.trim();
        if (n.isEmpty() || n.length() > 20) {
            throw ApiException.bad("Give the floor a short name (up to 20 letters), e.g. Ground, 1st, Basement");
        }
        return n;
    }
}
