package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.lezitech.suite.notify.Mailer.esc;

/**
 * A lease over time (owner's decisions, 2026-11-13):
 * <ul>
 *   <li><b>Rent increases</b> written in the lease (a % or fixed amount every N months) are proposed 30 days ahead and
 *       wait for the owner: approve (the amount can be changed) or skip. Approved ones email the tenant a notice and
 *       apply to bills from the month they're due.</li>
 *   <li><b>Deposit</b> is billed like rent when the lease starts (or ticked as received before BizNez). It's money held
 *       for the tenant, not income: deposit bills (channel {@code DEPOSIT}) are left out of sales and tax.</li>
 *   <li><b>Move-out:</b> the deposit held, minus unpaid rent and the repair costs the owner lists. What's left to give
 *       back is shown; the owner records the refund after paying it. If the tenant still owes, that shows instead.</li>
 *   <li><b>Lease end:</b> the owner is alerted at 90, 60 and 30 days; if nothing is done, billing carries on monthly.
 *       Renewal is one step.</li>
 * </ul>
 * Callers run inside the workspace's transaction.
 */
@Service
public class LeaseLife {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final Db db;
    private final OrderService orders;
    private final LeaseLedger ledger;
    private final Mailer mailer;

    public LeaseLife(Db db, OrderService orders, LeaseLedger ledger, Mailer mailer) {
        this.db = db;
        this.orders = orders;
        this.ledger = ledger;
        this.mailer = mailer;
    }

    // ---------------- history ----------------

    public void event(UUID leaseId, String kind, String summary, Map<String, Object> detail) {
        TenantContext.Caller c = TenantContext.callerOrNull();
        db.exec("""
                INSERT INTO lease_events (tenant_id, lease_id, kind, summary, detail, user_id, user_name)
                VALUES (:t, :lease, :kind, :summary, cast(:detail as jsonb), :u, :name)""",
                "lease", leaseId, "kind", kind, "summary", summary, "detail", db.toJson(detail == null ? Map.of() : detail),
                "u", c == null ? null : c.userId(), "name", c == null || "system".equals(c.name()) ? "BizNez" : c.name());
    }

    public Map<String, Object> history(UUID leaseId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", db.list("SELECT kind, summary, user_name, created_at FROM lease_events WHERE lease_id = :l ORDER BY created_at DESC", "l", leaseId));
        out.put("increases", db.list("SELECT * FROM lease_increases WHERE lease_id = :l ORDER BY due_on DESC", "l", leaseId));
        return out;
    }

    // ---------------- deposit ----------------

    /** The deposit bill for a new lease, due the day it starts. */
    public UUID billDeposit(UUID leaseId) {
        Map<String, Object> l = db.one("Lease", "SELECT l.*, sp.name AS unit_name FROM leases l JOIN spaces sp ON sp.id = l.space_id WHERE l.id = :id", "id", leaseId);
        BigDecimal deposit = (BigDecimal) l.get("deposit");
        if (deposit.signum() <= 0) {
            return null;
        }
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("kind", "INVOICE");
        inv.put("channel", "DEPOSIT");
        inv.put("customerId", l.get("customerId"));
        inv.put("dueOn", l.get("startsOn").toString());
        inv.put("lines", List.of(Map.of("name", "Deposit · " + l.get("unitName"), "unitPrice", deposit, "qty", 1, "taxRate", 0)));
        UUID order = orders.createTrusted(new Body(inv));
        db.exec("UPDATE orders SET lease_id = :l WHERE id = :o", "l", leaseId, "o", order);
        ledger.useCredit(leaseId);
        return order;
    }

    /** What the landlord holds for the tenant now: received before BizNez, plus what's been paid on deposit bills. */
    public BigDecimal depositHeld(UUID leaseId) {
        Map<String, Object> l = db.one("Lease", "SELECT deposit_before, deposit_settled_on FROM leases WHERE id = :id", "id", leaseId);
        if (l.get("depositSettledOn") != null) {
            return BigDecimal.ZERO;
        }
        BigDecimal billed = db.value("""
                SELECT coalesce(sum(paid), 0) FROM orders WHERE lease_id = :l AND channel = 'DEPOSIT' AND status <> 'VOID'""", BigDecimal.class, "l", leaseId);
        return ((BigDecimal) l.get("depositBefore")).add(billed);
    }

