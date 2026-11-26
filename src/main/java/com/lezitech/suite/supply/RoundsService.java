package com.lezitech.suite.supply;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.BrandedEmail;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Supply rounds. Every handover has two names: the store loads (name 1), the driver accepts on his own phone (name 2).
 * The driver delivers, the client's clerk confirms what arrived (the driver cannot edit it), and the invoice follows
 * what was confirmed. Run: LOADING, OUT, BACK. Stop: PENDING, EMAILED, then CONFIRMED (all received) or PART.
 */
@Service
@Transactional
public class RoundsService {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final int MAX_SENDS = 3;
    static final int MAX_TRIES = 5;
    static final int MAX_SIGNATURE = 150 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SEE = Set.of("OWNER", "MANAGER", "STOREKEEPER", "ACCOUNTANT");

    /** The result of a code attempt: wrong tries must be saved, so the answer is returned and the controller refuses after the commit. */
    public record CodeResult(boolean ok, String message, Map<String, Object> stop) {
    }

    private final Db db;
    private final OrderService orders;
    private final ActivityLog activity;
    private final Mailer mailer;
    private final BrandedEmail brands;
    private final com.lezitech.suite.crm.CustomerLedger customerLedger;
    private final com.lezitech.suite.stock.StockService stock;

    public RoundsService(Db db, OrderService orders, ActivityLog activity, Mailer mailer, BrandedEmail brands,
                         com.lezitech.suite.crm.CustomerLedger customerLedger, com.lezitech.suite.stock.StockService stock) {
        this.customerLedger = customerLedger;
        this.stock = stock;
        this.db = db;
        this.orders = orders;
        this.activity = activity;
        this.mailer = mailer;
        this.brands = brands;
    }

    // ---------------- helpers ----------------

    static UUID uuid(Object o) {
        return o == null ? null : UUID.fromString(o.toString());
    }

    static double num(Object o) {
        return o == null ? 0 : ((BigDecimal) o).doubleValue();
    }

    static String sha(String s) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String codeHash(UUID stop, String code) {
        return sha(stop + ":" + code);
    }

    static String noteNo(Object n) {
        return "DN-" + n;
    }

    private static String qty(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal bd(Object o, String what) {
        if (o == null || o.toString().isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(o.toString());
            if (v.signum() < 0 || v.stripTrailingZeros().scale() > 3 || v.compareTo(new BigDecimal("100000000")) > 0) {
                throw ApiException.bad(what + " is not a valid amount");
            }
            return v;
        } catch (NumberFormatException e) {
            throw ApiException.bad(what + " is not a valid amount");
        }
    }

    /** An address the way the mail system sees it: no capitals, no +tag, and no dots in the name for gmail. */
    static String normEmail(String e) {
        if (e == null) {
            return "";
        }
        String s = e.trim().toLowerCase(java.util.Locale.ROOT);
        int at = s.lastIndexOf('@');
        if (at < 1) {
            return s;
        }
        String name = s.substring(0, at);
        String host = s.substring(at + 1);
        int plus = name.indexOf('+');
        if (plus > 0) {
            name = name.substring(0, plus);
        }
        if (host.equals("gmail.com") || host.equals("googlemail.com")) {
            name = name.replace(".", "");
            host = "gmail.com";
        }
        return name + "@" + host;
    }

    /** True when the address is one of this business's staff (compared the way normEmail does). */
    private boolean isStaffEmail(String email) {
        String want = normEmail(email);
        for (Map<String, Object> u : db.list("SELECT email FROM users WHERE email IS NOT NULL")) {
            if (normEmail((String) u.get("email")).equals(want)) {
                return true;
            }
        }
        return false;
    }

    private boolean sees() {
        return SEE.contains(TenantContext.caller().role());
    }

    private Map<String, Object> runRow(UUID id) {
        return db.one("Round", "SELECT * FROM delivery_runs WHERE id = :id", "id", id);
    }

    /** The round, locked until this transaction ends, so two changes to it cannot cross. */
    private Map<String, Object> runRowLock(UUID id) {
        return db.one("Round", "SELECT * FROM delivery_runs WHERE id = :id FOR UPDATE", "id", id);
    }

    private void requireDriver(Map<String, Object> run, String what) {
        if (!TenantContext.userId().toString().equals(run.get("driverUserId"))) {
            throw ApiException.forbidden("Only the driver of this round can " + what + ".");
        }
    }

    /** The stop, locked until this transaction ends, once the signed-in user is shown to be the driver of its round. */
    private Map<String, Object> stopForDriver(UUID stopId) {
        Map<String, Object> s = db.one("Stop", "SELECT * FROM delivery_stops WHERE id = :id FOR UPDATE", "id", stopId);
        requireDriver(runRow(uuid(s.get("runId"))), "do this");
        return s;
    }

    private static boolean isDone(Object status) {
        return "CONFIRMED".equals(status) || "PART".equals(status);
    }

    /** Confirmed, or closed by the office without a confirmation. */
    private static boolean isResolved(Object status) {
        return isDone(status) || "MISSED".equals(status);
    }

    // ---------------- lists ----------------

    public List<Map<String, Object>> list(String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now(NAIROBI) : parseDate(date);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.list("""
                SELECT r.id, r.run_date, r.vehicle, r.driver_user_id, r.driver_name, r.status,
                       (SELECT count(*) FROM delivery_stops s WHERE s.run_id = r.id) AS stop_count,
                       (SELECT count(*) FROM delivery_stops s WHERE s.run_id = r.id AND s.status IN ('CONFIRMED', 'PART')) AS confirmed_count
                FROM delivery_runs r WHERE r.run_date = :d ORDER BY r.created_at""", "d", day)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("runDate", r.get("runDate"));
            m.put("vehicle", r.get("vehicle"));
            m.put("driverUserId", r.get("driverUserId"));
            m.put("driverName", r.get("driverName"));
            m.put("status", r.get("status"));
            m.put("stopCount", ((Number) r.get("stopCount")).intValue());
            m.put("confirmedCount", ((Number) r.get("confirmedCount")).intValue());
            out.add(m);
        }
        return out;
    }

    private static LocalDate parseDate(String d) {
        try {
            return LocalDate.parse(d);
        } catch (RuntimeException e) {
            throw ApiException.bad("Give the date as year-month-day, for example 2026-03-05");
        }
    }

