package com.lezitech.suite.property;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A lease's money: what the tenant is billed (rent invoices and penalties) against what they've paid.
 *
 * <ul>
 *   <li>Money received (M-Pesa prompt, paybill, or recorded by hand) is one {@code lease_receipts} row, spread over the
 *       lease's open invoices as ordinary payments, the invoice due first; anything left is kept as {@code leases.credit}.</li>
 *   <li>Credit is used up by the next invoices as payments of method {@code ADVANCE} (money that came in earlier, so it
 *       is not counted twice on the statement).</li>
 *   <li>A late invoice gets at most one penalty (its own invoice, {@code orders.penalty_for}), only when the lease has
 *       penalties switched on; the owner can lift it, which gives back anything already paid on it as credit.</li>
 * </ul>
 * Callers run inside the workspace's transaction.
 */
@Service
public class LeaseLedger {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final Set<String> METHODS = Set.of("MPESA", "CASH", "BANK", "CHEQUE", "WHT");

    private final Db db;
    private final OrderService orders;
    private final ActivityLog activity;

    public LeaseLedger(Db db, OrderService orders, ActivityLog activity) {
        this.db = db;
        this.orders = orders;
        this.activity = activity;
    }

    public static LocalDate today() {
        return LocalDate.now(NAIROBI);
    }

