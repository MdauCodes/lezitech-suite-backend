package com.lezitech.suite.finance;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the business spends: rent, wages, electricity, transport, supplies... Owners, managers and accountants record
 * anything; anyone with an open till can record cash paid out of that till (e.g. paying the water vendor), which
 * lowers the cash the till should have at closing and shows in the activity log.
 */
@Service
@Transactional
public class ExpenseService {

    static final Set<String> METHODS = Set.of("CASH", "MPESA", "BANK", "CARD");
    static final Set<String> BACK_OFFICE = Set.of("OWNER", "MANAGER", "ACCOUNTANT");

    /** Where money usually goes, by kind of business. The owner can add their own in Settings. */
    static final List<String> COMMON = List.of("Rent", "Salaries and wages", "Electricity", "Water", "Transport", "Airtime and data",
            "Repairs", "Marketing", "Licences and permits", "Bank and M-Pesa charges");
    static final Map<String, List<String>> BY_SECTOR = Map.of(
            "restaurant", List.of("Gas and charcoal", "Cleaning supplies", "Packaging and takeaway boxes"),
            "hotel", List.of("Laundry", "Gas and charcoal", "Guest supplies", "Commissions to booking sites"),
            "retail", List.of("Bags and packaging", "Security"),
            "beauty", List.of("Salon supplies", "Laundry (towels)"),
            "wholesale", List.of("Fuel", "Loading and offloading", "Packaging"),
            "services", List.of("Fuel", "Tools and equipment", "Subcontractors"),
            "tours", List.of("Fuel", "Park fees", "Vehicle service"),
            "property", List.of("Security", "Cleaning", "Garbage collection"),
            "pharmacy", List.of("Cold chain (fridge)", "Security"),
            "resort", List.of("Gas and charcoal", "Pool chemicals", "Grounds and gardens", "Security", "Entertainment and artists"));

    private final Db db;
    private final ActivityLog activity;

    public ExpenseService(Db db, ActivityLog activity) {
        this.db = db;
        this.activity = activity;
    }

    /** The business's categories: its own list if saved, else the common ones for its kind of business, then Other. */
    public List<String> categories() {
        Map<String, Object> t = db.one("Business", """
                SELECT sector, settings->'expenseCategories' AS own FROM tenants WHERE id = :t""");
        List<String> out = new ArrayList<>();
        if (t.get("own") instanceof com.fasterxml.jackson.databind.JsonNode own && own.isArray() && !own.isEmpty()) {
            own.forEach(n -> out.add(n.asText()));
        } else {
            out.addAll(COMMON);
            out.addAll(BY_SECTOR.getOrDefault((String) t.get("sector"), List.of()));
        }
        if (!out.contains("Other")) {
            out.add("Other");
        }
        return out;
    }

    public Map<String, Object> list(LocalDate from, LocalDate to, String category) {
        Object[] p = {"from", from, "to", to, "cat", blank(category)};
        String where = """
                e.spent_on >= :from AND e.spent_on <= :to AND (cast(:cat as text) IS NULL OR e.category = cast(:cat as text))""";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("expenses", db.list("""
                SELECT e.*, u.name AS created_by_name, l.name AS branch FROM expenses e
                LEFT JOIN users u ON u.id = e.created_by LEFT JOIN locations l ON l.id = e.location_id
                WHERE """ + " " + where + " ORDER BY e.spent_on DESC, e.created_at DESC LIMIT 500", p));
        out.put("byCategory", db.list("SELECT e.category, sum(e.amount) AS amount, count(*) AS count FROM expenses e WHERE "
                + " " + where + " GROUP BY e.category ORDER BY sum(e.amount) DESC", p));
        out.put("total", db.value("SELECT coalesce(sum(e.amount), 0) FROM expenses e WHERE " + " " + where, BigDecimal.class, p));
        out.put("categories", categories());
        return out;
    }