    public List<Map<String, Object>> ordersReady() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : db.list("""
                SELECT o.id, o.number, o.status, o.total, c.name AS customer_name, c.email AS customer_email
                FROM orders o JOIN customers c ON c.id = o.customer_id
                WHERE (o.status = 'OPEN' OR (o.status = 'CREDIT' AND coalesce((SELECT sum(p.amount) FROM payments p WHERE p.order_id = o.id AND p.method <> 'CREDIT'), 0) = 0))
                  AND NOT EXISTS (SELECT 1 FROM delivery_stops d WHERE d.order_id = o.id AND d.status IN ('CONFIRMED', 'PART')) AND o.kind <> 'QUOTE'
                  AND NOT EXISTS (SELECT 1 FROM delivery_stops s JOIN delivery_runs r ON r.id = s.run_id
                                  WHERE s.order_id = o.id AND r.status <> 'BACK')
                ORDER BY o.number DESC LIMIT 200""")) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> l : orderLines(uuid(o.get("id")))) {
                lines.add(Map.of("itemId", l.get("itemId"), "name", l.get("name"), "qty", num(l.get("qty"))));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", o.get("id"));
            m.put("orderStatus", o.get("status"));
            m.put("number", o.get("number"));
            m.put("customerName", o.get("customerName"));
            m.put("customerEmail", o.get("customerEmail"));
            m.put("total", num(o.get("total")));
            m.put("lines", lines);
            out.add(m);
        }
        return out;
    }

    /** The goods on an order: real lines with an item and a positive quantity (deal and combo parts are not goods to load). */
    private List<Map<String, Object>> orderLines(UUID orderId) {
        return db.list("""
                SELECT id, item_id, name, qty FROM order_lines
                WHERE order_id = :o AND item_id IS NOT NULL AND qty > 0 AND deal_id IS NULL AND parent_line_id IS NULL
                ORDER BY created_at, id""", "o", orderId);
    }

    // ---------------- create, load, accept, back ----------------

    public Map<String, Object> create(Map<String, Object> body) {
        LocalDate day = parseDate(String.valueOf(body.get("runDate")));
        String vehicle = body.get("vehicle") == null ? null : body.get("vehicle").toString().trim();
        if (vehicle != null && vehicle.length() > 60) {
            throw ApiException.bad("The vehicle name is too long");
        }
        Map<String, Object> driver = body.get("driverUserId") == null ? null : db.find("""
                SELECT id, name FROM users WHERE id = :d AND role = 'RIDER' AND active""", "d", body.get("driverUserId").toString()).orElse(null);
        if (driver == null) {
            throw ApiException.bad("Pick a driver from your staff with the rider job");
        }
        Set<String> ids = new LinkedHashSet<>();
        if (body.get("orderIds") instanceof List<?> l) {
            l.forEach(x -> ids.add(String.valueOf(x)));
        }
        if (ids.isEmpty()) {
            throw ApiException.bad("Pick at least one order to deliver");
        }
        TenantContext.Caller me = TenantContext.caller();
        UUID run = db.insert("""
                INSERT INTO delivery_runs (tenant_id, run_date, vehicle, driver_user_id, driver_name, created_by, created_by_name)
                VALUES (:t, :d, :v, :driver, :dn, :u, :un) RETURNING id""",
                "d", day, "v", vehicle == null || vehicle.isEmpty() ? null : vehicle, "driver", driver.get("id"), "dn", driver.get("name"),
                "un", me.name());
        Map<UUID, Object[]> load = new LinkedHashMap<>();
        int sort = 0;
        for (String oid : ids) {
            Map<String, Object> o = db.find("""
                    SELECT o.id, o.number, o.status, o.kind, c.name AS customer_name, c.email AS customer_email,
                           (o.status = 'OPEN' OR (o.status = 'CREDIT' AND coalesce((SELECT sum(p.amount) FROM payments p WHERE p.order_id = o.id AND p.method <> 'CREDIT'), 0) = 0))
                  AND NOT EXISTS (SELECT 1 FROM delivery_stops d WHERE d.order_id = o.id AND d.status IN ('CONFIRMED', 'PART')) AS takes_round
                    FROM orders o LEFT JOIN customers c ON c.id = o.customer_id WHERE o.id = :id FOR UPDATE OF o""", "id", oid).orElse(null);
            if (o == null) {
                throw ApiException.bad("One of the orders was not found");
            }
            if (!Boolean.TRUE.equals(o.get("takesRound")) || "QUOTE".equals(o.get("kind"))) {
                throw ApiException.bad("Order #" + o.get("number") + " is not an open order or an unpaid account order");
            }
            if (o.get("customerName") == null) {
                throw ApiException.bad("Order #" + o.get("number") + " has no customer. Add the customer first");
            }
            Boolean taken = db.value("""
                    SELECT exists(SELECT 1 FROM delivery_stops s JOIN delivery_runs r ON r.id = s.run_id
                                  WHERE s.order_id = :o AND r.status <> 'BACK')""", Boolean.class, "o", oid);
            if (Boolean.TRUE.equals(taken)) {
                throw ApiException.bad("Order #" + o.get("number") + " is already on a round");
            }
            List<Map<String, Object>> lines = orderLines(uuid(oid));
            if (lines.isEmpty()) {
                throw ApiException.bad("Order #" + o.get("number") + " has nothing to deliver");
            }
            Integer dn = db.value("UPDATE tenants SET dn_seq = dn_seq + 1 WHERE id = :t RETURNING dn_seq", Integer.class);
            UUID stop = db.insert("""
                    INSERT INTO delivery_stops (tenant_id, run_id, sort, note_no, order_id, customer_name, confirm_email)
                    VALUES (:t, :run, :sort, :dn, :o, :cn, :ce) RETURNING id""",
                    "run", run, "sort", sort++, "dn", dn, "o", oid, "cn", o.get("customerName"),
                    "ce", o.get("customerEmail") == null || o.get("customerEmail").toString().isBlank() || isStaffEmail(o.get("customerEmail").toString())
                            ? null : o.get("customerEmail"));
            if (o.get("customerEmail") != null && !o.get("customerEmail").toString().isBlank() && isStaffEmail(o.get("customerEmail").toString())) {
                activity.record("EMAIL_CHANGED", ActivityLog.CONCERN,
                        "The email on file for " + o.get("customerName") + " (" + o.get("customerEmail") + ") is one of your staff's addresses, so "
                                + noteNo(dn) + " has no confirm email. Set the client's own address.",
                        uuid(oid), null, null, null, Map.of("noteNo", noteNo(dn), "email", o.get("customerEmail").toString()));
            }
            int pos = 0;
            for (Map<String, Object> l : lines) {
                db.exec("""
                        INSERT INTO delivery_stop_lines (tenant_id, stop_id, pos, order_line_id, item_id, name, qty_sent)
                        VALUES (:t, :s, :p, :ol, :i, :n, :q)""",
                        "s", stop, "p", pos++, "ol", l.get("id"), "i", l.get("itemId"), "n", l.get("name"), "q", l.get("qty"));
                UUID item = uuid(l.get("itemId"));
                Object[] cur = load.computeIfAbsent(item, k -> new Object[]{l.get("name"), BigDecimal.ZERO});
                cur[1] = ((BigDecimal) cur[1]).add((BigDecimal) l.get("qty"));
            }
        }
        for (Map.Entry<UUID, Object[]> e : load.entrySet()) {
            db.exec("INSERT INTO delivery_run_lines (tenant_id, run_id, item_id, name, qty_planned) VALUES (:t, :r, :i, :n, :q)",
                    "r", run, "i", e.getKey(), "n", e.getValue()[0], "q", e.getValue()[1]);
        }
        return detail(run);
    }

    public Map<String, Object> saveLoad(UUID runId, Map<String, Object> body) {
        Map<String, Object> run = runRowLock(runId);
        if (!"LOADING".equals(run.get("status"))) {
            throw ApiException.conflict("This round has already gone out, so the load can no longer change");
        }
        Map<String, BigDecimal> given = new LinkedHashMap<>();
        if (body.get("lines") instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map<?, ?> m && m.get("itemId") != null) {
                    BigDecimal q = bd(m.get("qtyLoaded"), "The loaded amount");
                    if (q == null) {
                        throw ApiException.bad("Fill in how many were loaded");
                    }
                    given.put(m.get("itemId").toString().toLowerCase(), q);
                }
            }
        }
        List<Map<String, Object>> want = db.list("SELECT item_id, name FROM delivery_run_lines WHERE run_id = :r", "r", runId);
        for (Map<String, Object> w : want) {
            if (!given.containsKey(w.get("itemId").toString().toLowerCase())) {
                throw ApiException.bad("Fill in how many " + w.get("name") + " went on the vehicle");
            }
        }
        if (given.size() != want.size()) {
            throw ApiException.bad("The load has an item that is not on this round");
        }
        for (Map.Entry<String, BigDecimal> e : given.entrySet()) {
            db.exec("UPDATE delivery_run_lines SET qty_loaded = :q WHERE run_id = :r AND item_id = :i", "q", e.getValue(), "r", runId, "i", e.getKey());
        }
        TenantContext.Caller me = TenantContext.caller();
        int saved = db.exec("UPDATE delivery_runs SET loaded_by = :u, loaded_by_name = :n, loaded_at = clock_timestamp() WHERE id = :id AND status = 'LOADING'",
                "n", me.name(), "id", runId);
        if (saved == 0) {
            throw ApiException.conflict("This round has already gone out, so the load can no longer change");
        }
        return detail(runId);
    }

    public Map<String, Object> accept(UUID runId, Map<String, Object> body) {
        Map<String, Object> run = runRowLock(runId);
        requireDriver(run, "accept this load");
        if (!"LOADING".equals(run.get("status"))) {
            throw ApiException.conflict("This load was already accepted");
        }
        if (run.get("loadedAt") == null) {
            throw ApiException.bad("The store has not saved the load yet");
        }
        Object seen = body.get("loadedAt");
        if (seen == null || seen.toString().isBlank()) {
            throw ApiException.bad("Open the round again so you see what was loaded, then accept");
        }
        boolean same;
        try {
            same = java.time.Instant.parse(seen.toString()).equals(java.time.Instant.parse(run.get("loadedAt").toString()));
        } catch (RuntimeException e) {
            same = false;
        }
        if (!same) {
            throw ApiException.conflict("The store changed the load. Check it again before you accept.");
        }
        if (TenantContext.userId().toString().equals(run.get("loadedBy"))) {
            throw ApiException.bad("Someone else must load the vehicle. The driver cannot load and accept his own load");
        }
        TenantContext.Caller me = TenantContext.caller();
        int n = db.exec("""
                UPDATE delivery_runs SET status = 'OUT', accepted_by_name = :n, accepted_at = now()
                WHERE id = :id AND status = 'LOADING'""", "n", me.name(), "id", runId);
        if (n == 0) {
            throw ApiException.conflict("This load was already accepted");
        }
        activity.record("LOAD_ACCEPTED", ActivityLog.INFO,
                me.name() + " accepted the load loaded by " + run.get("loadedByName") + " for the round of " + run.get("runDate"));
        return detail(runId);
    }

    public Map<String, Object> back(UUID runId) {
        Map<String, Object> run = runRowLock(runId);
        boolean driver = "RIDER".equals(TenantContext.caller().role());
        if (driver) {
            requireDriver(run, "close this round");
        }
        if ("BACK".equals(run.get("status"))) {
            throw ApiException.conflict("This round is already closed");
        }
        if ("LOADING".equals(run.get("status"))) {
            // Never went out: a plain cancel. Nothing was delivered, so nothing counts as missing.
            db.exec("UPDATE delivery_stops SET status = 'CANCELLED', token_hash = NULL, code_hash = NULL WHERE run_id = :r", "r", runId);
            db.exec("DELETE FROM delivery_links WHERE stop_id IN (SELECT id FROM delivery_stops WHERE run_id = :r)", "r", runId);
            db.exec("UPDATE delivery_runs SET status = 'BACK', back_at = now() WHERE id = :id", "id", runId);
            activity.record("ROUND_CANCELLED", ActivityLog.INFO,
                    TenantContext.caller().name() + " cancelled the round of " + run.get("runDate") + " before it went out");
            return detail(runId);
        }
        List<Map<String, Object>> open = db.list("""
                SELECT id, sort, note_no, customer_name, order_id FROM delivery_stops
                WHERE run_id = :r AND status IN ('PENDING', 'EMAILED') ORDER BY sort FOR UPDATE""", "r", runId);
        if (!open.isEmpty() && driver) {
            List<String> nums = open.stream().map(o -> String.valueOf(((Number) o.get("sort")).intValue() + 1)).toList();
            throw ApiException.conflict((nums.size() == 1 ? "Stop " + nums.get(0) + " is" : "Stops " + String.join(" and ", nums) + " are")
                    + " not confirmed yet. Ask the office to close the round.");
        }
        TenantContext.Caller me = TenantContext.caller();
        for (Map<String, Object> o : open) {
            UUID id = uuid(o.get("id"));
            db.exec("""
                    UPDATE delivery_stops SET status = 'MISSED', token_hash = NULL, code_hash = NULL WHERE id = :s""", "s", id);
            db.exec("DELETE FROM delivery_links WHERE stop_id = :s", "s", id);
            db.exec("UPDATE delivery_stop_lines SET qty_received = 0, qty_sent_back = 0 WHERE stop_id = :s", "s", id);
            activity.record("DELIVERY_MISSED", ActivityLog.CONCERN,
                    me.name() + " closed the round with " + noteNo(o.get("noteNo")) + " to " + o.get("customerName")
                            + " not confirmed by the client. Its goods count as missing.",
                    uuid(o.get("orderId")), null, null, null, Map.of("noteNo", noteNo(o.get("noteNo")), "driver", run.get("driverName")));
        }
        db.exec("UPDATE delivery_runs SET status = 'BACK', back_at = now() WHERE id = :id", "id", runId);
        return detail(runId);
    }

    // ---------------- detail ----------------

    public Map<String, Object> get(UUID runId) {
        Map<String, Object> run = runRow(runId);
        if (!sees()) {
            requireDriver(run, "see this round");
        }
        return detail(runId);
    }

    public List<Map<String, Object>> mine() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.list("""
                SELECT id FROM delivery_runs WHERE driver_user_id = :u AND status <> 'BACK' ORDER BY run_date, created_at""")) {
            out.add(detail(uuid(r.get("id"))));
        }
        return out;
    }

    Map<String, Object> detail(UUID runId) {
        Map<String, Object> r = runRow(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("id", "runDate", "vehicle", "driverUserId", "driverName", "status", "loadedByName", "loadedAt", "acceptedByName", "acceptedAt")) {
            out.put(k, r.get(k));
        }
        List<Map<String, Object>> load = new ArrayList<>();
        List<Map<String, Object>> runLines = db.list("SELECT * FROM delivery_run_lines WHERE run_id = :r ORDER BY name, id", "r", runId);
        for (Map<String, Object> l : runLines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", l.get("itemId"));
            m.put("name", l.get("name"));
            m.put("qtyPlanned", num(l.get("qtyPlanned")));
            m.put("qtyLoaded", l.get("qtyLoaded") == null ? null : num(l.get("qtyLoaded")));
            load.add(m);
        }
        out.put("load", load);
        List<Map<String, Object>> stops = db.list("""
                SELECT s.*, o.number AS order_number, o.status AS order_status FROM delivery_stops s JOIN orders o ON o.id = s.order_id
                WHERE s.run_id = :r ORDER BY s.sort""", "r", runId);
        List<Map<String, Object>> allLines = db.list("""
                SELECT * FROM delivery_stop_lines WHERE stop_id IN (SELECT id FROM delivery_stops WHERE run_id = :r) ORDER BY pos""", "r", runId);
        boolean allDone = !stops.isEmpty();
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        List<Map<String, Object>> stopOut = new ArrayList<>();
        for (Map<String, Object> s : stops) {
            boolean done = isResolved(s.get("status"));
            allDone &= done;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.get("id"));
            m.put("sort", s.get("sort"));
            m.put("noteNo", noteNo(s.get("noteNo")));
            m.put("orderId", s.get("orderId"));
            m.put("orderNumber", s.get("orderNumber"));
            m.put("orderStatus", s.get("orderStatus"));
            m.put("customerName", s.get("customerName"));
            m.put("email", s.get("confirmEmail"));
            m.put("emailSetBy", s.get("emailSetBy"));
            m.put("status", s.get("status"));
            m.put("emailedAt", s.get("emailedAt"));
            m.put("sends", s.get("sends"));
            m.put("receivedByName", s.get("receivedByName"));
            m.put("confirmedAt", s.get("confirmedAt"));
            m.put("confirmedVia", s.get("confirmedVia"));
            m.put("cashCollected", s.get("cashCollected") == null ? null : num(s.get("cashCollected")));
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> l : allLines) {
                if (!l.get("stopId").equals(s.get("id"))) {
                    continue;
                }
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("lineId", l.get("id"));
                x.put("itemId", l.get("itemId"));
                x.put("name", l.get("name"));
                x.put("qtySent", num(l.get("qtySent")));
                x.put("qtyReceived", l.get("qtyReceived") == null ? null : num(l.get("qtyReceived")));
                x.put("qtySentBack", num(l.get("qtySentBack")));
                x.put("reason", l.get("reason"));
                lines.add(x);
                if (done && l.get("itemId") != null) {
                    BigDecimal[] sum = sums.computeIfAbsent(l.get("itemId").toString(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    sum[0] = sum[0].add(l.get("qtyReceived") == null ? BigDecimal.ZERO : (BigDecimal) l.get("qtyReceived"));
                    sum[1] = sum[1].add((BigDecimal) l.get("qtySentBack"));
                }
            }
            m.put("lines", lines);
            stopOut.add(m);
        }
        out.put("stops", stopOut);
        List<Map<String, Object>> totals = new ArrayList<>();
        for (Map<String, Object> l : runLines) {
            BigDecimal[] sum = sums.getOrDefault(l.get("itemId").toString(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("itemId", l.get("itemId"));
            t.put("name", l.get("name"));
            t.put("loaded", l.get("qtyLoaded") == null ? null : num(l.get("qtyLoaded")));
            t.put("received", sum[0].doubleValue());
            t.put("sentBack", sum[1].doubleValue());
            t.put("missing", allDone && l.get("qtyLoaded") != null
                    ? ((BigDecimal) l.get("qtyLoaded")).subtract(sum[0]).subtract(sum[1]).doubleValue() : null);
            totals.add(t);
        }
        out.put("totals", totals);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stopDetail(UUID stopId) {
        UUID run = uuid(db.one("Stop", "SELECT run_id FROM delivery_stops WHERE id = :id", "id", stopId).get("runId"));
        for (Map<String, Object> s : (List<Map<String, Object>>) detail(run).get("stops")) {
            if (stopId.toString().equals(s.get("id"))) {
                return s;
            }
        }
        throw ApiException.notFound("Stop");
    }

    // ---------------- at the door ----------------

    /** The driver is at the client's door: email the clerk a link and a 4 digit code. A resend replaces both. */
    public Map<String, Object> arrived(UUID stopId, Map<String, Object> body) {
        Map<String, Object> s = stopForDriver(stopId);
        Map<String, Object> run = runRow(uuid(s.get("runId")));
        if (!"OUT".equals(run.get("status"))) {
            throw ApiException.bad("Accept the load first. The round is not out yet");
        }
        if (!"PENDING".equals(s.get("status")) && !"EMAILED".equals(s.get("status"))) {
            throw ApiException.conflict("This delivery is already closed. It cannot be sent again");
        }
        if (((Number) s.get("sends")).intValue() >= MAX_SENDS) {
            throw ApiException.bad("The email has been sent " + MAX_SENDS + " times already. Ask the client to check their inbox, or ask the office to help");
        }
        // The driver never chooses where the email goes: the address is the one the office has on the stop.
        String email = (String) s.get("confirmEmail");
        if (email == null || email.isBlank()) {
            throw ApiException.bad("This client has no email yet. Ask the office to add one.");
        }
        String token = newToken();
        String code = String.format("%04d", RANDOM.nextInt(10000));
        String link = mailer.appUrl() + "/confirm-delivery/" + token;
        TenantContext.Caller me = TenantContext.caller();
        String business = db.value("SELECT name FROM tenants WHERE id = :t", String.class);
        String dn = noteNo(s.get("noteNo"));
        String who = run.get("driverName") + (run.get("vehicle") == null ? "" : " (" + run.get("vehicle") + ")");
        String text = "Hello,\n\n" + who + " is delivering your order from " + business + " (delivery note " + dn + ").\n\n"
                + "Please confirm what you received:\n" + link + "\n\n"
                + "No phone at the door? Read this code to the driver: " + code + "\n\n"
                + "If something is short or damaged, say so on the page before you sign.";
        String color = brands.brand(me.tenantId()).color();
        String html = EmailLayout.h("Your delivery " + dn, null)
                + EmailLayout.p(Mailer.esc(who) + " is delivering your order from <b>" + Mailer.esc(business) + "</b>.")
                + EmailLayout.button("Confirm what you received", link, color)
                + EmailLayout.p("No phone at the door? Read this code to the driver:")
                + EmailLayout.code(code, null)
                + EmailLayout.safety("If something is short or damaged, say so on the page before you sign.");
        String problem = mailer.trySendAs(me.tenantId(), email, (String) s.get("customerName"),
                "Confirm your delivery " + dn + " from " + business, text, html);
        Map<String, Object> out = new LinkedHashMap<>();
        if (problem != null) {
            out.put("emailed", false);
            out.put("message", "The email did not go out (" + problem + "). Check the address and try again.");
            out.put("stop", stopDetail(stopId));
            return out;
        }
        // The stop row stays locked from the start of this call, so a clerk's confirmation can never be undone by a resend.
        int changed = db.exec("""
                UPDATE delivery_stops SET status = 'EMAILED', token_hash = :h, code_hash = :c, code_tries = 0, sends = sends + 1,
                       emailed_at = now(), expires_at = now() + interval '3 days'
                WHERE id = :s AND status IN ('PENDING', 'EMAILED')""", "h", sha(token), "c", codeHash(stopId, code), "s", stopId);
        if (changed == 0) {
            throw ApiException.conflict("This delivery is already closed. It cannot be sent again");
        }
        db.exec("DELETE FROM delivery_links WHERE stop_id = :s", "s", stopId);
        db.exec("INSERT INTO delivery_links (token_hash, tenant_id, stop_id, expires_at) VALUES (:h, :t, :s, now() + interval '3 days')",
                "h", sha(token), "s", stopId);
        out.put("emailed", true);
        out.put("message", "We emailed " + email + ". The clerk can confirm on the link, or read you the code from the email.");
        out.put("stop", stopDetail(stopId));
        return out;
    }

    /** 32 random bytes as base64url. One with a capital letter or underscore, so a link can never be mistaken for a business slug. */
    /** The office sets where a stop's confirm email goes. Any link and code already sent stop working. */
    public Map<String, Object> setEmail(UUID stopId, Map<String, Object> body) {
        TenantContext.requireRole("MANAGER");
        Map<String, Object> s = db.one("Stop", "SELECT * FROM delivery_stops WHERE id = :id FOR UPDATE", "id", stopId);
        if (!"PENDING".equals(s.get("status")) && !"EMAILED".equals(s.get("status"))) {
            throw ApiException.conflict("This delivery is already closed, so the address cannot change");
        }
        String email = body.get("email") == null ? "" : body.get("email").toString().trim();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 200) {
            throw ApiException.bad("Type a proper email address");
        }
        if (isStaffEmail(email)) {
            throw ApiException.bad("That address belongs to one of your staff");
        }
        String customerEmail = db.value("""
                SELECT c.email FROM delivery_stops s JOIN orders o ON o.id = s.order_id JOIN customers c ON c.id = o.customer_id
                WHERE s.id = :s""", String.class, "s", stopId);
        TenantContext.Caller me = TenantContext.caller();
        db.exec("""
                UPDATE delivery_stops SET confirm_email = :e, email_set_by = :by, status = 'PENDING', token_hash = NULL, code_hash = NULL,
                       code_tries = 0, expires_at = NULL WHERE id = :s""", "e", email, "by", me.name(), "s", stopId);
        db.exec("DELETE FROM delivery_links WHERE stop_id = :s", "s", stopId);
        if (customerEmail == null || !normEmail(customerEmail).equals(normEmail(email))) {
            activity.record("EMAIL_CHANGED", ActivityLog.CONCERN,
                    me.name() + " set the confirm email for " + noteNo(s.get("noteNo")) + " to " + email
                            + (customerEmail == null ? " (the customer has none on file)" : " (the customer's is " + customerEmail + ")"),
                    uuid(s.get("orderId")), null, null, null, Map.of("noteNo", noteNo(s.get("noteNo")), "email", email));
        }
        return stopDetail(stopId);
    }

    private static String newToken() {
        while (true) {
            byte[] b = new byte[32];
            RANDOM.nextBytes(b);
            String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            if (t.chars().anyMatch(c -> (c >= 'A' && c <= 'Z') || c == '_')) {
                return t;
            }
        }
    }

    /** The clerk read the code to the driver: everything counts as received in full. */
    public CodeResult code(UUID stopId, Map<String, Object> body) {
        Map<String, Object> s = stopForDriver(stopId);
        if (isDone(s.get("status"))) {
            throw ApiException.conflict("The client has already confirmed this delivery");
        }
        if (!"EMAILED".equals(s.get("status")) || s.get("codeHash") == null) {
            throw ApiException.bad("Send the email to the client first");
        }
        BigDecimal cash = bd(body.get("cashCollected"), "The cash collected");
        if (s.get("expiresAt") == null || java.time.Instant.parse(s.get("expiresAt").toString()).isBefore(java.time.Instant.now())) {
            return new CodeResult(false, "This code has expired. Send the email again.", null);
        }
        String given = body.get("code") == null ? "" : body.get("code").toString().trim();
        // The try is counted in the same statement that checks the limit, so parallel guesses cannot get past the lock.
        int n = db.exec("UPDATE delivery_stops SET code_tries = code_tries + 1 WHERE id = :s AND status = 'EMAILED' AND code_tries < :max",
                "s", stopId, "max", MAX_TRIES);
        if (n == 0) {
            return new CodeResult(false, "Too many wrong codes. Send the email again to get a new code.", null);
        }
        boolean right = given.matches("\\d{4}") && same(codeHash(stopId, given), (String) s.get("codeHash"));
        if (!right) {
            int left = MAX_TRIES - 1 - ((Number) s.get("codeTries")).intValue();
            return new CodeResult(false, left > 0 ? "That code is not right. You have " + left + (left == 1 ? " try" : " tries") + " left."
                    : "Too many wrong codes. Send the email again to get a new code.", null);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : db.list("SELECT id, qty_sent FROM delivery_stop_lines WHERE stop_id = :s", "s", stopId)) {
            lines.add(Map.of("id", uuid(l.get("id")), "received", l.get("qtySent"), "sentBack", BigDecimal.ZERO, "reason", ""));
        }
        confirm(s, lines, "CODE", "Client, by code", null, cash, null);
        return new CodeResult(true, "Confirmed. Everything counted as received.", stopDetail(stopId));
    }

    public Map<String, Object> cash(UUID stopId, Map<String, Object> body) {
        Map<String, Object> s = stopForDriver(stopId);
        if (!isDone(s.get("status"))) {
            throw ApiException.bad("The client has not confirmed this delivery yet");
        }
        BigDecimal cash = bd(body.get("cashCollected"), "The cash collected");
        if (cash == null) {
            throw ApiException.bad("Type how much cash you collected");
        }
        int n = db.exec("UPDATE delivery_stops SET cash_collected = :c WHERE id = :s AND cash_collected IS NULL", "c", cash, "s", stopId);
        if (n == 0) {
            throw ApiException.conflict("The cash was already written down for this delivery");
        }
        return stopDetail(stopId);
    }

    // ---------------- the clerk's page (no login) ----------------

    private Map<String, Object> stopOfLink(String token) {
        return db.find("""
                SELECT s.*, o.number AS order_number, r.driver_name, r.vehicle
                FROM delivery_stops s JOIN delivery_runs r ON r.id = s.run_id JOIN orders o ON o.id = s.order_id
                WHERE s.token_hash = :h AND s.expires_at > now()""", "h", sha(token))
                .orElseThrow(() -> ApiException.notFound("This delivery link"));
    }

    public Map<String, Object> publicView(String token) {
        Map<String, Object> s = stopOfLink(token);
        UUID id = uuid(s.get("id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supplierName", db.value("SELECT name FROM tenants WHERE id = :t", String.class));
        out.put("customerName", s.get("customerName"));
        out.put("noteNo", noteNo(s.get("noteNo")));
        out.put("orderNumber", s.get("orderNumber"));
        out.put("driverName", s.get("driverName"));
        out.put("vehicle", s.get("vehicle"));
        out.put("emailedAt", s.get("emailedAt"));
        out.put("status", s.get("status"));
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> got = new ArrayList<>();
        for (Map<String, Object> l : db.list("SELECT * FROM delivery_stop_lines WHERE stop_id = :s ORDER BY pos", "s", id)) {
            lines.add(Map.of("lineId", l.get("id"), "name", l.get("name"), "qtySent", num(l.get("qtySent"))));
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("lineId", l.get("id"));
            g.put("qtyReceived", l.get("qtyReceived") == null ? null : num(l.get("qtyReceived")));
            g.put("qtySentBack", num(l.get("qtySentBack")));
            g.put("reason", l.get("reason"));
            got.add(g);
        }
        out.put("lines", lines);
        if (isDone(s.get("status"))) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("receivedByName", s.get("receivedByName"));
            c.put("confirmedAt", s.get("confirmedAt"));
            c.put("lines", got);
            out.put("confirmed", c);
        } else {
            out.put("confirmed", null);
        }
        return out;
    }

    /** The clerk's confirmation. Once only: the first one to reach the database wins and the rest get a 409. */
    public Map<String, Object> publicConfirm(String token, Map<String, Object> body) {
        Map<String, Object> s = stopOfLink(token);
        UUID stopId = uuid(s.get("id"));
        if (!"EMAILED".equals(s.get("status"))) {
            throw ApiException.conflict("This delivery was already confirmed. It cannot be changed.");
        }
        String name = body.get("receivedByName") == null ? "" : body.get("receivedByName").toString().trim();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiException.bad("Type your name (up to 80 letters)");
        }
        if (!Boolean.TRUE.equals(body.get("agreed"))) {
            throw ApiException.bad("Tick the box to say this is what you received");
        }
        String sig = body.get("signature") == null ? "" : body.get("signature").toString();
        if (sig.isEmpty()) {
            throw ApiException.bad("Please sign on the page");
        }
        String prefix = "data:image/png;base64,";
        if (sig.length() > MAX_SIGNATURE || !sig.startsWith(prefix)) {
            throw ApiException.bad("The signature is not a small PNG picture. Clear it and sign again");
        }
        try {
            byte[] png = Base64.getDecoder().decode(sig.substring(prefix.length()));
            if (png.length < 8 || (png[0] & 0xff) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.bad("The signature is not a small PNG picture. Clear it and sign again");
        }
        Map<UUID, Map<String, Object>> sent = new LinkedHashMap<>();
        for (Map<String, Object> l : db.list("SELECT id, name, qty_sent FROM delivery_stop_lines WHERE stop_id = :s", "s", stopId)) {
            sent.put(uuid(l.get("id")), l);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        if (body.get("lines") instanceof List<?> in) {
            for (Object x : in) {
                if (!(x instanceof Map<?, ?> m) || m.get("lineId") == null) {
                    throw ApiException.bad("A line is missing its id");
                }
                UUID id;
                try {
                    id = UUID.fromString(m.get("lineId").toString());
                } catch (IllegalArgumentException e) {
                    throw ApiException.bad("A line has a wrong id");
                }
                Map<String, Object> line = sent.get(id);
                if (line == null || !seen.add(id)) {
                    throw ApiException.bad("A line is not on this delivery note, or is listed twice");
                }
                BigDecimal got = bd(m.get("qtyReceived"), "The amount received for " + line.get("name"));
                BigDecimal back = bd(m.get("qtySentBack"), "The amount sent back for " + line.get("name"));
                got = got == null ? BigDecimal.ZERO : got;
                back = back == null ? BigDecimal.ZERO : back;
                if (got.add(back).compareTo((BigDecimal) line.get("qtySent")) > 0) {
                    throw ApiException.bad("For " + line.get("name") + " you cannot have received and sent back more than the "
                            + qty((BigDecimal) line.get("qtySent")) + " that was sent");
                }
                String reason = m.get("reason") == null ? "" : m.get("reason").toString().trim();
                if (back.signum() > 0 && reason.isEmpty()) {
                    throw ApiException.bad("Say why " + line.get("name") + " was sent back");
                }
                if (reason.length() > 200) {
                    throw ApiException.bad("The reason for " + line.get("name") + " is too long");
                }
                lines.add(Map.of("id", id, "received", got, "sentBack", back, "reason", reason));
            }
        }
        if (seen.size() != sent.size()) {
            throw ApiException.bad("Fill in every line of the delivery note");
        }
        confirm(s, lines, "LINK", name, sig, null, sha(token));
        String status = db.value("SELECT status FROM delivery_stops WHERE id = :s", String.class, "s", stopId);
        return Map.of("status", status, "message", "Thank you. Your confirmation is saved.");
    }

    // ---------------- confirming (link or code) ----------------

    /**
     * Writes what was received, once. The status change comes first and only works on a stop still waiting, so a second
     * confirmation (or two at the same moment) changes nothing and gets a 409. Then each order line is set to what was
     * received through the order's own recalculation, so the invoice follows what the client confirmed.
     */
    private void confirm(Map<String, Object> stop, List<Map<String, Object>> lines, String via, String receivedBy, String signature, BigDecimal cash,
                         String linkHash) {
        UUID stopId = uuid(stop.get("id"));
        // Locked first: a resend, an address change or a second confirmation waits here and then finds the stop already settled.
        db.value("SELECT 1 FROM delivery_stops WHERE id = :s FOR UPDATE", Integer.class, "s", stopId);
        Map<UUID, Map<String, Object>> sent = new LinkedHashMap<>();
        for (Map<String, Object> l : db.list("""
                SELECT sl.id, sl.name, sl.qty_sent, sl.order_line_id, sl.item_id, ol.unit_price, coalesce(ol.pack_size, 1) AS pack_size
                FROM delivery_stop_lines sl LEFT JOIN order_lines ol ON ol.id = sl.order_line_id
                WHERE sl.stop_id = :s ORDER BY sl.pos""", "s", stopId)) {
            sent.put(uuid(l.get("id")), l);
        }
        boolean part = false;
        for (Map<String, Object> l : lines) {
            BigDecimal full = (BigDecimal) sent.get((UUID) l.get("id")).get("qtySent");
            part |= ((BigDecimal) l.get("received")).compareTo(full) < 0;
        }
        int n = db.exec("""
                UPDATE delivery_stops SET status = :st, confirmed_at = now(), confirmed_via = :via, received_by_name = :by,
                       signature = :sig, cash_collected = coalesce(:cash, cash_collected)
                WHERE id = :s AND status = 'EMAILED' AND (cast(:h as text) IS NULL OR token_hash = :h)""",
                "h", linkHash, "st", part ? "PART" : "CONFIRMED", "via", via, "by", receivedBy, "sig", signature, "cash", cash, "s", stopId);
        if (n == 0) {
            throw ApiException.conflict("This delivery was already confirmed. It cannot be changed.");
        }
        UUID orderId = uuid(stop.get("orderId"));
        // The order row is locked until this ends, so a payment at the same moment waits and then sees the new total.
        Map<String, Object> order = db.one("Order", "SELECT number, status, total, customer_id FROM orders WHERE id = :o FOR UPDATE", "o", orderId);
        boolean realOpen = "OPEN".equals(order.get("status"));
        // An unpaid account order: still CREDIT, and nobody has paid anything but the account itself.
        BigDecimal otherPaid = db.value("SELECT coalesce(sum(amount), 0) FROM payments WHERE order_id = :o AND method <> 'CREDIT'", BigDecimal.class, "o", orderId);
        boolean onAccount = "CREDIT".equals(order.get("status")) && order.get("customerId") != null && otherPaid.signum() == 0;
        boolean open = realOpen || onAccount;
        BigDecimal totalBefore = (BigDecimal) order.get("total");
        String dn = noteNo(stop.get("noteNo"));
        String driver = db.value("SELECT driver_name FROM delivery_runs WHERE id = :r", String.class, "r", stop.get("runId"));
        String client = (String) stop.get("customerName");
        UUID where = db.value("SELECT location_id FROM orders WHERE id = :o", UUID.class, "o", orderId);
        UUID stockAt = where == null ? stock.defaultLocation() : where;
        boolean closed = "PAID".equals(order.get("status")) || "CREDIT".equals(order.get("status"));
        BigDecimal unbilled = BigDecimal.ZERO;
        List<String> unbilledLines = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            UUID id = (UUID) l.get("id");
            Map<String, Object> was = sent.get(id);
            BigDecimal full = (BigDecimal) was.get("qtySent");
            BigDecimal got = (BigDecimal) l.get("received");
            BigDecimal back = (BigDecimal) l.get("sentBack");
            String reason = (String) l.get("reason");
            db.exec("UPDATE delivery_stop_lines SET qty_received = :r, qty_sent_back = :b, reason = :why WHERE id = :id",
                    "r", got, "b", back, "why", reason.isEmpty() ? null : reason, "id", id);
            BigDecimal shortBy = full.subtract(got);
            if (shortBy.signum() <= 0) {
                continue;
            }
            // Goods that left on the vehicle are gone from stock unless they came back. An open order's own sale takes off
            // only what is on its lowered lines (what was received), so what went missing is taken off here. A closed order
            // already took the full amount off, so only what was sent back goes in again.
            if (was.get("itemId") != null) {
                BigDecimal pack = (BigDecimal) was.get("packSize");
                if (realOpen && shortBy.subtract(back).signum() > 0) {
                    stock.move(uuid(was.get("itemId")), stockAt, shortBy.subtract(back).multiply(pack).negate(), "DELIVERY_SHORT", dn, "Missing on delivery " + dn, true);
                } else if (closed && back.signum() > 0) {
                    stock.move(uuid(was.get("itemId")), stockAt, back.multiply(pack), "DELIVERY_SENT_BACK", dn, "Sent back on delivery " + dn, true);
                }
            }
            UUID itemId = uuid(was.get("itemId"));
            BigDecimal price = was.get("unitPrice") == null ? BigDecimal.ZERO : (BigDecimal) was.get("unitPrice");
            BigDecimal missing = shortBy.subtract(back);
            if (missing.signum() > 0) {
                activity.record("DELIVERY_SHORT", ActivityLog.CONCERN,
                        "Short on " + dn + " to " + client + ": " + qty(got) + " of " + qty(full) + " x " + was.get("name") + " received, "
                                + qty(missing) + " not accounted for (driver " + driver + ", confirmed by " + receivedBy + ", KES "
                                + missing.multiply(price).setScale(0, RoundingMode.HALF_UP) + ")",
                        orderId, itemId, missing.multiply(price), null,
                        Map.of("noteNo", dn, "driver", driver, "qtySent", full, "qtyReceived", got, "qtySentBack", back));
            }
            if (back.signum() > 0) {
                activity.record("DELIVERY_SENT_BACK", ActivityLog.NOTICE,
                        client + " sent back " + qty(back) + " x " + was.get("name") + " on " + dn + " (" + reason + "; driver "
                                + driver + ", confirmed by " + receivedBy + ")",
                        orderId, itemId, back.multiply(price), null, Map.of("noteNo", dn, "driver", driver, "reason", reason));
            }
            if (open && was.get("orderLineId") != null) {
                orders.setDeliveredQty(orderId, uuid(was.get("orderLineId")), got, " because " + client + " received less on " + dn);
            } else if (!open) {
                unbilled = unbilled.add(shortBy.multiply(price));
                unbilledLines.add(qty(shortBy) + " x " + was.get("name"));
            }
        }
        if (onAccount) {
            BigDecimal drop = totalBefore.subtract(db.value("SELECT total FROM orders WHERE id = :o", BigDecimal.class, "o", orderId));
            if (drop.signum() > 0) {
                UUID customerId = uuid(order.get("customerId"));
                // The account goes down by exactly what the bill went down by, through the customer ledger.
                db.value("SELECT 1 FROM customers WHERE id = :c FOR UPDATE", Integer.class, "c", customerId);
                db.exec("UPDATE customers SET balance = balance - :d WHERE id = :c", "c", customerId, "d", drop);
                customerLedger.record(customerId, "REFUND", drop.negate(), orderId, null, null,
                        "Delivery " + dn + " short, confirmed by " + receivedBy);
                db.exec("""
                        INSERT INTO payments (tenant_id, order_id, method, amount, kind, refund_reason, user_id)
                        VALUES (:t, :o, 'CREDIT', :a, 'ADJUST', :why, cast(:u as uuid))""",
                        "o", orderId, "a", drop.negate(), "why", "Delivery " + dn + " short");
                db.exec("UPDATE orders SET paid = greatest(paid - :d, 0) WHERE id = :o", "d", drop, "o", orderId);
                // eTIMS signs one credit note for the whole bill as it stands, so a drop after KRA signed the sale needs one by hand.
                // A sale still waiting in the queue is sent from the lowered lines, so it needs nothing.
                if ("SIGNED".equals(db.value("SELECT etims_status FROM orders WHERE id = :o", String.class, "o", orderId))) {
                    activity.record("DELIVERY_ETIMS_CREDIT", ActivityLog.CONCERN,
                            "Order " + order.get("number") + " went down by KES " + drop.setScale(0, RoundingMode.HALF_UP) + " after delivery " + dn
                                    + " was confirmed short. Make a credit note in eTIMS for KES " + drop.setScale(0, RoundingMode.HALF_UP) + ".",
                            orderId, null, drop, null, Map.of("noteNo", dn));
                }
            }
        }
        if (realOpen) {
            Map<String, Object> now = db.one("Order", "SELECT total, paid FROM orders WHERE id = :o", "o", orderId);
            BigDecimal paid = (BigDecimal) now.get("paid");
            BigDecimal total = (BigDecimal) now.get("total");
            if (paid.compareTo(total) > 0) {
                BigDecimal extra = paid.subtract(total);
                activity.record("DELIVERY_OVERPAID", ActivityLog.CONCERN,
                        "Order " + order.get("number") + ": the client already paid KES " + paid.setScale(0, RoundingMode.HALF_UP)
                                + ", the confirmed goods come to KES " + total.setScale(0, RoundingMode.HALF_UP) + ". Give back or credit KES "
                                + extra.setScale(0, RoundingMode.HALF_UP) + ".",
                        orderId, null, extra, null, Map.of("noteNo", dn, "paid", paid, "total", total));
            }
        }
        if (!unbilledLines.isEmpty()) {
            activity.record("DELIVERY_NOT_BILLED_RIGHT", ActivityLog.CONCERN,
                    "Order " + order.get("number") + " was already paid or closed when " + receivedBy + " confirmed " + dn + ": short "
                            + String.join(", ", unbilledLines) + " (KES " + unbilled.setScale(0, RoundingMode.HALF_UP) + "). Make a credit or refund.",
                    orderId, null, unbilled, null, Map.of("noteNo", dn, "short", unbilledLines));
        }
    }
}
