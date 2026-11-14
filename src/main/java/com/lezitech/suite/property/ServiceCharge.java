package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
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
 * Service charge as a yearly budget (owner's decisions, 2026-11-14; used when {@code settings.rent.serviceMode} is
 * BUDGET). The landlord lists the year's costs (security, cleaning, common power...). The total, plus anything carried
 * from last year, is shared by each unit's floor size over all units (empty units' share is the landlord's) and billed
 * monthly. Actual spending is recorded through the year. Closing the year carries the difference (spent minus billed)
 * into next year's budget; nothing is billed or credited to tenants. Callers run inside the workspace's transaction.
 */
@Service
public class ServiceCharge {

    private final Db db;

    public ServiceCharge(Db db) {
        this.db = db;
    }

    public Map<String, Object> budgetRow(int year, boolean create) {
        var found = db.find("SELECT * FROM sc_budgets WHERE year = :y", "y", year);
        if (found.isPresent() || !create) {
            return found.orElse(null);
        }
        UUID id = db.insert("INSERT INTO sc_budgets (tenant_id, year) VALUES (:t, :y) RETURNING id", "y", year);
        return db.one("Budget", "SELECT * FROM sc_budgets WHERE id = :id", "id", id);
    }

    /** The year's budget total (lines plus anything carried from last year). */
    public BigDecimal total(int year) {
        Map<String, Object> b = budgetRow(year, false);
        if (b == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal lines = db.value("SELECT coalesce(sum(amount), 0) FROM sc_budget_lines WHERE budget_id = :b", BigDecimal.class, "b", b.get("id"));
        return lines.add((BigDecimal) b.get("carried")).max(BigDecimal.ZERO);
    }

    private BigDecimal totalArea() {
        return db.value("SELECT coalesce(sum(area_sqft), 0) FROM spaces WHERE active AND area_sqft > 0 AND kind IN " + PropertyController.UNIT_KINDS, BigDecimal.class);
    }

    /** One unit's monthly service charge for the year: budget × its floor size ÷ all units' floor size ÷ 12. */
    public BigDecimal monthlyShare(UUID spaceId, int year) {
        BigDecimal total = total(year);
        BigDecimal area = totalArea();
        Integer unit = db.value("SELECT area_sqft FROM spaces WHERE id = :id", Integer.class, "id", spaceId);
        if (total.signum() <= 0 || area.signum() <= 0 || unit == null || unit <= 0) {
            return BigDecimal.ZERO;
        }
        return total.multiply(BigDecimal.valueOf(unit)).divide(area.multiply(BigDecimal.valueOf(12)), 0, RoundingMode.HALF_UP);
    }

    /** Replace the year's budget lines: [{name, amount}]. Refused once the year is closed. */
    public Map<String, Object> setLines(int year, List<Map<String, Object>> raw) {
        Map<String, Object> b = budgetRow(year, true);
        if ("CLOSED".equals(b.get("status"))) {
            throw ApiException.conflict(year + " is closed; its budget can't change");
        }
        db.exec("DELETE FROM sc_budget_lines WHERE budget_id = :b", "b", b.get("id"));
        int sort = 0;
        for (Map<String, Object> l : raw == null ? List.<Map<String, Object>>of() : raw) {
            Body x = new Body(l);
            String name = x.str("name", "").trim();
            BigDecimal amount = x.num("amount", BigDecimal.ZERO);
            if (name.isEmpty() && amount.signum() == 0) {
                continue;
            }
            if (name.isEmpty() || amount.signum() < 0) {
                throw ApiException.bad("Each budget line needs a name and an amount");
            }
            db.exec("INSERT INTO sc_budget_lines (tenant_id, budget_id, name, amount, sort) VALUES (:t, :b, :n, :a, :s)",
                    "b", b.get("id"), "n", name.length() > 100 ? name.substring(0, 100) : name, "a", amount, "s", sort++);
        }
        return summary(year);
    }

    public Map<String, Object> addExpense(int year, Body b) {
        Map<String, Object> budget = budgetRow(year, true);
        if ("CLOSED".equals(budget.get("status"))) {
            throw ApiException.conflict(year + " is closed; record this in the current year");
        }
        String what = b.str("what", "").trim();
        BigDecimal amount = b.num("amount", BigDecimal.ZERO);
        if (what.isEmpty() || amount.signum() <= 0) {
            throw ApiException.bad("Say what was paid for and how much");
        }
        LocalDate on = b.has("spentOn") ? b.date("spentOn") : LeaseLedger.today();
        UUID line = b.has("lineId") && !b.str("lineId", "").isBlank() ? UUID.fromString(b.str("lineId")) : null;
        db.exec("""
                INSERT INTO sc_expenses (tenant_id, budget_id, line_id, what, amount, spent_on, reference, user_id)
                VALUES (:t, :b, :l, :w, :a, :d, :r, :u)""",
                "b", budget.get("id"), "l", line, "w", what.length() > 150 ? what.substring(0, 150) : what, "a", amount, "d", on,
                "r", b.str("reference", null), "u", TenantContext.userId());
        return summary(year);
    }

    public void removeExpense(UUID id) {
        Map<String, Object> e = db.one("Expense", "SELECT e.id, b.status FROM sc_expenses e JOIN sc_budgets b ON b.id = e.budget_id WHERE e.id = :id", "id", id);
        if ("CLOSED".equals(e.get("status"))) {
            throw ApiException.conflict("That year is closed");
        }
        db.exec("DELETE FROM sc_expenses WHERE id = :id", "id", id);
    }

    /** Service charge billed to tenants for the year (bill lines named "Service charge · YYYY-MM"). */
    public BigDecimal billed(int year) {
        return db.value("""
                SELECT coalesce(sum(ol.line_total), 0) FROM order_lines ol JOIN orders o ON o.id = ol.order_id
                WHERE o.lease_id IS NOT NULL AND o.status <> 'VOID' AND ol.name LIKE :name""", BigDecimal.class, "name", "Service charge · " + year + "-%");
    }

    public Map<String, Object> summary(int year) {
        Map<String, Object> b = budgetRow(year, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("status", b == null ? "NONE" : b.get("status"));
        out.put("carried", b == null ? BigDecimal.ZERO : b.get("carried"));
        out.put("lines", b == null ? List.of() : db.list("SELECT id, name, amount FROM sc_budget_lines WHERE budget_id = :b ORDER BY sort", "b", b.get("id")));
        List<Map<String, Object>> expenses = b == null ? List.of() : db.list("""
                SELECT e.id, e.what, e.amount, e.spent_on, e.reference, l.name AS line_name FROM sc_expenses e
                LEFT JOIN sc_budget_lines l ON l.id = e.line_id WHERE e.budget_id = :b ORDER BY e.spent_on DESC, e.created_at DESC""", "b", b.get("id"));
        out.put("expenses", expenses);
        BigDecimal total = total(year);
        BigDecimal spent = expenses.stream().map(e -> (BigDecimal) e.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        out.put("total", total);
        out.put("spent", spent);
        out.put("billed", billed(year));
        out.put("closedNote", b == null ? null : b.get("closedNote"));
        // Each unit's share, so the landlord sees what tenants will pay (and the empty units' share they carry).
        BigDecimal area = totalArea();
        List<Map<String, Object>> shares = new ArrayList<>();
        int missingArea = 0;
        for (Map<String, Object> u : db.list("""
                SELECT sp.id, sp.name, sp.area_sqft, c.name AS tenant_name FROM spaces sp
                LEFT JOIN leases l ON l.space_id = sp.id AND l.status = 'ACTIVE' LEFT JOIN customers c ON c.id = l.customer_id
                WHERE sp.active AND sp.kind IN """ + " " + PropertyController.UNIT_KINDS + " ORDER BY sp.sort, sp.name")) {
            if (u.get("areaSqft") == null) {
                missingArea++;
            }
            Map<String, Object> m = new LinkedHashMap<>(u);
            m.put("monthly", monthlyShare(UUID.fromString(u.get("id").toString()), year));
            shares.add(m);
        }
        out.put("shares", shares);
        out.put("totalArea", area);
        out.put("unitsWithoutSize", missingArea);
        return out;
    }

    /** Close the year: spent minus billed is carried into next year's budget (a shortfall adds, a surplus takes off). */
    public Map<String, Object> close(int year, String note) {
        Map<String, Object> b = budgetRow(year, false);
        if (b == null) {
            throw ApiException.notFound("Budget");
        }
        if ("CLOSED".equals(b.get("status"))) {
            throw ApiException.conflict(year + " is already closed");
        }
        BigDecimal spent = db.value("SELECT coalesce(sum(amount), 0) FROM sc_expenses WHERE budget_id = :b", BigDecimal.class, "b", b.get("id"));
        BigDecimal billed = billed(year);
        BigDecimal carry = spent.subtract(billed);
        Map<String, Object> next = budgetRow(year + 1, true);
        db.exec("UPDATE sc_budgets SET carried = carried + :c WHERE id = :id", "id", next.get("id"), "c", carry);
        db.exec("UPDATE sc_budgets SET status = 'CLOSED', closed_at = now(), closed_note = :n WHERE id = :id", "id", b.get("id"),
                "n", "Spent " + spent.toPlainString() + ", billed " + billed.toPlainString() + "; "
                        + (carry.signum() > 0 ? carry.toPlainString() + " short, added to " : carry.signum() < 0 ? carry.negate().toPlainString() + " over, taken off " : "nothing to carry to ")
                        + (year + 1) + "'s budget" + (note == null || note.isBlank() ? "" : " · " + note.trim()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spent", spent);
        out.put("billed", billed);
        out.put("carried", carry);
        out.put("nextYear", summary(year + 1));
        return out;
    }
}
