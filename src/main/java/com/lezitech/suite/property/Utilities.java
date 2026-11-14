package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Water and electricity meters (owner's decisions, 2026-11-14). The caretaker reads each unit's meter (with a photo);
 * the usage since the last reading goes on the tenant's next monthly bill, at the property's price per unit (or the
 * unit's own price), plus an optional fixed monthly fee. The landlord charges water, electricity, both or none
 * ({@code settings.rent.utilities}), and can switch a kind off for one lease ({@code leases.charge_water/power}).
 * A unit's first reading (or one on a new meter) only sets the start. Callers run inside the workspace's transaction.
 */
@Service
public class Utilities {

    private final Db db;
    private final FileService files;

    public Utilities(Db db, FileService files) {
        this.db = db;
        this.files = files;
    }

    static String label(String kind) {
        return "WATER".equals(kind) ? "Water" : "Electricity";
    }

    /** A meter reading for a unit. Refused if it's below the last one (unless the meter was replaced). */
    public Map<String, Object> record(UUID spaceId, String kindRaw, BigDecimal reading, LocalDate readOn, String note, boolean newMeter) {
        String kind = kindRaw == null ? "" : kindRaw.toUpperCase();
        if (!RentSettings.KINDS.contains(kind)) {
            throw ApiException.bad("Choose water or electricity");
        }
        if (reading == null || reading.signum() < 0) {
            throw ApiException.bad("Enter the number on the meter");
        }
        LocalDate day = readOn == null ? LeaseLedger.today() : readOn;
        if (day.isAfter(LeaseLedger.today())) {
            throw ApiException.bad("The reading date can't be in the future");
        }
        db.one("Unit", "SELECT id FROM spaces WHERE id = :id AND kind IN " + PropertyController.UNIT_KINDS, "id", spaceId);
        Map<String, Object> last = db.find("""
                SELECT reading, read_on FROM meter_readings WHERE space_id = :s AND kind = :k ORDER BY read_on DESC, created_at DESC LIMIT 1""",
                "s", spaceId, "k", kind).orElse(null);
        BigDecimal previous = last == null || newMeter ? null : (BigDecimal) last.get("reading");
        if (previous != null && reading.compareTo(previous) < 0) {
            throw ApiException.bad("That's lower than the last reading (" + previous.stripTrailingZeros().toPlainString()
                    + "). Check the number, or tick \"new meter\" if it was replaced.");
        }
        BigDecimal used = previous == null ? BigDecimal.ZERO : reading.subtract(previous);
        UUID lease = db.value("SELECT id FROM leases WHERE space_id = :s AND status = 'ACTIVE' LIMIT 1", UUID.class, "s", spaceId);
        TenantContext.Caller c = TenantContext.caller();
        UUID id = db.insert("""
                INSERT INTO meter_readings (tenant_id, space_id, kind, reading, previous, used, read_on, note, new_meter, read_by, read_by_name, lease_id)
                VALUES (:t, :s, :k, :r, :p, :u, :d, :n, :nm, :by, :name, :lease) RETURNING id""",
                "s", spaceId, "k", kind, "r", reading, "p", previous, "u", used, "d", day, "n", note == null || note.isBlank() ? null : note.trim(),
                "nm", newMeter, "by", c.userId(), "name", c.name(), "lease", lease);
        return db.one("Reading", "SELECT * FROM meter_readings WHERE id = :id", "id", id);
    }

    public Map<String, Object> addPhoto(UUID readingId, byte[] bytes) {
        Map<String, Object> r = db.one("Reading", "SELECT id, billed_order_id FROM meter_readings WHERE id = :id", "id", readingId);
        UUID file = files.save(FileService.Purpose.REPAIR_PHOTO, bytes);
        db.exec("UPDATE meter_readings SET photo_file_id = :f WHERE id = :id", "id", readingId, "f", file);
        return db.one("Reading", "SELECT * FROM meter_readings WHERE id = :id", "id", r.get("id"));
    }

    /** A wrong reading not yet billed can be removed (and taken again). */
    public void remove(UUID readingId) {
        Map<String, Object> r = db.one("Reading", "SELECT billed_order_id, space_id, kind, created_at FROM meter_readings WHERE id = :id", "id", readingId);
        if (r.get("billedOrderId") != null) {
            throw ApiException.conflict("This reading is already on a bill; it can't be removed");
        }
        Boolean newer = db.value("""
                SELECT EXISTS (SELECT 1 FROM meter_readings WHERE space_id = :s AND kind = :k AND created_at > cast(:c as timestamptz))""",
                Boolean.class, "s", r.get("spaceId"), "k", r.get("kind"), "c", r.get("createdAt"));
        if (Boolean.TRUE.equals(newer)) {
            throw ApiException.conflict("Only the latest reading of a meter can be removed");
        }
        db.exec("DELETE FROM meter_readings WHERE id = :id", "id", readingId);
    }

    /** Every unit with its last water and electricity readings, and whether the current tenant is charged for each. */
    public List<Map<String, Object>> board() {
        return db.list("""
                SELECT sp.id, sp.name, sp.floor, sp.water_price, sp.power_price, l.id AS lease_id, l.charge_water, l.charge_power, c.name AS tenant_name,
                       (SELECT row_to_json(x) FROM (SELECT reading, read_on AS "readOn", used, billed_order_id IS NOT NULL AS billed FROM meter_readings
                            WHERE space_id = sp.id AND kind = 'WATER' ORDER BY read_on DESC, created_at DESC LIMIT 1) x) AS last_water,
                       (SELECT row_to_json(x) FROM (SELECT reading, read_on AS "readOn", used, billed_order_id IS NOT NULL AS billed FROM meter_readings
                            WHERE space_id = sp.id AND kind = 'POWER' ORDER BY read_on DESC, created_at DESC LIMIT 1) x) AS last_power
                FROM spaces sp LEFT JOIN leases l ON l.space_id = sp.id AND l.status = 'ACTIVE' LEFT JOIN customers c ON c.id = l.customer_id
                WHERE sp.active AND sp.kind IN """ + " " + PropertyController.UNIT_KINDS + " ORDER BY sp.sort, sp.floor NULLS FIRST, sp.name");
    }

    public List<Map<String, Object>> history(UUID spaceId) {
        return db.list("""
                SELECT r.*, o.number AS bill_number FROM meter_readings r LEFT JOIN orders o ON o.id = r.billed_order_id
                WHERE r.space_id = :s ORDER BY r.read_on DESC, r.created_at DESC LIMIT 60""", "s", spaceId);
    }

    /** Per-unit price (blank = the property's) and, per lease, whether each kind is charged (blank = as the property). */
    public void setUnitPrices(UUID spaceId, BigDecimal water, BigDecimal power) {
        for (BigDecimal p : new BigDecimal[]{water, power}) {
            if (p != null && p.signum() < 0) {
                throw ApiException.bad("A price can't be negative");
            }
        }
        db.exec("UPDATE spaces SET water_price = :w, power_price = :p WHERE id = :id", "id", spaceId, "w", water, "p", power);
    }

    public void setLeaseCharges(UUID leaseId, Boolean water, Boolean power) {
        db.one("Lease", "SELECT id FROM leases WHERE id = :id", "id", leaseId);
        db.exec("UPDATE leases SET charge_water = :w, charge_power = :p WHERE id = :id", "id", leaseId, "w", water, "p", power);
    }

    /** Bill lines for this lease's next bill: usage not yet billed, and the fixed monthly fees. Returns lines and the readings used. */
    public record Billing(List<Map<String, Object>> lines, List<UUID> readings) {
    }

    @SuppressWarnings("unchecked")
    public Billing billing(Map<String, Object> lease, Map<String, Object> settings, String period) {
        List<Map<String, Object>> lines = new ArrayList<>();
        List<UUID> used = new ArrayList<>();
        Map<String, Object> utilities = (Map<String, Object>) settings.get("utilities");
        UUID leaseId = UUID.fromString(lease.get("id").toString());
        UUID spaceId = UUID.fromString(lease.get("spaceId").toString());
        Map<String, Object> space = db.one("Unit", "SELECT water_price, power_price FROM spaces WHERE id = :id", "id", spaceId);
        for (String kind : RentSettings.KINDS) {
            Map<String, Object> u = (Map<String, Object>) utilities.get(kind);
            Object override = lease.get("WATER".equals(kind) ? "chargeWater" : "chargePower");
            boolean charged = override instanceof Boolean b ? b && Boolean.TRUE.equals(u.get("charge")) : Boolean.TRUE.equals(u.get("charge"));
            if (!charged) {
                continue;
            }
            Object own = space.get("WATER".equals(kind) ? "waterPrice" : "powerPrice");
            BigDecimal price = own instanceof BigDecimal p ? p : new BigDecimal(String.valueOf(u.get("price")));
            String unit = String.valueOf(u.get("unit"));
            for (Map<String, Object> r : db.list("""
                    SELECT id, previous, reading, used, read_on FROM meter_readings
                    WHERE space_id = :s AND kind = :k AND billed_order_id IS NULL AND lease_id = :l AND used > 0
                    ORDER BY read_on, created_at""", "s", spaceId, "k", kind, "l", leaseId)) {
                BigDecimal qty = (BigDecimal) r.get("used");
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("name", label(kind) + " · " + plain((BigDecimal) r.get("previous")) + " → " + plain((BigDecimal) r.get("reading"))
                        + " (" + plain(qty) + " " + unit + ", read " + r.get("readOn") + ")");
                line.put("unitPrice", price.setScale(2, RoundingMode.HALF_UP));
                line.put("qty", qty);
                line.put("taxRate", 0);
                lines.add(line);
                used.add(UUID.fromString(r.get("id").toString()));
            }
            BigDecimal fee = new BigDecimal(String.valueOf(u.get("fixedFee")));
            if (fee.signum() > 0) {
                lines.add(Map.of("name", label(kind) + " meter fee · " + period, "unitPrice", fee.setScale(2, RoundingMode.HALF_UP), "qty", 1, "taxRate", 0));
            }
        }
        return new Billing(lines, used);
    }

    public void markBilled(List<UUID> readings, UUID orderId) {
        for (UUID r : readings) {
            db.exec("UPDATE meter_readings SET billed_order_id = :o WHERE id = :id", "id", r, "o", orderId);
        }
    }

    static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