    /** Unpaid part of the deposit bills (not rent arrears: at move-out it's simply cancelled). */
    private BigDecimal depositUnpaid(UUID leaseId) {
        return db.value("""
                SELECT coalesce(sum(total - paid), 0) FROM orders WHERE lease_id = :l AND channel = 'DEPOSIT' AND status = 'OPEN'""", BigDecimal.class, "l", leaseId);
    }

    // ---------------- rent increases ----------------

    /** Set (or clear) the increase written in the lease; the next one is worked out from the month the lease began. */
    public Map<String, Object> setIncrease(UUID leaseId, String kindRaw, BigDecimal value, int every) {
        String kind = kindRaw == null ? "NONE" : kindRaw.toUpperCase();
        if (!List.of("NONE", "PERCENT", "FIXED").contains(kind)) {
            throw ApiException.bad("Choose the increase: none, a percentage, or a fixed amount");
        }
        if (!"NONE".equals(kind) && (value == null || value.signum() <= 0)) {
            throw ApiException.bad("Enter how much the rent goes up");
        }
        if ("PERCENT".equals(kind) && value.compareTo(BigDecimal.valueOf(50)) > 0) {
            throw ApiException.bad("An increase over 50% looks like a mistake; check the number");
        }
        if (every < 1 || every > 60) {
            throw ApiException.bad("How often must be between 1 and 60 months");
        }
        Map<String, Object> l = db.one("Lease", "SELECT starts_on FROM leases WHERE id = :id FOR UPDATE", "id", leaseId);
        LocalDate next = null;
        if (!"NONE".equals(kind)) {
            LocalDate thisMonth = LeaseLedger.today().withDayOfMonth(1);
            next = LocalDate.parse(l.get("startsOn").toString()).withDayOfMonth(1).plusMonths(every);
            while (next.isBefore(thisMonth)) {
                next = next.plusMonths(every);
            }
        }
        db.exec("""
                UPDATE leases SET increase_kind = :k, increase_value = :v, increase_every = :e, next_increase_on = :n WHERE id = :id""",
                "id", leaseId, "k", kind, "v", "NONE".equals(kind) ? BigDecimal.ZERO : value, "e", every, "n", next);
        // A pending one that no longer matches the lease's terms is dropped; it's proposed again if still due.
        db.exec("DELETE FROM lease_increases WHERE lease_id = :id AND status = 'PENDING'", "id", leaseId);
        event(leaseId, "INCREASE_TERMS", "NONE".equals(kind) ? "Rent increases switched off"
                : "Rent goes up " + ("PERCENT".equals(kind) ? value.stripTrailingZeros().toPlainString() + "%" : "by " + value.toPlainString())
                  + " every " + every + " months; next on " + DAY.format(next), Map.of());
        return db.one("Lease", "SELECT * FROM leases WHERE id = :id", "id", leaseId);
    }

    /** Increases due within 30 days become proposals for the owner. Returns the new ones (for the owner's alert). */
    public List<Map<String, Object>> proposeIncreases(LocalDate today) {
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> l : db.list("""
                SELECT l.id, l.rent, l.increase_kind, l.increase_value, l.next_increase_on, sp.name AS unit_name, c.name AS tenant_name
                FROM leases l JOIN spaces sp ON sp.id = l.space_id JOIN customers c ON c.id = l.customer_id
                WHERE l.status = 'ACTIVE' AND l.increase_kind <> 'NONE' AND l.next_increase_on IS NOT NULL
                  AND l.next_increase_on <= cast(:limit as date)""", "limit", today.plusDays(30))) {
            BigDecimal rent = (BigDecimal) l.get("rent");
            BigDecimal v = (BigDecimal) l.get("increaseValue");
            BigDecimal newRent = "PERCENT".equals(l.get("increaseKind"))
                    ? rent.multiply(BigDecimal.valueOf(100).add(v)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    : rent.add(v).setScale(0, RoundingMode.HALF_UP);
            int n = db.exec("""
                    INSERT INTO lease_increases (tenant_id, lease_id, due_on, old_rent, new_rent) VALUES (:t, :l, cast(:due as date), :old, :new)
                    ON CONFLICT (lease_id, due_on) DO NOTHING""",
                    "l", l.get("id"), "due", l.get("nextIncreaseOn"), "old", rent, "new", newRent);
            if (n > 0) {
                Map<String, Object> m = new LinkedHashMap<>(l);
                m.put("newRent", newRent);
                created.add(m);
            }
        }
        return created;
    }

