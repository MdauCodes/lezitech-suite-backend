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
    private final LeaseLife life;
    private final Utilities utilities;
    private final ServiceCharge serviceCharge;
    @org.springframework.beans.factory.annotation.Autowired
    private ToLet toLet;

    public PropertyController(Db db, OrderService orders, LeaseLedger ledger, LeaseLife life, Utilities utilities, ServiceCharge serviceCharge) {
        this.db = db;
        this.orders = orders;
        this.ledger = ledger;
        this.life = life;
        this.utilities = utilities;
        this.serviceCharge = serviceCharge;
    }

    /** Every unit with its M-Pesa account number, current lease, occupier and what they owe. */
    @GetMapping("/units")
    public List<Map<String, Object>> units() {
        return db.list("""
                SELECT sp.*, """ + LeaseLedger.CODE_SQL + """
                        AS pay_account, l.id AS lease_id, l.rent AS lease_rent, l.service_charge AS lease_service_charge, l.starts_on, l.ends_on,
                       l.due_day, l.credit, l.penalty_kind, l.penalty_value, l.penalty_grace_days, l.deposit,
                       l.increase_kind, l.increase_value, l.increase_every, l.next_increase_on, l.vacate_on, l.vacate_reason, l.charge_water, l.charge_power, l.frequency, l.own_business,
                       (SELECT ll.name FROM landlords ll WHERE ll.id = sp.landlord_id) AS landlord_name,
                       (SELECT CASE WHEN a.password_hash IS NOT NULL THEN 'ACTIVE' WHEN a.invited_at IS NOT NULL THEN 'INVITED' ELSE 'ASKED' END
                        FROM renter_accounts a WHERE a.customer_id = c.id AND a.active ORDER BY a.created_at LIMIT 1) AS portal,
                       (SELECT a.last_login_at FROM renter_accounts a WHERE a.customer_id = c.id AND a.active ORDER BY a.created_at LIMIT 1) AS portal_last_seen,
                       (SELECT count(*) FROM maintenance_requests m WHERE m.lease_id = l.id AND m.status NOT IN ('DONE','CLOSED','CANCELLED')) AS open_requests,
                       CASE WHEN l.id IS NULL OR l.deposit_settled_on IS NOT NULL THEN 0
                            ELSE l.deposit_before + (SELECT coalesce(sum(o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.channel = 'DEPOSIT' AND o.status <> 'VOID') END AS deposit_held,
                       c.id AS occupier_id, c.name AS occupier_name, c.phone AS occupier_phone, c.email AS occupier_email,
                       (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN') AS arrears,
                       (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN'
                            AND o.due_on < current_date) AS overdue
                FROM spaces sp
                LEFT JOIN leases l ON l.space_id = sp.id AND l.status = 'ACTIVE'
                LEFT JOIN customers c ON c.id = l.customer_id
                WHERE sp.active AND sp.kind IN """ + " " + UNIT_KINDS + " ORDER BY " + Floors.UNIT_ORDER);
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
        String frequency = b.str("frequency", "MONTHLY").toUpperCase();
        if (!List.of("MONTHLY", "WEEKLY", "DAILY").contains(frequency)) {
            throw ApiException.bad("Choose how often they pay: monthly, weekly or daily");
        }
        BigDecimal penaltyValue = b.num("penaltyValue", BigDecimal.ZERO).max(BigDecimal.ZERO);
        UUID customerId = b.has("customerId") ? UUID.fromString(b.str("customerId"))
                : db.insert("INSERT INTO customers (tenant_id, name, phone, email, company, kind) VALUES (:t, :name, :phone, :email, :name, 'CORPORATE') RETURNING id",
                "name", b.str("tenantName"), "phone", b.str("phone", null), "email", blankToNull(b.str("email", null)));
        UUID id = db.insert("""
                INSERT INTO leases (tenant_id, space_id, customer_id, starts_on, ends_on, rent, service_charge, deposit, due_day,
                                    penalty_kind, penalty_value, penalty_grace_days, frequency)
                VALUES (:t, :spaceId, :customerId, :start, :end, :rent, :svc, :deposit, :due, :pk, :pv, :pg, :freq) RETURNING id""",
                "spaceId", spaceId, "customerId", customerId,
                "start", b.has("startsOn") ? b.date("startsOn") : today(), "end", b.date("endsOn"),
                "rent", b.num("rent", (BigDecimal) space.get("rate")), "svc", b.num("serviceCharge", (BigDecimal) space.get("serviceCharge")),
                "deposit", b.num("deposit", BigDecimal.ZERO), "due", dueDay(b.integer("dueDay", 5)),
                "pk", penaltyKind, "pv", "NONE".equals(penaltyKind) ? BigDecimal.ZERO : penaltyValue,
                "pg", graceDays(b.integer("penaltyGraceDays", 5)), "freq", frequency);
        BigDecimal deposit = b.num("deposit", BigDecimal.ZERO);
        if (deposit.signum() < 0) {
            throw ApiException.bad("The deposit can't be negative");
        }
        if (deposit.signum() > 0) {
            if (b.bool("depositAlreadyReceived", false)) {
                db.exec("UPDATE leases SET deposit_before = deposit WHERE id = :id", "id", id);
            } else {
                life.billDeposit(id);
            }
        }
        // The landlord's own business renting the unit (billed like a tenant); and an enquiry that became this lease.
        db.exec("UPDATE leases SET own_business = :o WHERE id = :id", "id", id, "o", b.bool("ownBusiness", false));
        db.exec("UPDATE spaces SET owner_use = false WHERE id = :s", "s", spaceId);
        if (b.has("dealId") && !b.str("dealId", "").isBlank()) {
            toLet.won(UUID.fromString(b.str("dealId")), customerId);
        }
        if (!"NONE".equalsIgnoreCase(b.str("increaseKind", "NONE"))) {
            life.setIncrease(id, b.str("increaseKind"), b.num("increaseValue", BigDecimal.ZERO), b.integer("increaseEvery", 12));
        }
        life.event(id, "STARTED", "Rented out from " + (b.has("startsOn") ? b.date("startsOn") : today()) + " at rent "
                + b.num("rent", (BigDecimal) space.get("rate")).stripTrailingZeros().toPlainString()
                + (deposit.signum() > 0 ? ", deposit " + deposit.stripTrailingZeros().toPlainString()
                   + (b.bool("depositAlreadyReceived", false) ? " (already received)" : " (billed)") : ""), Map.of());
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

    /**
     * The month's invoices for every active lease that doesn't have one yet; any credit the tenant has is used on them.
     * A lease past its end date that wasn't renewed or ended carries on month to month (owner, 2026-11-13). Approved
     * rent increases due by the month are put into the rent first.
     */
    public int raiseInvoices(YearMonth period) {
        int created = 0;
        Map<String, Object> rentSettings = settings();
        String serviceMode = (String) rentSettings.get("serviceMode");
        for (Map<String, Object> found : db.list("""
                SELECT l.id FROM leases l WHERE l.status = 'ACTIVE' AND l.frequency = 'MONTHLY' AND l.starts_on <= :last""", "last", period.atEndOfMonth())) {
            UUID leaseId = UUID.fromString(found.get("id").toString());
            if (db.find("SELECT id FROM orders WHERE lease_id = :leaseId AND period = :p AND status <> 'VOID'",
                    "leaseId", leaseId, "p", period.toString()).isPresent()) {
                continue;
            }
            life.applyDueIncreases(leaseId, period.atDay(1));
            Map<String, Object> l = db.one("Lease", "SELECT l.*, sp.name AS unit_name FROM leases l JOIN spaces sp ON sp.id = l.space_id WHERE l.id = :id", "id", leaseId);
            List<Map<String, Object>> lines = new ArrayList<>();
            lines.add(Map.of("name", "Rent · " + l.get("unitName") + " · " + period, "unitPrice", l.get("rent"), "qty", 1, "taxRate", 0));
            // Service charge: a fixed amount on the lease, the unit's share of the year's budget, or included in the rent.
            BigDecimal service = "INCLUDED".equals(serviceMode) ? BigDecimal.ZERO
                    : "BUDGET".equals(serviceMode) ? serviceCharge.monthlyShare(UUID.fromString(l.get("spaceId").toString()), period.getYear())
                    : (BigDecimal) l.get("serviceCharge");
            if (service.signum() > 0) {
                lines.add(Map.of("name", "Service charge · " + period, "unitPrice", service, "qty", 1, "taxRate", 16));
            }
            // Water and electricity used since the last bill, for the kinds this tenant is charged.
            Utilities.Billing meters = utilities.billing(l, rentSettings, period.toString());
            lines.addAll(meters.lines());
            int dueDay = Math.min((Integer) l.get("dueDay"), period.lengthOfMonth());
            Map<String, Object> inv = new HashMap<>();
            inv.put("kind", "INVOICE");
            inv.put("channel", "RENT");
            inv.put("customerId", l.get("customerId"));
            inv.put("dueOn", period.atDay(dueDay).toString());
            inv.put("lines", lines);
            UUID order = orders.createTrusted(new Body(inv));
            db.exec("UPDATE orders SET lease_id = :leaseId, period = :p WHERE id = :order", "leaseId", leaseId, "p", period.toString(), "order", order);
            utilities.markBilled(meters.readings(), order);
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

    // ---------------- meters and service charge (M3) ----------------

    /** Every unit's last readings, and per lease whether water and electricity are charged. */
    @GetMapping("/meters")
    public List<Map<String, Object>> meters() {
        return utilities.board();
    }

    @PostMapping("/units/{id}/prices")
    public Map<String, Object> unitPrices(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        utilities.setUnitPrices(id, b.has("waterPrice") ? b.num("waterPrice", null) : null, b.has("powerPrice") ? b.num("powerPrice", null) : null);
        return Map.of("saved", true);
    }

    /** Per lease: chargeWater / chargePower true, false (included for this tenant) or null (as the property is set). */
    @PostMapping("/leases/{id}/utilities")
    public Map<String, Object> leaseUtilities(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Object w = raw == null ? null : raw.get("chargeWater");
        Object p = raw == null ? null : raw.get("chargePower");
        utilities.setLeaseCharges(id, w instanceof Boolean x ? x : null, p instanceof Boolean x ? x : null);
        life.event(id, "UTILITIES", "Water " + (Boolean.FALSE.equals(w) ? "included" : Boolean.TRUE.equals(w) ? "charged" : "as the property")
                + ", electricity " + (Boolean.FALSE.equals(p) ? "included" : Boolean.TRUE.equals(p) ? "charged" : "as the property"), Map.of());
        return Map.of("saved", true);
    }

    @GetMapping("/service-charge/{year}")
    public Map<String, Object> serviceChargeYear(@PathVariable int year) {
        return serviceCharge.summary(year);
    }

    @PostMapping("/service-charge/{year}/lines")
    public Map<String, Object> serviceChargeLines(@PathVariable int year, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return serviceCharge.setLines(year, new Body(raw).list("lines"));
    }

    @PostMapping("/service-charge/{year}/expenses")
    public Map<String, Object> serviceChargeExpense(@PathVariable int year, @RequestBody Map<String, Object> raw) {
        return serviceCharge.addExpense(year, new Body(raw));
    }

    @PostMapping("/service-charge/expenses/{id}/delete")
    public Map<String, Object> removeExpense(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        serviceCharge.removeExpense(id);
        return Map.of("removed", true);
    }

    @PostMapping("/service-charge/{year}/close")
    public Map<String, Object> closeYear(@PathVariable int year, @RequestBody(required = false) Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return serviceCharge.close(year, new Body(raw).str("note", null));
    }

    // ---------------- the lease's life (M2) ----------------

    /** The rent increase written in the lease: increaseKind NONE | PERCENT | FIXED, increaseValue, increaseEvery (months). */
    @PostMapping("/leases/{id}/increase-terms")
    public Map<String, Object> increaseTerms(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        return life.setIncrease(id, b.str("increaseKind", "NONE"), b.num("increaseValue", BigDecimal.ZERO), b.integer("increaseEvery", 12));
    }

    @PostMapping("/increases/{id}/approve")
    public Map<String, Object> approveIncrease(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        return life.approveIncrease(id, b.has("newRent") ? b.num("newRent") : null);
    }

    @PostMapping("/increases/{id}/skip")
    public Map<String, Object> skipIncrease(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        return life.skipIncrease(id);
    }

    @PostMapping("/leases/{id}/renew")
    public Map<String, Object> renew(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        return life.renew(id, b.has("endsOn") ? b.date("endsOn") : null, b.has("rent") ? b.num("rent") : null,
                b.has("serviceCharge") ? b.num("serviceCharge") : null);
    }

    /** What moving out would settle to, before doing it: {deductions: [{what, amount}]}. */
    @PostMapping("/leases/{id}/move-out/preview")
    public Map<String, Object> moveOutPreview(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        db.one("Lease", "SELECT id FROM leases WHERE id = :id", "id", id);
        return life.moveOutPreview(id, new Body(raw).list("deductions"));
    }

    /** The tenant moves out: {moveOutOn, deductions: [{what, amount}], note}. */
    @PostMapping("/leases/{id}/move-out")
    public Map<String, Object> moveOut(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        return life.moveOut(id, b.has("moveOutOn") ? b.date("moveOutOn") : null, b.list("deductions"), b.str("note", null));
    }

    /** Money the owner gave back to a tenant who moved out: {amount, method, reference, paidOn}. */
    @PostMapping("/leases/{id}/refund")
    public Map<String, Object> refund(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        return life.recordRefund(id, b.num("amount"), b.str("method", "MPESA"), b.str("reference", null), b.has("paidOn") ? b.date("paidOn") : null);
    }

    @GetMapping("/leases/{id}/history")
    public Map<String, Object> history(@PathVariable UUID id) {
        db.one("Lease", "SELECT id FROM leases WHERE id = :id", "id", id);
        return life.history(id);
    }

    /** What needs the owner: increases to approve, leases ending soon or past their end, and moved-out tenants to settle with. */
    @GetMapping("/attention")
    public Map<String, Object> attention() {
        Map<String, Object> out = life.attention(today());
        out.put("settle", db.list("""
                SELECT l.id AS lease_id, sp.name AS unit_name, c.name AS tenant_name, c.phone AS tenant_phone, l.moved_out_on, l.credit AS to_give_back,
                       (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN') AS still_owed
                FROM leases l JOIN spaces sp ON sp.id = l.space_id JOIN customers c ON c.id = l.customer_id
                WHERE l.status = 'ENDED' AND (l.credit > 0 OR EXISTS (SELECT 1 FROM orders o WHERE o.lease_id = l.id AND o.status = 'OPEN'))
                ORDER BY l.moved_out_on DESC NULLS LAST"""));
        return out;
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
