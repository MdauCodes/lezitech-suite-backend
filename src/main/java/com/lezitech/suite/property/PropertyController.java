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

/**
 * Landlord side: units (shops, stalls, offices, apartments), leases, monthly rent invoices, payments, statements,
 * penalties and arrears. The money rules live in {@link LeaseLedger}.
 */
@RestController
@RequestMapping("/api/property")
@Transactional
public class PropertyController {

    static final String UNIT_KINDS = "('SHOP','STALL','OFFICE','APARTMENT')";

    private final Db db;
    private final OrderService orders;
    private final LeaseLedger ledger;

    public PropertyController(Db db, OrderService orders, LeaseLedger ledger) {
        this.db = db;
        this.orders = orders;
        this.ledger = ledger;
    }

    /** Every unit with its M-Pesa account number, current lease, occupier and what they owe. */
    @GetMapping("/units")
    public List<Map<String, Object>> units() {
        return db.list("""
                SELECT sp.*, """ + LeaseLedger.CODE_SQL + """
                        AS pay_account, l.id AS lease_id, l.rent AS lease_rent, l.service_charge AS lease_service_charge, l.starts_on, l.ends_on,
                       l.due_day, l.credit, l.penalty_kind, l.penalty_value, l.penalty_grace_days,
                       c.id AS occupier_id, c.name AS occupier_name, c.phone AS occupier_phone, c.email AS occupier_email,
                       (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN') AS arrears,
                       (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN'
                            AND o.due_on < current_date) AS overdue
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
        String penaltyKind = penaltyKind(b.str("penaltyKind", "NONE"));
        BigDecimal penaltyValue = b.num("penaltyValue", BigDecimal.ZERO).max(BigDecimal.ZERO);
        UUID customerId = b.has("customerId") ? UUID.fromString(b.str("customerId"))
                : db.insert("INSERT INTO customers (tenant_id, name, phone, email, company, kind) VALUES (:t, :name, :phone, :email, :name, 'CORPORATE') RETURNING id",
                "name", b.str("tenantName"), "phone", b.str("phone", null), "email", blankToNull(b.str("email", null)));
        UUID id = db.insert("""
                INSERT INTO leases (tenant_id, space_id, customer_id, starts_on, ends_on, rent, service_charge, deposit, due_day,
                                    penalty_kind, penalty_value, penalty_grace_days)
                VALUES (:t, :spaceId, :customerId, :start, :end, :rent, :svc, :deposit, :due, :pk, :pv, :pg) RETURNING id""",
                "spaceId", spaceId, "customerId", customerId,
                "start", b.has("startsOn") ? b.date("startsOn") : today(), "end", b.date("endsOn"),
                "rent", b.num("rent", (BigDecimal) space.get("rate")), "svc", b.num("serviceCharge", (BigDecimal) space.get("serviceCharge")),
                "deposit", b.num("deposit", BigDecimal.ZERO), "due", dueDay(b.integer("dueDay", 5)),
                "pk", penaltyKind, "pv", "NONE".equals(penaltyKind) ? BigDecimal.ZERO : penaltyValue,
                "pg", graceDays(b.integer("penaltyGraceDays", 5)));
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

    /** The month's invoices for every active lease that doesn't have one yet; any credit the tenant has is used on them. */
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
            UUID order = orders.createTrusted(new Body(inv));
            db.exec("UPDATE orders SET lease_id = :leaseId, period = :p WHERE id = :order", "leaseId", leaseId, "p", period.toString(), "order", order);
            ledger.useCredit(leaseId);
            created++;
        }
        return created;
    }

    /** The unit's M-Pesa account number (what tenants type on the paybill). Blank goes back to the unit's name. */
    @PostMapping("/units/{id}/account")
    public Map<String, Object> account(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        String code = LeaseLedger.normalizeCode(new Body(raw).str("accountCode", ""));
        if (code.length() > 20) {
            throw ApiException.bad("Keep the account number to 20 letters or numbers");
        }
        db.one("Unit", "SELECT id FROM spaces WHERE id = :id AND kind IN " + UNIT_KINDS, "id", id);
        db.exec("UPDATE spaces SET account_code = :c WHERE id = :id", "id", id, "c", code.isEmpty() ? null : code);
        String effective = db.value("SELECT " + LeaseLedger.CODE_SQL + " FROM spaces sp WHERE sp.id = :id", String.class, "id", id);
        Long clash = db.value("SELECT count(*) FROM spaces sp WHERE sp.active AND sp.id <> :id AND sp.kind IN " + UNIT_KINDS
                + " AND " + LeaseLedger.CODE_SQL + " = :c", Long.class, "id", id, "c", effective);
        if (clash != null && clash > 0) {
            throw ApiException.conflict("Another unit already uses account number " + effective + ". Payments to them couldn't be told apart.");
        }
        return Map.of("accountCode", effective);
    }

    /** Late-payment penalty for a lease: NONE, a FIXED amount, or a PERCENT of what's unpaid, after the grace days. */
    @PostMapping("/leases/{id}/penalty")
    public Map<String, Object> penalty(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        String kind = penaltyKind(b.str("penaltyKind", "NONE"));
        BigDecimal value = b.num("penaltyValue", BigDecimal.ZERO);
        if (!"NONE".equals(kind) && value.signum() <= 0) {
            throw ApiException.bad("Enter the penalty amount or percentage");
        }
        if ("PERCENT".equals(kind) && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.bad("A percentage penalty can't be more than 100%");
        }
        db.one("Lease", "SELECT id FROM leases WHERE id = :id", "id", id);
        db.exec("UPDATE leases SET penalty_kind = :k, penalty_value = :v, penalty_grace_days = :g WHERE id = :id",
                "id", id, "k", kind, "v", "NONE".equals(kind) ? BigDecimal.ZERO : value, "g", graceDays(b.integer("penaltyGraceDays", 5)));
        return db.one("Lease", "SELECT * FROM leases WHERE id = :id", "id", id);
    }

    /** Money received from the tenant outside a prompt (cash, bank, cheque, or an M-Pesa code checked by hand). */
    @PostMapping("/leases/{id}/payments")
    public Map<String, Object> receive(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        LocalDate on = b.has("receivedOn") ? b.date("receivedOn") : today();
        if (on.isAfter(today())) {
            throw ApiException.bad("The date received can't be in the future");
        }
        String method = b.str("method", "MPESA").toUpperCase();
        if ("MPESA".equals(method) && b.str("reference", "").isBlank()) {
            throw ApiException.bad("Enter the M-Pesa code from the message, so it can be checked and isn't recorded twice");
        }
        return ledger.receive(id, b.num("amount"), method, b.str("reference", null), "MANUAL", b.str("note", null), on);
    }

    /** The tenant's statement for a period (default: the last six months), with the link to share with them. */
    @GetMapping("/leases/{id}/statement")
    public Map<String, Object> statement(@PathVariable UUID id, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        Map<String, Object> s = ledger.statement(id, from == null || from.isBlank() ? null : LocalDate.parse(from),
                to == null || to.isBlank() ? null : LocalDate.parse(to));
        s.put("link", link(id));
        return s;
    }

    /** A new statement link (the old one stops working), e.g. if it was sent to the wrong person. */
    @PostMapping("/leases/{id}/statement/new-link")
    public Map<String, Object> newLink(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        db.one("Lease", "SELECT id FROM leases WHERE id = :id", "id", id);
        db.exec("UPDATE leases SET statement_token = replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '') WHERE id = :id", "id", id);
        return Map.of("link", link(id));
    }

    private String link(UUID leaseId) {
        return "/statement/" + db.value("SELECT slug FROM tenants WHERE id = :t", String.class) + "/"
                + db.value("SELECT statement_token FROM leases WHERE id = :id", String.class, "id", leaseId);
    }

    /** Lift (waive) a late-payment penalty, with the reason. */
    @PostMapping("/penalties/{orderId}/lift")
    public Map<String, Object> lift(@PathVariable UUID orderId, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return ledger.liftPenalty(orderId, new Body(raw).str("reason", ""));
    }

    /** Everyone who owes, the longest-waiting first: the arrears list. */
    @GetMapping("/arrears")
    public List<Map<String, Object>> arrears() {
        return db.list("""
                SELECT l.id AS lease_id, sp.name AS unit_name, c.name AS tenant_name, c.phone AS tenant_phone, c.email AS tenant_email, l.credit,
                       sum(o.total - o.paid) AS owed,
                       sum(CASE WHEN o.due_on < current_date THEN o.total - o.paid ELSE 0 END) AS overdue,
                       min(o.due_on) AS oldest_due, count(*) AS invoices
                FROM orders o JOIN leases l ON l.id = o.lease_id JOIN spaces sp ON sp.id = l.space_id JOIN customers c ON c.id = l.customer_id
                WHERE o.status = 'OPEN' AND o.kind = 'INVOICE' AND o.total > o.paid
                GROUP BY l.id, sp.name, c.name, c.phone, c.email, l.credit
                ORDER BY min(o.due_on) NULLS LAST, sp.name""");
    }

    /** Rent settings: invoices raised by themselves each month, and email reminders before and after the due day. */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return RentSettings.withDefaults(db.value("SELECT coalesce(settings->'rent', '{}'::jsonb)::text FROM tenants WHERE id = :t", String.class));
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Map<String, Object> merged = new LinkedHashMap<>(settings());
        merged.putAll(RentSettings.clean(raw));
        db.exec("UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{rent}', cast(:v as jsonb)) WHERE id = :t",
                "v", db.toJson(merged));
        return settings();
    }

    /** Rent roll: one row per invoice for the month, with what's been paid. */
    @GetMapping("/rent-roll")
    public Map<String, Object> rentRoll(@RequestParam(required = false) String period) {
        String p = period == null ? YearMonth.now(ZoneId.of("Africa/Nairobi")).toString() : period;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("invoices", db.list("""
                SELECT o.id, o.number, o.total, o.paid, o.status, o.due_on, l.id AS lease_id, sp.name AS unit_name, c.name AS occupier_name, c.phone AS occupier_phone,
                       (o.status = 'OPEN' AND o.due_on < current_date) AS overdue
                FROM orders o JOIN leases l ON l.id = o.lease_id JOIN spaces sp ON sp.id = l.space_id
                LEFT JOIN customers c ON c.id = o.customer_id
                WHERE o.period = :p AND o.status <> 'VOID' ORDER BY sp.sort, sp.name""", "p", p));
        out.put("received", db.value("""
                SELECT coalesce(sum(amount), 0) FROM lease_receipts WHERE to_char(received_on, 'YYYY-MM') = :p""", BigDecimal.class, "p", p));
        return out;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Africa/Nairobi"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String penaltyKind(String raw) {
        String k = raw == null ? "NONE" : raw.toUpperCase();
        if (!List.of("NONE", "FIXED", "PERCENT").contains(k)) {
            throw ApiException.bad("Choose the penalty: none, a fixed amount, or a percentage of what's unpaid");
        }
        return k;
    }

    private static int graceDays(Integer d) {
        int g = d == null ? 5 : d;
        if (g < 0 || g > 60) {
            throw ApiException.bad("Grace days must be between 0 and 60");
        }
        return g;
    }

    private static int dueDay(Integer d) {
        int g = d == null ? 5 : d;
        if (g < 1 || g > 28) {
            throw ApiException.bad("The due day must be between the 1st and the 28th");
        }
        return g;
    }
}