    /**
     * Record an expense. {@code fromTill}: cash out of the caller's open till (anyone with a till may do this);
     * otherwise only the owner, a manager or the accountant may record expenses.
     */
    public Map<String, Object> add(Body b) {
        TenantContext.Caller me = TenantContext.caller();
        boolean fromTill = b.bool("fromTill", false);
        UUID shift = null;
        if (fromTill) {
            shift = db.value("SELECT id FROM shifts WHERE user_id = :u AND status = 'OPEN' LIMIT 1", UUID.class);
            if (shift == null) {
                throw ApiException.conflict("Open your till first: the cash for this comes out of it.");
            }
        } else if (!BACK_OFFICE.contains(me.role())) {
            throw ApiException.forbidden("Only the owner, a manager or the accountant records expenses. You can pay something from your till instead.");
        }
        Fields f = fields(b, fromTill);
        UUID id = db.insert("""
                INSERT INTO expenses (tenant_id, spent_on, category, what, amount, method, paid_to, reference, location_id, shift_id, created_by)
                VALUES (:t, :on, :cat, :what, :amount, :method, :to, :ref,
                        coalesce(cast(:loc as uuid), (SELECT location_id FROM shifts WHERE id = cast(:shift as uuid))), :shift, :u)
                RETURNING id""",
                "on", fromTill ? LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")) : f.on, "cat", f.category, "what", f.what,
                "amount", f.amount, "method", f.method, "to", f.paidTo, "ref", f.reference,
                "loc", b.has("locationId") ? UUID.fromString(b.str("locationId")) : null, "shift", shift);
        if (fromTill) {
            activity.record("TILL_PAYOUT", ActivityLog.NOTICE,
                    "Paid " + f.amount.toPlainString() + " from the till: " + f.what + (f.paidTo == null ? "" : " to " + f.paidTo),
                    null, null, f.amount, null, Map.of("expense", id.toString(), "category", f.category));
        }
        return one(id);
    }

    public Map<String, Object> update(UUID id, Body b) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        Map<String, Object> before = one(id);
        Fields f = fields(b, before.get("shiftId") != null);
        db.exec("""
                UPDATE expenses SET spent_on = :on, category = :cat, what = :what, amount = :amount, method = :method,
                       paid_to = :to, reference = :ref WHERE id = :id""",
                "id", id, "on", f.on, "cat", f.category, "what", f.what, "amount", f.amount, "method", f.method, "to", f.paidTo, "ref", f.reference);
        if (((BigDecimal) before.get("amount")).compareTo(f.amount) != 0) {
            activity.record("EXPENSE_CHANGE", ActivityLog.NOTICE, "Changed an expense (" + f.what + ") from "
                    + ((BigDecimal) before.get("amount")).toPlainString() + " to " + f.amount.toPlainString(), null, null, null, null, Map.of());
        }
        return one(id);
    }

    public void delete(UUID id) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        Map<String, Object> e = one(id);
        db.exec("DELETE FROM expenses WHERE id = :id", "id", id);
        activity.record("EXPENSE_CHANGE", ActivityLog.NOTICE, "Removed an expense: " + e.get("what") + " ("
                + ((BigDecimal) e.get("amount")).toPlainString() + ")", null, null, (BigDecimal) e.get("amount"), null, Map.of());
    }

    public void setReceipt(UUID id, UUID fileId) {
        one(id);
        db.exec("UPDATE expenses SET receipt_file_id = :f WHERE id = :id", "f", fileId, "id", id);
    }

    /** Cash paid out of a till during this session (lowers the cash expected at closing). */
    public BigDecimal paidFromShift(UUID shiftId) {
        return db.value("SELECT coalesce(sum(amount), 0) FROM expenses WHERE shift_id = :s", BigDecimal.class, "s", shiftId);
    }

    public Map<String, Object> one(UUID id) {
        return db.one("Expense", "SELECT * FROM expenses WHERE id = :id", "id", id);
    }

    private record Fields(LocalDate on, String category, String what, BigDecimal amount, String method, String paidTo, String reference) {
    }

    private Fields fields(Body b, boolean fromTill) {
        String what = blank(b.str("what", null));
        if (what == null) {
            throw ApiException.bad("Say what the money was for");
        }
        BigDecimal amount;
        try {
            amount = b.num("amount").setScale(2, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            throw ApiException.bad("Enter how much was paid");
        }
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("100000000")) >= 0) {
            throw ApiException.bad("Enter how much was paid");
        }
        String method = fromTill ? "CASH" : b.str("method", "CASH").toUpperCase();
        if (!METHODS.contains(method)) {
            throw ApiException.bad("Paid by cash, M-Pesa, bank or card");
        }
        String category = blank(b.str("category", null));
        if (category == null) {
            category = "Other";
        }
        LocalDate on = b.has("spentOn") ? b.date("spentOn") : LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"));
        if (on.isAfter(LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")).plusDays(1))) {
            throw ApiException.bad("The date can't be in the future");
        }
        return new Fields(on, clip(category, 60), clip(what, 200), amount, method, clip(blank(b.str("paidTo", null)), 120),
                clip(blank(b.str("reference", null)), 80));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
