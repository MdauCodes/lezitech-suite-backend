package com.lezitech.suite.sales;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Till sessions (a cashier's drawer from opening to closing), cf. Odoo pos_hr's per-employee closing.
 *
 * - Opening needs the float: the money in the drawer at the start.
 * - Closing is a blind count: cashiers type what they counted and aren't shown what was expected; the owner, managers
 *   and the accountant see expected, counted and the difference.
 * - Cashiers see only their own sessions, without expected amounts or differences.
 * - Till days: the owner, a manager or the accountant reconciles each closed session (matched, difference noted,
 *   investigated, with a note), which locks its sales; only the owner can unlock, and that is logged.
 */
@RestController
@RequestMapping("/api/shifts")
@Transactional
public class ShiftController {

    static final Set<String> STATUSES = Set.of("MATCHED", "DIFFERENCE", "INVESTIGATED");

    private final Db db;

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.activity.ActivityLog activity;

    public ShiftController(Db db) {
        this.db = db;
    }

    @GetMapping("/current")
    public Map<String, Object> current() {
        return db.find("SELECT * FROM shifts WHERE user_id = :u AND status = 'OPEN' LIMIT 1")
                .map(s -> forCaller(full(UUID.fromString(s.get("id").toString()))))
                .orElse(Map.of("open", false));
    }

    /** Recent sessions: everyone's for the owner, managers and accountant; a cashier's own otherwise. */
    @GetMapping
    public List<Map<String, Object>> recent() {
        boolean all = TillRules.seesTotals();
        List<Map<String, Object>> rows = db.list("""
                SELECT s.id, s.user_id, s.opened_at, s.closed_at, s.status, s.opening_cash, s.counted_cash, s.counted_mpesa,
                       s.counted_card, s.reconciled_at IS NOT NULL AS checked, s.locked, u.name AS user_name,
                       (SELECT coalesce(sum(amount), 0) FROM payments p WHERE p.shift_id = s.id) AS takings,
                       (SELECT count(*) FROM orders o WHERE o.shift_id = s.id AND o.status IN ('PAID','CREDIT')) AS orders
                FROM shifts s LEFT JOIN users u ON u.id = s.user_id
                WHERE (:all OR s.user_id = :u)
                ORDER BY s.opened_at DESC LIMIT 60""", "all", all);
        return rows;
    }

    @PostMapping("/open")
    public Map<String, Object> open(@RequestBody Map<String, Object> raw) {
        if (db.find("SELECT id FROM shifts WHERE user_id = :u AND status = 'OPEN'").isPresent()) {
            throw ApiException.conflict("You already have an open till");
        }
        Body b = new Body(raw);
        if (!b.has("openingCash")) {
            throw ApiException.bad("Count the money in your drawer and enter it (0 if it's empty)");
        }
        BigDecimal cash = b.num("openingCash", null);
        if (cash == null || cash.signum() < 0) {
            throw ApiException.bad("The money in the drawer can't be below zero");
        }
        UUID id = db.insert("""
                INSERT INTO shifts (tenant_id, user_id, location_id, opening_cash)
                VALUES (:t, :u, (SELECT id FROM locations WHERE is_default LIMIT 1), :cash) RETURNING id""",
                "cash", cash);
        return forCaller(full(id));
    }

    /** Close a till with what was counted. Cashiers close their own; the reply doesn't tell them what was expected. */
    @PostMapping("/{id}/close")
    public Map<String, Object> close(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Map<String, Object> shift = mine(id);
        if (!"OPEN".equals(shift.get("status"))) {
            throw ApiException.conflict("This till is already closed");
        }
        Body b = new Body(raw);
        if (!b.has("countedCash")) {
            throw ApiException.bad("Count the cash in the drawer and enter it");
        }
        db.exec("""
                UPDATE shifts SET status = 'CLOSED', closed_at = now(), counted_cash = :cash, counted_mpesa = :mpesa,
                       counted_card = :card, note = :note WHERE id = :id AND status = 'OPEN'""",
                "id", id, "cash", b.num("countedCash", null), "mpesa", b.num("countedMpesa", null),
                "card", b.num("countedCard", null), "note", b.str("note", null));
        Map<String, Object> s = full(id);
        BigDecimal diff = b.num("countedCash").subtract((BigDecimal) s.get("expectedCash"));
        if (diff.signum() != 0) {
            activity.record(diff.signum() < 0 ? "CASH_SHORT" : "CASH_OVER",
                    diff.signum() < 0 ? com.lezitech.suite.activity.ActivityLog.CONCERN : com.lezitech.suite.activity.ActivityLog.NOTICE,
                    "Till closed " + (diff.signum() < 0 ? "short by " : "over by ") + diff.abs().toPlainString()
                            + " (expected " + ((BigDecimal) s.get("expectedCash")).toPlainString() + " in cash)",
                    null, null, diff.abs(), null, Map.of("shift", id.toString(), "note", b.str("note", "")));
        }
        return forCaller(s);
    }

    @GetMapping("/{id}")
    public Map<String, Object> summary(@PathVariable UUID id) {
        mine(id);
        return forCaller(full(id));
    }

    // ---------- till days: reconcile and lock ----------

    /** Every till session in the dates (default the last 14 days), with expected, counted and differences. */
    @GetMapping("/days")
    public Map<String, Object> days(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(13) : from;
        if (start.isAfter(end) || start.isBefore(end.minusDays(92))) {
            throw ApiException.bad("Choose up to three months, with the start before the end");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : db.list("""
                SELECT id FROM shifts WHERE opened_at >= cast(:from as date) AND opened_at < cast(:to as date) + 1
                ORDER BY opened_at DESC LIMIT 300""", "from", start, "to", end)) {
            out.add(full(UUID.fromString(s.get("id").toString())));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("from", start);
        res.put("to", end);
        res.put("shifts", out);
        res.put("toCheck", out.stream().filter(s -> "CLOSED".equals(s.get("status")) && s.get("reconcileStatus") == null).count());
        return res;
    }

    /** Check a closed till session: matched, difference noted or investigated (a note is needed unless it matched). Locks its sales. */
    @PostMapping("/{id}/reconcile")
    public Map<String, Object> reconcile(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        Body b = new Body(raw);
        String status = b.str("status", "").toUpperCase();
        String note = b.str("note", null);
        note = note == null || note.isBlank() ? null : note.trim();
        if (!STATUSES.contains(status)) {
            throw ApiException.bad("Choose matched, difference noted or investigated");
        }
        if (!"MATCHED".equals(status) && note == null) {
            throw ApiException.bad("Say what happened with the difference, so it's clear later");
        }
        Map<String, Object> s = db.one("Till", "SELECT status, locked, user_id FROM shifts WHERE id = :id FOR UPDATE", "id", id);
        if (!"CLOSED".equals(s.get("status"))) {
            throw ApiException.conflict("Close the till before checking it");
        }
        if (Boolean.TRUE.equals(s.get("locked"))) {
            throw ApiException.conflict("This till day is already checked and locked. The owner can unlock it to change it.");
        }
        db.exec("""
                UPDATE shifts SET reconcile_status = :st, reconcile_note = :n, reconciled_at = now(), reconciled_by = :u, locked = true
                WHERE id = :id""", "id", id, "st", status, "n", note);
        Map<String, Object> out = full(id);
        activity.record("TILL_CHECKED", "MATCHED".equals(status) ? com.lezitech.suite.activity.ActivityLog.INFO
                        : com.lezitech.suite.activity.ActivityLog.NOTICE,
                "Checked and locked " + out.get("userName") + "'s till (" + label(status) + ")" + (note == null ? "" : ": " + note),
                null, null, (BigDecimal) out.get("cashDifference"), null, Map.of("shift", id.toString()));
        return out;
    }

    /** Owner only: open a checked till day again (to refund or cancel one of its sales). Recorded in the activity log. */
    @PostMapping("/{id}/unlock")
    public Map<String, Object> unlock(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole();
        String reason = new Body(raw).str("reason", null);
        if (reason == null || reason.isBlank()) {
            throw ApiException.bad("Say why you're unlocking it");
        }
        if (db.exec("UPDATE shifts SET locked = false, reconcile_status = NULL, reconciled_at = NULL, reconciled_by = NULL WHERE id = :id AND locked",
                "id", id) == 0) {
            throw ApiException.conflict("This till day isn't locked");
        }
        Map<String, Object> out = full(id);
        activity.record("TILL_UNLOCKED", com.lezitech.suite.activity.ActivityLog.NOTICE,
                "Unlocked " + out.get("userName") + "'s till from " + out.get("openedAt") + ": " + reason.trim(),
                null, null, null, null, Map.of("shift", id.toString()));
        return out;
    }

    // ---------- helpers ----------

    /** The session, if the caller may see it: their own, or any for those who see totals. */
    private Map<String, Object> mine(UUID id) {
        Map<String, Object> s = db.one("Till", "SELECT * FROM shifts WHERE id = :id", "id", id);
        TenantContext.Caller c = TenantContext.caller();
        if (!TillRules.seesTotals() && !String.valueOf(s.get("userId")).equals(String.valueOf(c.userId()))) {
            throw ApiException.forbidden("You can only see your own till");
        }
        return s;
    }

    /** Everything about a session, with expected amounts and differences. */
    private Map<String, Object> full(UUID id) {
        Map<String, Object> s = new LinkedHashMap<>(db.one("Till", """
                SELECT s.*, u.name AS user_name, r.name AS reconciled_by_name
                FROM shifts s LEFT JOIN users u ON u.id = s.user_id LEFT JOIN users r ON r.id = s.reconciled_by
                WHERE s.id = :id""", "id", id));
        List<Map<String, Object>> byMethod = db.list("""
                SELECT method, sum(amount) AS amount, count(*) FILTER (WHERE kind = 'PAYMENT') AS count,
                       coalesce(-sum(amount) FILTER (WHERE kind = 'REFUND'), 0) AS refunded
                FROM payments WHERE shift_id = :id GROUP BY method ORDER BY method""", "id", id);
        s.put("refunds", db.value("SELECT coalesce(-sum(amount), 0) FROM payments WHERE shift_id = :id AND kind = 'REFUND'",
                BigDecimal.class, "id", id));
        BigDecimal cash = method(byMethod, "CASH");
        BigDecimal mpesa = method(byMethod, "MPESA");
        BigDecimal card = method(byMethod, "CARD");
        BigDecimal expectedCash = ((BigDecimal) s.get("openingCash")).add(cash);
        s.put("open", "OPEN".equals(s.get("status")));
        s.put("byMethod", byMethod);
        s.put("expectedCash", expectedCash);
        s.put("expectedMpesa", mpesa);
        s.put("expectedCard", card);
        s.put("cashDifference", diff(s.get("countedCash"), expectedCash));
        s.put("mpesaDifference", diff(s.get("countedMpesa"), mpesa));
        s.put("cardDifference", diff(s.get("countedCard"), card));
        s.put("sales", byMethod.stream().map(m -> (BigDecimal) m.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add));
        s.put("byCashier", db.list("""
                SELECT u.name AS user_name, p.method, sum(p.amount) AS amount
                FROM payments p LEFT JOIN users u ON u.id = p.user_id WHERE p.shift_id = :id
                GROUP BY u.name, p.method ORDER BY u.name, p.method""", "id", id));
        s.put("orders", db.value("SELECT count(*) FROM orders WHERE shift_id = :id AND status IN ('PAID','CREDIT')", Long.class, "id", id));
        return s;
    }

    /**
     * What a cashier may see of their own till: the float, how many sales and their total, what they counted, and
     * whether it's been checked. Not what was expected, the split by method (which would give it away), or differences.
     */
    private static Map<String, Object> forCaller(Map<String, Object> s) {
        if (TillRules.seesTotals()) {
            return s;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("id", "userId", "userName", "openedAt", "closedAt", "status", "open", "openingCash",
                "countedCash", "countedMpesa", "countedCard", "note", "orders", "sales", "refunds", "locked")) {
            out.put(k, s.get(k));
        }
        out.put("checked", s.get("reconciledAt") != null);
        out.put("blind", true);
        return out;
    }

    private static BigDecimal method(List<Map<String, Object>> byMethod, String m) {
        return byMethod.stream().filter(x -> m.equals(x.get("method"))).map(x -> (BigDecimal) x.get("amount")).findFirst().orElse(BigDecimal.ZERO);
    }

    private static BigDecimal diff(Object counted, BigDecimal expected) {
        return counted == null ? null : ((BigDecimal) counted).subtract(expected);
    }

    private static String label(String status) {
        return switch (status) {
            case "MATCHED" -> "matched";
            case "DIFFERENCE" -> "difference noted";
            default -> "investigated";
        };
    }
}