    /** "G-12 " → "G12": what a tenant types as the M-Pesa account number. */
    public static String normalizeCode(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    /** SQL for a unit's account number: the one the owner set, or the unit's name tidied up. */
    static final String CODE_SQL = "upper(regexp_replace(coalesce(nullif(sp.account_code, ''), sp.name), '[^A-Za-z0-9]', '', 'g'))";

    /** The active lease whose unit's account number is {@code accountNumber}, if exactly one matches. */
    public Optional<UUID> leaseForAccount(String accountNumber) {
        String code = normalizeCode(accountNumber);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = db.list("SELECT l.id FROM leases l JOIN spaces sp ON sp.id = l.space_id WHERE l.status = 'ACTIVE' AND "
                + CODE_SQL + " = :code", "code", code);
        return rows.size() == 1 ? Optional.of(UUID.fromString(rows.get(0).get("id").toString())) : Optional.empty();
    }

    // ---------------- money in ----------------

    /**
     * Money from the tenant: onto the lease's open invoices, the one due first first; what's left becomes credit.
     * An M-Pesa code can be recorded once only.
     */
    public Map<String, Object> receive(UUID leaseId, BigDecimal amount, String method, String reference, String source, String note,
                                       LocalDate receivedOn) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.bad("Enter the amount received");
        }
        String m = method == null ? "" : method.toUpperCase();
        // At move-out the deposit the landlord holds is put on the tenant's account like a payment ("DEPOSIT").
        if (!METHODS.contains(m) && !("DEPOSIT".equals(m) && "MOVEOUT".equals(source))) {
            throw ApiException.bad("Choose how it was paid: M-Pesa, cash, bank or cheque");
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim().toUpperCase();
        Map<String, Object> lease = db.one("Lease", "SELECT * FROM leases WHERE id = :id FOR UPDATE", "id", leaseId);
        if ("MPESA".equals(m) && ref != null && db.find("SELECT id FROM lease_receipts WHERE method = 'MPESA' AND reference = :ref", "ref", ref).isPresent()) {
            throw ApiException.conflict("M-Pesa code " + ref + " is already recorded");
        }
        if ("WHT".equals(m)) {
            if (ref == null) {
                throw ApiException.bad("Enter the withholding tax certificate number");
            }
            if (db.find("SELECT id FROM lease_receipts WHERE method = 'WHT' AND reference = :ref", "ref", ref).isPresent()) {
                throw ApiException.conflict("Certificate " + ref + " is already recorded");
            }
        }
        BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
        UUID receipt = db.insert("""
                INSERT INTO lease_receipts (tenant_id, lease_id, amount, method, reference, source, note, user_id, received_on)
                VALUES (:t, :lease, :amount, :method, :ref, :source, :note, :u, :on) RETURNING id""",
                "lease", leaseId, "amount", amt, "method", m, "ref", ref, "source", source, "note", note,
                "u", TenantContext.userId(), "on", receivedOn == null ? today() : receivedOn);
        BigDecimal left = amt;
        for (Map<String, Object> inv : openInvoices(leaseId)) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal part = left.min(due(inv));
            if (part.signum() <= 0) {
                continue;
            }
            UUID orderId = UUID.fromString(inv.get("id").toString());
            payTagged(orderId, m, part, ref == null ? "Rent payment" : ref, receipt);
            left = left.subtract(part);
        }
        db.exec("UPDATE lease_receipts SET to_invoices = :inv, to_credit = :credit WHERE id = :id",
                "id", receipt, "inv", amt.subtract(left), "credit", left);
        if (left.signum() > 0) {
            db.exec("UPDATE leases SET credit = credit + :c WHERE id = :id", "id", leaseId, "c", left);
        }
        Map<String, Object> out = new LinkedHashMap<>(db.one("Receipt", "SELECT * FROM lease_receipts WHERE id = :id", "id", receipt));
        out.put("balance", balance(leaseId));
        out.put("credit", db.value("SELECT credit FROM leases WHERE id = :id", BigDecimal.class, "id", leaseId));
        out.put("tenantName", db.value("SELECT name FROM customers WHERE id = :c", String.class, "c", lease.get("customerId")));
        return out;
    }

    /**
     * Money given back to the tenant (e.g. the deposit after move-out): it comes off their credit, and shows on the
     * statement as a refund. Refused if it's more than they're owed.
     */
    public Map<String, Object> refund(UUID leaseId, BigDecimal amount, String method, String reference, LocalDate on, String note) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.bad("Enter the amount given back");
        }
        String m = method == null ? "" : method.toUpperCase();
        if (!METHODS.contains(m)) {
            throw ApiException.bad("Choose how it was given back: M-Pesa, cash, bank or cheque");
        }
        BigDecimal credit = db.value("SELECT credit FROM leases WHERE id = :id FOR UPDATE", BigDecimal.class, "id", leaseId);
        if (credit == null) {
            throw ApiException.notFound("Lease");
        }
        BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
        if (amt.compareTo(credit) > 0) {
            throw ApiException.bad("That's more than the " + credit.toPlainString() + " owed to the tenant");
        }
        db.insert("""
                INSERT INTO lease_receipts (tenant_id, lease_id, amount, to_credit, method, reference, source, note, user_id, received_on)
                VALUES (:t, :lease, :amount, :amount, :method, :ref, 'REFUND', :note, :u, :on) RETURNING id""",
                "lease", leaseId, "amount", amt.negate(), "method", m, "ref", reference == null || reference.isBlank() ? null : reference.trim().toUpperCase(),
                "note", note, "u", TenantContext.userId(), "on", on == null ? today() : on);
        db.exec("UPDATE leases SET credit = credit - :a WHERE id = :id", "id", leaseId, "a", amt);
        return Map.of("refunded", amt, "credit", credit.subtract(amt), "balance", balance(leaseId));
    }

    /** Use the lease's credit on its open invoices (after new invoices or penalties are raised). */
    public void useCredit(UUID leaseId) {
        BigDecimal credit = db.value("SELECT credit FROM leases WHERE id = :id FOR UPDATE", BigDecimal.class, "id", leaseId);
        if (credit == null || credit.signum() <= 0) {
            return;
        }
        BigDecimal left = credit;
        for (Map<String, Object> inv : openInvoices(leaseId)) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal part = left.min(due(inv));
            if (part.signum() <= 0) {
                continue;
            }
            payTagged(UUID.fromString(inv.get("id").toString()), "ADVANCE", part, "Paid ahead", null);
            left = left.subtract(part);
        }
        db.exec("UPDATE leases SET credit = :c WHERE id = :id", "id", leaseId, "c", left);
    }

    /** One payment on an invoice through the normal sale path (so it closes when paid), tagged with the receipt it came from. */
    private void payTagged(UUID orderId, String method, BigDecimal amount, String reference, UUID receipt) {
        String before = db.list("SELECT id FROM payments WHERE order_id = :o", "o", orderId).stream()
                .map(r -> r.get("id").toString()).collect(Collectors.joining(","));
        orders.pay(orderId, new Body(Map.of("payments", List.of(Map.of("method", method, "amount", amount, "reference", reference)))));
        if (receipt != null) {
            db.exec("""
                    UPDATE payments SET lease_receipt_id = :r
                    WHERE order_id = :o AND lease_receipt_id IS NULL AND NOT (id::text = ANY (string_to_array(cast(:before as text), ',')))""",
                    "r", receipt, "o", orderId, "before", before);
        }
    }

    private List<Map<String, Object>> openInvoices(UUID leaseId) {
        return db.list("""
                SELECT id, number, total, paid, due_on FROM orders
                WHERE lease_id = :lease AND status = 'OPEN' AND kind = 'INVOICE'
                ORDER BY due_on NULLS LAST, number FOR UPDATE""", "lease", leaseId);
    }

    private static BigDecimal due(Map<String, Object> inv) {
        return ((BigDecimal) inv.get("total")).subtract((BigDecimal) inv.get("paid"));
    }

    /** What the tenant owes now: everything billed minus everything they've paid (negative = in credit). */
    public BigDecimal balance(UUID leaseId) {
        BigDecimal billed = db.value("SELECT coalesce(sum(total), 0) FROM orders WHERE lease_id = :l AND status <> 'VOID' AND kind = 'INVOICE'",
                BigDecimal.class, "l", leaseId);
        return billed.subtract(paidIn(leaseId, null));
    }

    /** Money that really came in from the tenant (before {@code until}, if given): receipts, plus older payments made straight on an invoice. */
    private BigDecimal paidIn(UUID leaseId, LocalDate until) {
        BigDecimal receipts = db.value("SELECT coalesce(sum(amount), 0) FROM lease_receipts WHERE lease_id = :l AND (cast(:until as date) IS NULL OR received_on < cast(:until as date))",
                BigDecimal.class, "l", leaseId, "until", until);
        BigDecimal direct = db.value("""
                SELECT coalesce(sum(p.amount), 0) FROM payments p JOIN orders o ON o.id = p.order_id
                WHERE o.lease_id = :l AND p.lease_receipt_id IS NULL AND p.method <> 'ADVANCE'
                  AND (cast(:until as date) IS NULL OR (p.created_at AT TIME ZONE 'Africa/Nairobi')::date < cast(:until as date))""",
                BigDecimal.class, "l", leaseId, "until", until);
        return receipts.add(direct);
    }

    // ---------------- penalties ----------------

    /** One penalty per late invoice, for leases that have penalties switched on. Returns how many were raised. */
    public int raisePenalties(LocalDate today) {
        int raised = 0;
        for (Map<String, Object> o : db.list("""
                SELECT o.id, o.number, o.total, o.paid, o.customer_id, l.id AS lease_id, l.penalty_kind, l.penalty_value
                FROM orders o JOIN leases l ON l.id = o.lease_id
                WHERE l.status = 'ACTIVE' AND l.penalty_kind <> 'NONE' AND l.penalty_value > 0
                  AND o.status = 'OPEN' AND o.kind = 'INVOICE' AND o.channel = 'RENT' AND o.penalty_for IS NULL AND o.due_on IS NOT NULL
                  AND o.due_on + l.penalty_grace_days < cast(:today as date)
                  AND NOT EXISTS (SELECT 1 FROM orders p WHERE p.penalty_for = o.id)""", "today", today)) {
            BigDecimal unpaid = due(o);
            BigDecimal value = (BigDecimal) o.get("penaltyValue");
            BigDecimal amount = "PERCENT".equals(o.get("penaltyKind"))
                    ? unpaid.multiply(value).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    : value.setScale(0, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) {
                continue;
            }
            Map<String, Object> inv = new LinkedHashMap<>();
            inv.put("kind", "INVOICE");
            inv.put("channel", "PENALTY");
            inv.put("customerId", o.get("customerId"));
            inv.put("dueOn", today.toString());
            inv.put("lines", List.of(Map.of("name", "Late payment penalty · invoice #" + o.get("number"), "unitPrice", amount, "qty", 1, "taxRate", 0)));
            UUID penalty = orders.createTrusted(new Body(inv));
            UUID leaseId = UUID.fromString(o.get("leaseId").toString());
            db.exec("UPDATE orders SET lease_id = :lease, penalty_for = :late WHERE id = :id", "lease", leaseId, "late", o.get("id"), "id", penalty);
            useCredit(leaseId);
            raised++;
        }
        return raised;
    }

    /** The owner lifts (waives) a penalty: it's cancelled, and anything already paid on it goes to the tenant's credit. */
    public Map<String, Object> liftPenalty(UUID penaltyId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.bad("Say why the penalty is lifted");
        }
        Map<String, Object> p = db.one("Penalty", "SELECT * FROM orders WHERE id = :id AND penalty_for IS NOT NULL FOR UPDATE", "id", penaltyId);
        if ("VOID".equals(p.get("status"))) {
            throw ApiException.conflict("This penalty was already lifted");
        }
        UUID leaseId = UUID.fromString(p.get("leaseId").toString());
        BigDecimal paid = (BigDecimal) p.get("paid");
        if (paid.signum() > 0) {
            db.exec("""
                    INSERT INTO payments (tenant_id, order_id, method, amount, reference, user_id)
                    VALUES (:t, :o, 'ADVANCE', :amount, 'Penalty lifted: back to credit', :u)""", "o", penaltyId, "amount", paid.negate());
            db.exec("UPDATE leases SET credit = credit + :c WHERE id = :id", "id", leaseId, "c", paid);
        }
        db.exec("UPDATE orders SET status = 'VOID', paid = 0, closed_at = now(), note = concat_ws(' · ', note, :why) WHERE id = :id",
                "id", penaltyId, "why", "Penalty lifted: " + reason.trim());
        activity.record("PENALTY_LIFTED", ActivityLog.NOTICE, "Lifted the late-payment penalty #" + p.get("number") + " of "
                        + ((BigDecimal) p.get("total")).toPlainString() + ": " + reason.trim(),
                penaltyId, null, (BigDecimal) p.get("total"), null, Map.of("reason", reason.trim()));
        useCredit(leaseId);
        return Map.of("lifted", true, "balance", balance(leaseId));
    }

    // ---------------- statement ----------------

    /** Everything billed and paid between two dates, with the running balance and what's overdue by age. */
    public Map<String, Object> statement(UUID leaseId, LocalDate from, LocalDate to) {
        Map<String, Object> lease = db.one("Lease", """
                SELECT l.*, sp.name AS unit_name, sp.floor, """ + CODE_SQL + """
                 AS account_code, c.name AS tenant_name, c.phone AS tenant_phone, c.email AS tenant_email, c.company
                FROM leases l JOIN spaces sp ON sp.id = l.space_id JOIN customers c ON c.id = l.customer_id WHERE l.id = :id""", "id", leaseId);
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.minusMonths(6).withDayOfMonth(1) : from;
        if (start.isAfter(end)) {
            throw ApiException.bad("The start date is after the end date");
        }
        BigDecimal billedBefore = db.value("""
                SELECT coalesce(sum(total), 0) FROM orders WHERE lease_id = :l AND status <> 'VOID' AND kind = 'INVOICE'
                  AND (created_at AT TIME ZONE 'Africa/Nairobi')::date < cast(:from as date)""", BigDecimal.class, "l", leaseId, "from", start);
        BigDecimal opening = billedBefore.subtract(paidIn(leaseId, start));

        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> o : db.list("""
                SELECT o.id, o.number, o.total, o.paid, o.status, o.due_on, o.period, o.channel,
                       (o.created_at AT TIME ZONE 'Africa/Nairobi')::date AS day, o.created_at AS at,
                       (SELECT string_agg(name, ', ' ORDER BY name) FROM order_lines WHERE order_id = o.id) AS what
                FROM orders o WHERE o.lease_id = :l AND o.status <> 'VOID' AND o.kind = 'INVOICE'
                  AND (o.created_at AT TIME ZONE 'Africa/Nairobi')::date BETWEEN cast(:from as date) AND cast(:to as date)""",
                "l", leaseId, "from", start, "to", end)) {
            lines.add(line(o.get("day"), "PENALTY".equals(o.get("channel")) ? "PENALTY" : "DEPOSIT".equals(o.get("channel")) ? "DEPOSIT" : "INVOICE",
                    "Invoice #" + o.get("number") + " · " + o.get("what"), (BigDecimal) o.get("total"), BigDecimal.ZERO, o.get("id"), o.get("at")));
        }
        for (Map<String, Object> r : db.list("""
                SELECT id, received_on, amount, method, reference, to_credit, created_at AS at FROM lease_receipts
                WHERE lease_id = :l AND received_on BETWEEN cast(:from as date) AND cast(:to as date)""", "l", leaseId, "from", start, "to", end)) {
            BigDecimal amt = (BigDecimal) r.get("amount");
            String how = methodName((String) r.get("method")) + (r.get("reference") == null ? "" : " " + r.get("reference"));
            if (amt.signum() < 0) {
                lines.add(line(r.get("receivedOn"), "REFUND", "Given back to you · " + how, amt.negate(), BigDecimal.ZERO, r.get("id"), r.get("at")));
            } else {
                lines.add(line(r.get("receivedOn"), "PAYMENT", "DEPOSIT".equals(r.get("method")) ? "Your deposit, put on your account"
                        : "Payment · " + how, BigDecimal.ZERO, amt, r.get("id"), r.get("at")));
            }
        }
        for (Map<String, Object> p : db.list("""
                SELECT p.id, (p.created_at AT TIME ZONE 'Africa/Nairobi')::date AS day, p.created_at AS at, p.amount, p.method, p.reference
                FROM payments p JOIN orders o ON o.id = p.order_id
                WHERE o.lease_id = :l AND p.lease_receipt_id IS NULL AND p.method <> 'ADVANCE'
                  AND (p.created_at AT TIME ZONE 'Africa/Nairobi')::date BETWEEN cast(:from as date) AND cast(:to as date)""",
                "l", leaseId, "from", start, "to", end)) {
            BigDecimal amt = (BigDecimal) p.get("amount");
            lines.add(line(p.get("day"), amt.signum() < 0 ? "REFUND" : "PAYMENT", (amt.signum() < 0 ? "Refund · " : "Payment · ")
                    + methodName((String) p.get("method")) + (p.get("reference") == null ? "" : " " + p.get("reference")),
                    amt.signum() < 0 ? amt.negate() : BigDecimal.ZERO, amt.signum() < 0 ? BigDecimal.ZERO : amt, p.get("id"), p.get("at")));
        }
        // By day, then in the order things happened that day.
        lines.sort(Comparator.comparing((Map<String, Object> l) -> l.get("date").toString())
                .thenComparing(l -> (java.time.Instant) l.get("at")));
        lines.forEach(l -> l.remove("at"));
        BigDecimal running = opening;
        BigDecimal billed = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            billed = billed.add((BigDecimal) l.get("debit"));
            paid = paid.add((BigDecimal) l.get("credit"));
            running = running.add((BigDecimal) l.get("debit")).subtract((BigDecimal) l.get("credit"));
            l.put("balance", running);
        }

        // What's unpaid now, by how late it is.
        Map<String, BigDecimal> ageing = new LinkedHashMap<>();
        for (String k : List.of("notDue", "days1to30", "days31to60", "days61to90", "over90")) {
            ageing.put(k, BigDecimal.ZERO);
        }
        LocalDate now = today();
        List<Map<String, Object>> open = new ArrayList<>();
        for (Map<String, Object> o : db.list("""
                SELECT id, number, total, paid, due_on, channel FROM orders
                WHERE lease_id = :l AND status = 'OPEN' AND kind = 'INVOICE' ORDER BY due_on NULLS LAST, number""", "l", leaseId)) {
            BigDecimal unpaid = due(o);
            LocalDate dueOn = o.get("dueOn") == null ? now : LocalDate.parse(o.get("dueOn").toString());
            long late = ChronoUnit.DAYS.between(dueOn, now);
            String bucket = late <= 0 ? "notDue" : late <= 30 ? "days1to30" : late <= 60 ? "days31to60" : late <= 90 ? "days61to90" : "over90";
            ageing.merge(bucket, unpaid, BigDecimal::add);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", o.get("id"));
            row.put("number", o.get("number"));
            row.put("dueOn", dueOn.toString());
            row.put("unpaid", unpaid);
            row.put("daysLate", Math.max(0, late));
            row.put("penalty", "PENALTY".equals(o.get("channel")));
            open.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> l = new LinkedHashMap<>();
        for (String k : List.of("id", "unitName", "floor", "accountCode", "tenantName", "tenantPhone", "tenantEmail", "company", "rent",
                "serviceCharge", "deposit", "dueDay", "startsOn", "endsOn", "status", "credit", "penaltyKind", "penaltyValue", "penaltyGraceDays")) {
            l.put(k, lease.get(k));
        }
        out.put("lease", l);
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("opening", opening);
        out.put("billed", billed);
        out.put("paid", paid);
        out.put("closing", running);
        out.put("lines", lines);
        out.put("balance", balance(leaseId));
        out.put("open", open);
        out.put("ageing", ageing);
        return out;
    }

    private static Map<String, Object> line(Object date, String kind, String what, BigDecimal debit, BigDecimal credit, Object ref, Object at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("kind", kind);
        m.put("what", what);
        m.put("debit", debit);
        m.put("credit", credit);
        m.put("ref", ref);
        m.put("at", at instanceof String iso ? java.time.Instant.parse(iso) : java.time.Instant.EPOCH);
        return m;
    }

    static String methodName(String m) {
        return switch (m == null ? "" : m) {
            case "MPESA" -> "M-Pesa";
            case "CASH" -> "Cash";
            case "BANK" -> "Bank";
            case "CHEQUE" -> "Cheque";
            case "CARD" -> "Card";
            case "DEPOSIT" -> "Deposit";
            case "WHT" -> "Withholding tax certificate";
            default -> m;
        };
    }
}