    /** The owner approves an increase (optionally with a different new rent): the tenant gets an email notice. */
    public Map<String, Object> approveIncrease(UUID increaseId, BigDecimal newRentOrNull) {
        Map<String, Object> inc = db.one("Increase", "SELECT * FROM lease_increases WHERE id = :id FOR UPDATE", "id", increaseId);
        if (!"PENDING".equals(inc.get("status"))) {
            throw ApiException.conflict("This increase was already decided");
        }
        BigDecimal newRent = newRentOrNull == null ? (BigDecimal) inc.get("newRent") : newRentOrNull.setScale(2, RoundingMode.HALF_UP);
        if (newRent.signum() <= 0) {
            throw ApiException.bad("Enter the new rent");
        }
        UUID leaseId = UUID.fromString(inc.get("leaseId").toString());
        LocalDate due = LocalDate.parse(inc.get("dueOn").toString());
        String notice = noticeTenant(leaseId, (BigDecimal) inc.get("oldRent"), newRent, due);
        db.exec("""
                UPDATE lease_increases SET status = 'APPROVED', new_rent = :r, decided_by = :u, decided_at = now(), notice_status = :n WHERE id = :id""",
                "id", increaseId, "r", newRent, "u", TenantContext.userId(), "n", notice);
        event(leaseId, "INCREASE_APPROVED", "Approved new rent " + newRent.stripTrailingZeros().toPlainString() + " from " + MONTH.format(due)
                + ("SENT".equals(notice) ? "; the tenant was emailed" : "NO_EMAIL".equals(notice) ? "; the tenant has no email, so tell them yourself" : ""), Map.of());
        // Due already (approved late)? It applies from the next bill.
        return db.one("Increase", "SELECT * FROM lease_increases WHERE id = :id", "id", increaseId);
    }

    public Map<String, Object> skipIncrease(UUID increaseId) {
        Map<String, Object> inc = db.one("Increase", "SELECT * FROM lease_increases WHERE id = :id FOR UPDATE", "id", increaseId);
        if (!"PENDING".equals(inc.get("status"))) {
            throw ApiException.conflict("This increase was already decided");
        }
        UUID leaseId = UUID.fromString(inc.get("leaseId").toString());
        db.exec("UPDATE lease_increases SET status = 'SKIPPED', decided_by = :u, decided_at = now() WHERE id = :id", "id", increaseId, "u", TenantContext.userId());
        db.exec("UPDATE leases SET next_increase_on = (next_increase_on + make_interval(months => increase_every))::date WHERE id = :l", "l", leaseId);
        event(leaseId, "INCREASE_SKIPPED", "Skipped the rent increase due " + MONTH.format(LocalDate.parse(inc.get("dueOn").toString())), Map.of());
        return db.one("Increase", "SELECT * FROM lease_increases WHERE id = :id", "id", increaseId);
    }

    /** Before a lease's bill for the month starting {@code monthStart}: put approved increases due by then into its rent. */
    public void applyDueIncreases(UUID leaseId, LocalDate monthStart) {
        for (Map<String, Object> inc : db.list("""
                SELECT * FROM lease_increases WHERE lease_id = :l AND status = 'APPROVED' AND due_on <= cast(:m as date) ORDER BY due_on FOR UPDATE""",
                "l", leaseId, "m", monthStart)) {
            db.exec("UPDATE leases SET rent = :r, next_increase_on = CASE WHEN increase_kind = 'NONE' THEN NULL ELSE (cast(:due as date) + make_interval(months => increase_every))::date END WHERE id = :l",
                    "l", leaseId, "r", inc.get("newRent"), "due", LocalDate.parse(inc.get("dueOn").toString()));
            db.exec("UPDATE lease_increases SET status = 'APPLIED', applied_at = now() WHERE id = :id", "id", inc.get("id"));
            event(leaseId, "INCREASE", "Rent went from " + ((BigDecimal) inc.get("oldRent")).stripTrailingZeros().toPlainString() + " to "
                    + ((BigDecimal) inc.get("newRent")).stripTrailingZeros().toPlainString(), Map.of());
        }
    }

