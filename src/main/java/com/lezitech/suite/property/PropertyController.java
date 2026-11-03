package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Landlord side: units (shops, stalls, offices, apartments), leases, monthly rent invoices and arrears. */
@RestController
@RequestMapping("/api/property")
@Transactional
public class PropertyController {

    static final String UNIT_KINDS = "('SHOP','STALL','OFFICE','APARTMENT')";

    private final Db db;
    private final OrderService orders;

    public PropertyController(Db db, OrderService orders) {
        this.db = db;
        this.orders = orders;
    }

    /** Every unit with its current lease, occupier and what they owe. */
    @GetMapping("/units")
    public List<Map<String, Object>> units() {
        return db.list("""
                SELECT sp.*, l.id AS lease_id, l.rent AS lease_rent, l.service_charge AS lease_service_charge, l.starts_on, l.ends_on,
                       l.due_day, c.id AS occupier_id, c.name AS occupier_name, c.phone AS occupier_phone,
                       (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN') AS arrears
                FROM spaces sp
                LEFT JOIN leases l ON l.space_id = sp.id AND l.status = 'ACTIVE'
                LEFT JOIN customers c ON c.id = l.customer_id
                WHERE sp.active AND sp.kind IN """ + " " + UNIT_KINDS + " ORDER BY sp.sort, sp.floor NULLS FIRST, sp.name");
    }

    @PostMapping("/leases")
    public Map<String, Object> lease(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        UUID spaceId = UUID.fromString(b.str("spaceId"));
        if (db.find("SELECT id FROM leases WHERE space_id = :spaceId AND status = 'ACTIVE'", "spaceId", spaceId).isPresent()) {
            throw ApiException.conflict("That unit already has an active lease");
        }
        Map<String, Object> space = db.one("Unit", "SELECT * FROM spaces WHERE id = :spaceId", "spaceId", spaceId);
        UUID customerId = b.has("customerId") ? UUID.fromString(b.str("customerId"))
                : db.insert("INSERT INTO customers (tenant_id, name, phone, email, company, kind) VALUES (:t, :name, :phone, :email, :name, 'CORPORATE') RETURNING id",
                "name", b.str("tenantName"), "phone", b.str("phone", null), "email", b.str("email", null));
        UUID id = db.insert("""
                INSERT INTO leases (tenant_id, space_id, customer_id, starts_on, ends_on, rent, service_charge, deposit, due_day)
                VALUES (:t, :spaceId, :customerId, :start, :end, :rent, :svc, :deposit, :due) RETURNING id""",
                "spaceId", spaceId, "customerId", customerId,
                "start", b.has("startsOn") ? b.date("startsOn") : today(), "end", b.date("endsOn"),
                "rent", b.num("rent", (BigDecimal) space.get("rate")), "svc", b.num("serviceCharge", (BigDecimal) space.get("serviceCharge")),
                "deposit", b.num("deposit", BigDecimal.ZERO), "due", b.integer("dueDay", 5));
        return db.one("Lease", "SELECT * FROM leases WHERE id = :id", "id", id);
    }

    @PostMapping("/leases/{id}/end")
    public Map<String, Object> end(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        db.exec("UPDATE leases SET status = 'ENDED', ends_on = coalesce(ends_on, current_date) WHERE id = :id", "id", id);
        return db.one("Lease", "SELECT * FROM leases WHERE id = :id", "id", id);
    }

    /** Raise this month's rent and service-charge invoices for every active lease. Safe to run twice. */
    @PostMapping("/invoices/generate")
    public Map<String, Object> generate(@RequestBody(required = false) Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        YearMonth period = new Body(raw).has("period") ? YearMonth.parse(new Body(raw).str("period")) : YearMonth.now(ZoneId.of("Africa/Nairobi"));
        return Map.of("created", raiseInvoices(period), "period", period.toString());
    }

    public int raiseInvoices(YearMonth period) {
        int created = 0;
        for (Map<String, Object> l : db.list("""
                SELECT l.*, sp.name AS unit_name FROM leases l JOIN spaces sp ON sp.id = l.space_id
                WHERE l.status = 'ACTIVE' AND l.starts_on <= :last AND (l.ends_on IS NULL OR l.ends_on >= :first)""",
                "first", period.atDay(1), "last", period.atEndOfMonth())) {
            UUID leaseId = UUID.fromString(l.get("id").toString());
            if (db.find("SELECT id FROM orders WHERE lease_id = :leaseId AND period = :p AND status <> 'VOID'",
                    "leaseId", leaseId, "p", period.toString()).isPresent()) {
                continue;
            }
            List<Map<String, Object>> lines = new ArrayList<>();
            lines.add(Map.of("name", "Rent · " + l.get("unitName") + " · " + period, "unitPrice", l.get("rent"), "qty", 1, "taxRate", 0));
            if (((BigDecimal) l.get("serviceCharge")).signum() > 0) {
                lines.add(Map.of("name", "Service charge · " + period, "unitPrice", l.get("serviceCharge"), "qty", 1, "taxRate", 16));
            }
            int dueDay = Math.min((Integer) l.get("dueDay"), period.lengthOfMonth());
            Map<String, Object> inv = new HashMap<>();
            inv.put("kind", "INVOICE");
            inv.put("channel", "RENT");
            inv.put("customerId", l.get("customerId"));
            inv.put("dueOn", period.atDay(dueDay).toString());
            inv.put("lines", lines);
            UUID order = orders.create(new Body(inv));
            db.exec("UPDATE orders SET lease_id = :leaseId, period = :p WHERE id = :order", "leaseId", leaseId, "p", period.toString(), "order", order);
            created++;
        }
        return created;
    }

    /** Rent roll: one row per invoice for the month, with what's been paid. */
    @GetMapping("/rent-roll")
    public Map<String, Object> rentRoll(@RequestParam(required = false) String period) {
        String p = period == null ? YearMonth.now(ZoneId.of("Africa/Nairobi")).toString() : period;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("invoices", db.list("""
                SELECT o.id, o.number, o.total, o.paid, o.status, o.due_on, sp.name AS unit_name, c.name AS occupier_name, c.phone AS occupier_phone,
                       (o.status = 'OPEN' AND o.due_on < current_date) AS overdue
                FROM orders o JOIN leases l ON l.id = o.lease_id JOIN spaces sp ON sp.id = l.space_id
                LEFT JOIN customers c ON c.id = o.customer_id
                WHERE o.period = :p AND o.status <> 'VOID' ORDER BY sp.sort, sp.name""", "p", p));
        return out;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Africa/Nairobi"));
    }
}