    /** Email the tenant the new rent. Returns SENT, NO_EMAIL or FAILED. */
    private String noticeTenant(UUID leaseId, BigDecimal oldRent, BigDecimal newRent, LocalDate due) {
        Map<String, Object> l = db.one("Lease", """
                SELECT c.name, c.email, sp.name AS unit, t.name AS business, t.phone AS business_phone, t.brand_color
                FROM leases l JOIN customers c ON c.id = l.customer_id JOIN spaces sp ON sp.id = l.space_id JOIN tenants t ON t.id = l.tenant_id
                WHERE l.id = :id""", "id", leaseId);
        String to = (String) l.get("email");
        if (to == null || to.isBlank()) {
            return "NO_EMAIL";
        }
        String first = ((String) l.get("name")).trim().split("\\s+")[0];
        String business = (String) l.get("business");
        String brand = l.get("brandColor") == null ? "#101415" : (String) l.get("brandColor");
        String msg = "As set out in your lease, the rent for " + l.get("unit") + " changes from " + kes(oldRent) + " to " + kes(newRent)
                + " a month, starting with the bill for " + MONTH.format(due) + ".";
        String text = "Hi " + first + ",\n\n" + msg + "\n\nIf you have any questions, please contact us"
                + (l.get("businessPhone") == null ? "." : " on " + l.get("businessPhone") + ".") + "\n\n" + business;
        String html = "<div style=\"background:#f3f4ef;padding:24px 12px;font-family:Arial,Helvetica,sans-serif;color:#101415\">"
                + "<div style=\"max-width:560px;margin:0 auto;background:#fff;border-radius:16px;overflow:hidden;border:1px solid #e4e6df\">"
                + "<div style=\"height:6px;background:" + esc(brand) + "\"></div><div style=\"padding:22px 24px;font-size:15px;line-height:1.55\">"
                + "<div style=\"font-size:18px;font-weight:bold;margin-bottom:14px\">" + esc(business) + "</div>"
                + "<p>Hi " + esc(first) + ",</p><p>" + esc(msg) + "</p><p style=\"color:#6b7280\">If you have any questions, please contact us"
                + (l.get("businessPhone") == null ? "." : " on " + esc((String) l.get("businessPhone")) + ".") + "</p></div></div></div>";
        String err = mailer.trySend(to.trim(), (String) l.get("name"), "Notice of rent change for " + l.get("unit") + " from " + MONTH.format(due), text, html);
        return err == null ? "SENT" : "FAILED";
    }

    // ---------------- renewal and move-out ----------------

    public Map<String, Object> renew(UUID leaseId, LocalDate endsOn, BigDecimal rent, BigDecimal serviceCharge) {
        Map<String, Object> l = db.one("Lease", "SELECT * FROM leases WHERE id = :id FOR UPDATE", "id", leaseId);
        if (!"ACTIVE".equals(l.get("status"))) {
            throw ApiException.conflict("This tenant has moved out");
        }
        if (endsOn != null && !endsOn.isAfter(LeaseLedger.today())) {
            throw ApiException.bad("The new end date must be in the future");
        }
        BigDecimal newRent = rent == null ? (BigDecimal) l.get("rent") : rent;
        BigDecimal newSvc = serviceCharge == null ? (BigDecimal) l.get("serviceCharge") : serviceCharge;
        if (newRent.signum() < 0 || newSvc.signum() < 0) {
            throw ApiException.bad("Rent can't be negative");
        }
        db.exec("UPDATE leases SET ends_on = :e, rent = :r, service_charge = :s WHERE id = :id", "id", leaseId, "e", endsOn, "r", newRent, "s", newSvc);
        event(leaseId, "RENEWED", "Renewed " + (endsOn == null ? "with no end date" : "until " + DAY.format(endsOn)) + " at rent " + newRent.stripTrailingZeros().toPlainString()
                + (newSvc.signum() > 0 ? " + " + newSvc.stripTrailingZeros().toPlainString() + " service charge" : "") + ", from the next bill", Map.of());
        return db.one("Lease", "SELECT * FROM leases WHERE id = :id", "id", leaseId);
    }

    /** What moving out would settle to: deposit held, unpaid rent (deposit bills aside), the deductions, and the result. */
    public Map<String, Object> moveOutPreview(UUID leaseId, List<Map<String, Object>> deductions) {
        BigDecimal held = depositHeld(leaseId);
        BigDecimal owing = ledger.balance(leaseId).subtract(depositUnpaid(leaseId));
        BigDecimal deducted = BigDecimal.ZERO;
        for (Map<String, Object> d : cleanDeductions(deductions)) {
            deducted = deducted.add((BigDecimal) d.get("amount"));
        }
        BigDecimal result = held.subtract(owing).subtract(deducted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("depositHeld", held);
        out.put("unpaid", owing.max(BigDecimal.ZERO));
        out.put("credit", owing.signum() < 0 ? owing.negate() : BigDecimal.ZERO);
        out.put("deductions", deducted);
        out.put("toGiveBack", result.max(BigDecimal.ZERO));
        out.put("stillOwed", result.signum() < 0 ? result.negate() : BigDecimal.ZERO);
        return out;
    }

    /**
     * The tenant moves out: deductions are billed, the deposit is put on their account and used on what they owe, the
     * lease ends and billing stops. Returns the settlement: what to give back, or what they still owe.
     */
    public Map<String, Object> moveOut(UUID leaseId, LocalDate on, List<Map<String, Object>> deductionsRaw, String note) {
        Map<String, Object> l = db.one("Lease", "SELECT l.*, sp.name AS unit_name FROM leases l JOIN spaces sp ON sp.id = l.space_id WHERE l.id = :id FOR UPDATE", "id", leaseId);
        if (!"ACTIVE".equals(l.get("status"))) {
            throw ApiException.conflict("This tenant has already moved out");
        }
        LocalDate day = on == null ? LeaseLedger.today() : on;
        if (day.isAfter(LeaseLedger.today().plusDays(60))) {
            throw ApiException.bad("Record the move-out on or near the day it happens");
        }
        List<Map<String, Object>> deductions = cleanDeductions(deductionsRaw);
        BigDecimal before = db.value("SELECT deposit_before FROM leases WHERE id = :id", BigDecimal.class, "id", leaseId);

        // 1. Repairs and other deductions become a bill on the lease.
        if (!deductions.isEmpty()) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> d : deductions) {
                lines.add(Map.of("name", d.get("what"), "unitPrice", d.get("amount"), "qty", 1, "taxRate", 0));
            }
            Map<String, Object> inv = new LinkedHashMap<>();
            inv.put("kind", "INVOICE");
            inv.put("channel", "MOVEOUT");
            inv.put("customerId", l.get("customerId"));
            inv.put("dueOn", day.toString());
            inv.put("lines", lines);
            UUID order = orders.createTrusted(new Body(inv));
            db.exec("UPDATE orders SET lease_id = :l WHERE id = :o", "l", leaseId, "o", order);
        }
        // 2. Deposit bills: what was paid on them goes to the tenant's account; anything unpaid is cancelled.
        for (Map<String, Object> d : db.list("SELECT id, paid FROM orders WHERE lease_id = :l AND channel = 'DEPOSIT' AND status <> 'VOID' FOR UPDATE", "l", leaseId)) {
            BigDecimal paid = (BigDecimal) d.get("paid");
            if (paid.signum() > 0) {
                db.exec("""
                        INSERT INTO payments (tenant_id, order_id, method, amount, reference, user_id)
                        VALUES (:t, :o, 'ADVANCE', :amount, 'Deposit put on account at move-out', :u)""", "o", d.get("id"), "amount", paid.negate());
                db.exec("UPDATE leases SET credit = credit + :c WHERE id = :id", "id", leaseId, "c", paid);
            }
            db.exec("UPDATE orders SET status = 'VOID', paid = 0, closed_at = now(), note = concat_ws(' · ', note, 'Deposit settled at move-out') WHERE id = :id",
                    "id", d.get("id"));
        }
        // 3. A deposit received before BizNez is put on their account like a payment.
        if (before.signum() > 0) {
            ledger.receive(leaseId, before, "DEPOSIT", null, "MOVEOUT", "Deposit held before BizNez", day);
        }
        // 4. Credit (the deposit and anything paid ahead) pays what they owe, oldest first.
        ledger.useCredit(leaseId);
        db.exec("""
                UPDATE leases SET status = 'ENDED', moved_out_on = :d, ends_on = CASE WHEN ends_on IS NULL OR ends_on > :d THEN :d ELSE ends_on END,
                                  deposit_settled_on = :d WHERE id = :id""", "id", leaseId, "d", day);
        BigDecimal balance = ledger.balance(leaseId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toGiveBack", balance.signum() < 0 ? balance.negate() : BigDecimal.ZERO);
        out.put("stillOwed", balance.max(BigDecimal.ZERO));
        out.put("balance", balance);
        event(leaseId, "MOVED_OUT", "Moved out on " + DAY.format(day)
                + (balance.signum() < 0 ? "; " + kes(balance.negate()) + " to give back" : balance.signum() > 0 ? "; still owes " + kes(balance) : "; all settled")
                + (note == null || note.isBlank() ? "" : " · " + note.trim()), Map.of("deductions", deductions));
        return out;
    }

    /** The owner gave money back to a tenant who moved out (they pay it themselves, then record it here). */
    public Map<String, Object> recordRefund(UUID leaseId, BigDecimal amount, String method, String reference, LocalDate on) {
        Map<String, Object> r = ledger.refund(leaseId, amount, method, reference, on, "Deposit given back");
        event(leaseId, "DEPOSIT_REFUND", "Gave back " + kes(amount) + " by " + LeaseLedger.methodName(method == null ? null : method.toUpperCase())
                + (reference == null || reference.isBlank() ? "" : " (" + reference.trim().toUpperCase() + ")"), Map.of());
        return r;
    }

    private static List<Map<String, Object>> cleanDeductions(List<Map<String, Object>> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Map<String, Object> d : raw) {
            Body b = new Body(d);
            String what = b.str("what", "").trim();
            BigDecimal amount = b.num("amount", BigDecimal.ZERO);
            if (what.isEmpty() && amount.signum() == 0) {
                continue;
            }
            if (what.isEmpty() || amount.signum() <= 0) {
                throw ApiException.bad("Each deduction needs what it's for and an amount");
            }
            if (what.length() > 120) {
                throw ApiException.bad("Keep each deduction's description short");
            }
            out.add(Map.of("what", what, "amount", amount.setScale(2, RoundingMode.HALF_UP)));
        }
        return out;
    }

    // ---------------- what needs the owner ----------------

    /** For the owner's screen: increases to approve, leases ending within 90 days or past their end date. */
    public Map<String, Object> attention(LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("increases", db.list("""
                SELECT i.id, i.lease_id, i.due_on, i.old_rent, i.new_rent, sp.name AS unit_name, c.name AS tenant_name, c.email IS NOT NULL AS has_email
                FROM lease_increases i JOIN leases l ON l.id = i.lease_id JOIN spaces sp ON sp.id = l.space_id JOIN customers c ON c.id = l.customer_id
                WHERE i.status = 'PENDING' AND l.status = 'ACTIVE' ORDER BY i.due_on"""));
        out.put("ending", db.list("""
                SELECT l.id AS lease_id, l.ends_on, l.rent, sp.name AS unit_name, c.name AS tenant_name, (l.ends_on - cast(:today as date)) AS days_left
                FROM leases l JOIN spaces sp ON sp.id = l.space_id JOIN customers c ON c.id = l.customer_id
                WHERE l.status = 'ACTIVE' AND l.ends_on IS NOT NULL AND l.ends_on <= cast(:today as date) + 90
                ORDER BY l.ends_on""", "today", today));
        return out;
    }

    /** Lease-end alerts for the owner due today (90, 60, 30 days ahead), each claimed once. */
    public List<Map<String, Object>> endAlertsDue(LocalDate today) {
        List<Map<String, Object>> due = new ArrayList<>();
        for (Map<String, Object> l : db.list("""
                SELECT l.id, l.ends_on, sp.name AS unit_name, c.name AS tenant_name FROM leases l
                JOIN spaces sp ON sp.id = l.space_id JOIN customers c ON c.id = l.customer_id
                WHERE l.status = 'ACTIVE' AND l.ends_on IS NOT NULL AND l.ends_on >= cast(:today as date) AND l.ends_on <= cast(:today as date) + 90""",
                "today", today)) {
            LocalDate ends = LocalDate.parse(l.get("endsOn").toString());
            long left = ChronoUnit.DAYS.between(today, ends);
            int step = left <= 30 ? 30 : left <= 60 ? 60 : 90;
            String kind = "END_" + step + ":" + ends;
            if (db.exec("INSERT INTO lease_alerts (tenant_id, lease_id, kind, status) VALUES (:t, :l, :k, 'SENT') ON CONFLICT (lease_id, kind) DO NOTHING",
                    "l", l.get("id"), "k", kind) > 0) {
                Map<String, Object> m = new LinkedHashMap<>(l);
                m.put("daysLeft", left);
                due.add(m);
            }
        }
        return due;
    }

    static String kes(BigDecimal v) {
        return "KES " + NumberFormat.getIntegerInstance(Locale.ENGLISH).format(v.setScale(0, RoundingMode.HALF_UP));
    }
}
