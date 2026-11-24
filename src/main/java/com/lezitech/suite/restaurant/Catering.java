package com.lezitech.suite.restaurant;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.ApprovalService;
import com.lezitech.suite.sales.ManagerRequestService;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Catering orders: an event (who, when, where, how many people), the menu with quantities per person or in total and a
 * price each that can be changed, and a deposit. The money lives on an ordinary bill that opens with the first payment
 * (or on the event day), so every payment shows in the day's takings and reports. Cancelling an order with money paid
 * is a manager's call: anyone else asks, through "Waiting for you".
 */
@Service
public class Catering {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> STATUSES = List.of("BOOKED", "PREPARING", "DELIVERED");
    private static final List<String> METHODS = List.of("CASH", "MPESA", "CARD");

    private final Db db;
    private final OrderService orders;
    private final ManagerRequestService requests;
    private final ActivityLog activity;
    private final ApprovalService approvals;
    private final TransactionTemplate nested;

    public Catering(Db db, OrderService orders, ManagerRequestService requests, ActivityLog activity, ApprovalService approvals,
                    PlatformTransactionManager txm) {
        this.approvals = approvals;
        this.nested = new TransactionTemplate(txm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.db = db;
        this.orders = orders;
        this.requests = requests;
        this.activity = activity;
    }

    private static String clip(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }

    private static Instant epoch(Object seconds) {
        return Instant.ofEpochMilli(Math.round(((Number) seconds).doubleValue() * 1000));
    }

    private static String token() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static void staffOnly() {
        TenantContext.requireRole("MANAGER", "CASHIER");
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    // ---------------- booking ----------------

    /**
     * {name, phone, date, time, place, people, notes, lines:[{itemId, qty, perPerson, unitPrice?}], deposit?, depositMethod?,
     * depositReference?}. Lines are priced from the menu unless a price each is given.
     */
    public Map<String, Object> place(Body b) {
        staffOnly();
        String name = clip(b.str("name", "").trim(), 80);
        if (name.isEmpty()) {
            throw ApiException.bad("Type the customer's name");
        }
        String phone = TableOrderService.phone(b.str("phone", null));
        if (phone == null) {
            throw ApiException.bad("Type a phone number we can call about the order");
        }
        int people = b.integer("people", 0);
        if (people < 1 || people > 100000) {
            throw ApiException.bad("Type how many people it is for");
        }
        LocalDate date;
        LocalTime time;
        try {
            date = LocalDate.parse(b.str("date"));
            time = LocalTime.parse(b.str("time", "12:00"));
        } catch (Exception e) {
            throw ApiException.bad("Choose the day and time of the event");
        }
        List<Map<String, Object>> in = b.list("lines");
        if (in == null || in.isEmpty()) {
            throw ApiException.bad("Add at least one dish to the menu");
        }
        List<Object[]> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> raw : in) {
            Body l = new Body(raw);
            BigDecimal qty = l.num("qty", null);
            if (qty == null || qty.signum() <= 0) {
                throw ApiException.bad("Every dish needs a quantity");
            }
            Map<String, Object> item = db.one("Menu item", "SELECT id, name, price, kind FROM items WHERE id = :id", "id", l.str("itemId", ""));
            BigDecimal price = l.has("unitPrice") ? l.num("unitPrice") : (BigDecimal) item.get("price");
            if (price.signum() < 0) {
                throw ApiException.bad("A price can't be below zero");
            }
            if (price.compareTo((BigDecimal) item.get("price")) != 0) {
                // A price other than the menu's is a manager's call, as at the till (or a manager's PIN).
                String by = approvals.require(b, "a catering price different from the menu price of " + item.get("name"));
                activity.record("CATERING_PRICE", ActivityLog.NOTICE, item.get("name") + " priced at " + plain(price) + " instead of "
                        + plain((BigDecimal) item.get("price")) + " on a catering order", null, UUID.fromString(item.get("id").toString()),
                        price, by, Map.of("menu", item.get("price"), "agreed", price));
            }
            boolean per = l.bool("perPerson", false);
            BigDecimal lineTotal = qty.multiply(per ? BigDecimal.valueOf(people) : BigDecimal.ONE).multiply(price).setScale(2, RoundingMode.HALF_UP);
            total = total.add(lineTotal);
            lines.add(new Object[]{UUID.fromString(item.get("id").toString()), item.get("name"), per, qty, price, lineTotal});
        }
        if (total.signum() <= 0) {
            throw ApiException.bad("The order comes to nothing. Check the prices.");
        }
        BigDecimal deposit = b.num("deposit", BigDecimal.ZERO);
        if (deposit.signum() < 0 || deposit.compareTo(total) > 0) {
            throw ApiException.bad("The deposit can't be more than the total of " + plain(total));
        }
        Instant at = ZonedDateTime.of(date, time, NAIROBI).toInstant();
        UUID id = db.insert("""
                INSERT INTO catering_orders (tenant_id, token, name, phone, event_at, place, people, notes, total, created_by)
                VALUES (:t, :tk, :name, :phone, :at, :place, :people, :notes, :total, :u) RETURNING id""",
                "tk", token(), "name", name, "phone", phone, "at", Timestamp.from(at), "place", clip(b.str("place", "").trim(), 300),
                "people", people, "notes", clip(b.str("notes", null), 500), "total", total);
        for (Object[] l : lines) {
            db.exec("""
                    INSERT INTO catering_lines (tenant_id, catering_id, item_id, name, per_person, qty, unit_price, line_total)
                    VALUES (:t, :c, :item, :name, :per, :qty, :price, :lt)""",
                    "c", id, "item", l[0], "name", clip((String) l[1], 200), "per", l[2], "qty", l[3], "price", l[4], "lt", l[5]);
        }
        activity.record("CATERING_BOOKED", ActivityLog.INFO, "Catering order for " + name + " on " + DAY.format(at.atZone(NAIROBI))
                + ", " + people + " people, KES " + plain(total));
        if (deposit.signum() > 0) {
            Map<String, Object> dep = new HashMap<>();
            dep.put("amount", deposit);
            dep.put("method", b.str("depositMethod", "CASH"));
            if (b.has("depositReference")) {
                dep.put("reference", b.str("depositReference"));
            }
            pay(id, new Body(dep));
        }
        return view(id);
    }

    // ---------------- looking ----------------

    public Map<String, Object> view(UUID id) {
        Map<String, Object> c = new LinkedHashMap<>(db.one("Catering order", """
                SELECT c.*, o.total AS bill_total, o.number AS bill_number, o.status AS bill_status, coalesce(o.paid, 0) AS bill_paid,
                       extract(epoch FROM c.event_at) AS event_epoch
                FROM catering_orders c LEFT JOIN orders o ON o.id = c.order_id WHERE c.id = :id""", "id", id));
        Instant at = epoch(c.get("eventEpoch"));
        c.put("day", DAY.format(at.atZone(NAIROBI)));
        c.put("time", TIME.format(at.atZone(NAIROBI)));
        c.put("date", at.atZone(NAIROBI).toLocalDate().toString());
        c.put("timeOfDay", at.atZone(NAIROBI).toLocalTime().withSecond(0).withNano(0).toString());
        // Once the bill exists it is the one source of truth for the total: the till's own rules (rounding) apply to it.
        if (c.get("billTotal") != null) {
            c.put("total", c.get("billTotal"));
        }
        c.remove("billTotal");
        if ("VOID".equals(c.get("billStatus")) || "REFUNDED".equals(c.get("billStatus"))) {
            c.put("status", "CANCELLED");
        }
        boolean cancelled = "CANCELLED".equals(c.get("status"));
        BigDecimal total = (BigDecimal) c.get("total");
        BigDecimal paid = cancelled ? BigDecimal.ZERO : ((BigDecimal) c.get("billPaid")).min(total);
        c.put("paid", paid);
        c.put("balance", cancelled ? BigDecimal.ZERO : total.subtract(paid));
        c.put("lines", db.list("""
                SELECT id, item_id, name, per_person, qty, unit_price, line_total,
                       CASE WHEN per_person THEN qty * :people ELSE qty END AS total_qty
                FROM catering_lines WHERE catering_id = :id ORDER BY name""", "id", id, "people", c.get("people")));
        boolean asking = c.get("orderId") != null && Boolean.TRUE.equals(db.value("""
                SELECT exists(SELECT 1 FROM manager_requests WHERE order_id = :o AND kind = 'CANCEL_BILL' AND done_at IS NULL)""",
                Boolean.class, "o", c.get("orderId")));
        c.put("pendingAsk", asking);
        c.remove("billPaid");
        c.remove("eventEpoch");
        return c;
    }

    /** What the customer's link shows: no staff-only fields. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> publicView(UUID id) {
        Map<String, Object> v = view(id);
        for (String k : List.of("createdBy", "tenantId", "orderId", "billStatus", "sentToKitchenAt", "pendingAsk", "id", "notes", "createdAt", "timeOfDay")) {
            v.remove(k);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object l : (List<?>) v.get("lines")) {
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) l);
            m.remove("itemId");
            lines.add(m);
        }
        v.put("lines", lines);
        return v;
    }

    public UUID idOfToken(String token) {
        UUID id = db.value("SELECT id FROM catering_orders WHERE token = :tk", UUID.class, "tk", token);
        if (id == null) {
            throw ApiException.notFound("Catering order");
        }
        return id;
    }

    /** Orders from a month ago on, by date, plus today's events for the kitchen. */
    public Map<String, Object> list() {
        LocalDate today = LocalDate.now(NAIROBI);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : db.list("""
                SELECT id FROM catering_orders
                WHERE (event_at AT TIME ZONE 'Africa/Nairobi')::date >= cast(:f as date) - 30 ORDER BY event_at LIMIT 300""", "f", today)) {
            rows.add(view(UUID.fromString(r.get("id").toString())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", rows);
        out.put("today", rows.stream().filter(x -> today.toString().equals(x.get("date")) && !"CANCELLED".equals(x.get("status"))).toList());
        return out;
    }

    // ---------------- changes ----------------

    /** BOOKED, PREPARING or DELIVERED. Preparing sends the dishes to the kitchen when the business has one. */
    public Map<String, Object> setStatus(UUID id, String status) {
        TenantContext.requireRole("MANAGER", "CASHIER", "KITCHEN");
        if (!STATUSES.contains(status)) {
            throw ApiException.bad("Choose booked, preparing or delivered");
        }
        db.one("Catering order", "SELECT status FROM catering_orders WHERE id = :id FOR UPDATE", "id", id);
        if ("CANCELLED".equals(view(id).get("status"))) {
            throw ApiException.conflict("This order was cancelled");
        }
        db.exec("UPDATE catering_orders SET status = :s WHERE id = :id", "id", id, "s", status);
        if ("PREPARING".equals(status) && kitchenOn()) {
            toKitchen(id);
        }
        return view(id);
    }

    /** Details that don't change the money: who, when, where and notes. */
    public Map<String, Object> update(UUID id, Body b) {
        staffOnly();
        db.one("Catering order", "SELECT id FROM catering_orders WHERE id = :id FOR UPDATE", "id", id);
        Map<String, Object> cur = view(id);
        if ("CANCELLED".equals(cur.get("status"))) {
            throw ApiException.conflict("This order was cancelled");
        }
        String name = b.has("name") ? clip(b.str("name", "").trim(), 80) : (String) cur.get("name");
        String phone = b.has("phone") ? TableOrderService.phone(b.str("phone", null)) : (String) cur.get("phone");
        if (name.isEmpty() || phone == null) {
            throw ApiException.bad("Type the customer's name and a phone number");
        }
        Instant at;
        try {
            at = ZonedDateTime.of(LocalDate.parse(b.str("date", (String) cur.get("date"))),
                    LocalTime.parse(b.str("time", (String) cur.get("timeOfDay"))), NAIROBI).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.bad("Choose the day and time of the event");
        }
        db.exec("UPDATE catering_orders SET name = :name, phone = :phone, event_at = :at, place = :place, notes = :notes WHERE id = :id",
                "id", id, "name", name, "phone", phone, "at", Timestamp.from(at),
                "place", clip(b.has("place") ? b.str("place", "").trim() : (String) cur.get("place"), 300),
                "notes", clip(b.has("notes") ? b.str("notes", null) : (String) cur.get("notes"), 500));
        return view(id);
    }

    /**
     * Money taken: {amount, method CASH|MPESA|CARD, reference}. The first payment opens the bill. A payment that is less
     * than what is left is a deposit on the bill; one that clears it closes the bill (needs the till open, as for any sale).
     */
    public Map<String, Object> pay(UUID id, Body b) {
        staffOnly();
        db.one("Catering order", "SELECT id FROM catering_orders WHERE id = :id FOR UPDATE", "id", id);
        Map<String, Object> v = view(id);
        if ("CANCELLED".equals(v.get("status"))) {
            throw ApiException.conflict("This order was cancelled");
        }
        BigDecimal amount = b.num("amount", null);
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.bad("Type the amount paid");
        }
        BigDecimal balance = (BigDecimal) v.get("balance");
        if (amount.compareTo(balance) > 0) {
            throw ApiException.bad("That is more than the balance to pay of " + plain(balance));
        }
        String method = b.str("method", "CASH").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw ApiException.bad("Choose cash, M-Pesa or card");
        }
        String ref = clip(b.str("reference", method.equals("CASH") ? "Counter" : ""), 30);
        if (!method.equals("CASH") && (ref == null || ref.isBlank())) {
            throw ApiException.bad("Type the " + (method.equals("MPESA") ? "M-Pesa" : "card") + " code so the payment can be matched later");
        }
        if (!method.equals("CASH")) {
            Boolean used = db.value("SELECT exists(SELECT 1 FROM payments WHERE method = :m AND amount > 0 AND lower(reference) = lower(:r))",
                    Boolean.class, "m", method, "r", ref);
            if (Boolean.TRUE.equals(used)) {
                throw ApiException.conflict("That " + (method.equals("MPESA") ? "M-Pesa" : "card") + " code was already used for another payment");
            }
        }
        UUID bill = ensureBill(id);
        requests.requireNoOpenAsk(bill, "taking a payment");
        if (amount.compareTo(balance) < 0) {
            orders.depositByServer(bill, method, amount, ref);
        } else {
            orders.pay(bill, new Body(Map.of("payments", List.of(Map.of("method", method, "amount", amount, "reference", ref)))));
        }
        db.exec("UPDATE catering_orders SET total = (SELECT total FROM orders WHERE id = :o) WHERE id = :id", "id", id, "o", bill);
        activity.record("CATERING_PAYMENT", ActivityLog.INFO, "Took KES " + plain(amount) + " by " + method
                + " for the catering order of " + v.get("name"), bill, null, amount, null, Map.of("method", method));
        return view(id);
    }

    /** Opens the bill the money goes on (once), with the menu as its lines at the agreed prices. */
    UUID ensureBill(UUID id) {
        Map<String, Object> c = db.one("Catering order", "SELECT * FROM catering_orders WHERE id = :id FOR UPDATE", "id", id);
        if (c.get("orderId") != null) {
            return UUID.fromString(c.get("orderId").toString());
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : db.list("SELECT item_id, name, per_person, qty, unit_price FROM catering_lines WHERE catering_id = :id", "id", id)) {
            BigDecimal q = (BigDecimal) l.get("qty");
            if (Boolean.TRUE.equals(l.get("perPerson"))) {
                q = q.multiply(BigDecimal.valueOf(((Number) c.get("people")).longValue()));
            }
            Map<String, Object> line = new LinkedHashMap<>();
            if (l.get("itemId") != null) {
                line.put("itemId", l.get("itemId").toString());
            } else {
                line.put("name", l.get("name"));
            }
            line.put("qty", q);
            line.put("unitPrice", l.get("unitPrice"));
            lines.add(line);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", "TAKEAWAY");
        body.put("customerLabel", clip("Catering: " + c.get("name"), 80));
        body.put("lines", lines);
        UUID bill = orders.createTrusted(new Body(body));
        db.exec("UPDATE orders SET customer_phone = :p WHERE id = :o", "o", bill, "p", c.get("phone"));
        db.exec("UPDATE catering_orders SET order_id = :o WHERE id = :id", "id", id, "o", bill);
        // Opening the bill ran the till's deals before it was marked as a catering bill: take any deal lines off and price it as agreed.
        db.exec("DELETE FROM order_lines WHERE order_id = :o AND deal_id IS NOT NULL", "o", bill);
        orders.recalc(bill);
        db.exec("UPDATE catering_orders SET total = (SELECT total FROM orders WHERE id = :o) WHERE id = :id", "id", id, "o", bill);
        return bill;
    }

    /**
     * Cancel. With no money taken it simply goes. With money taken a manager (or the owner) cancels and the money goes back;
     * anyone else asks, and the order stays until a manager answers in "Waiting for you".
     */
    public Map<String, Object> cancel(UUID id, String reason) {
        staffOnly();
        Map<String, Object> c = db.one("Catering order", "SELECT * FROM catering_orders WHERE id = :id FOR UPDATE", "id", id);
        Map<String, Object> v = view(id);
        if ("CANCELLED".equals(v.get("status"))) {
            throw ApiException.conflict("This order was already cancelled");
        }
        if ("DELIVERED".equals(c.get("status"))) {
            throw ApiException.conflict("This order was delivered, so it can't be cancelled");
        }
        String why = clip(reason == null || reason.isBlank() ? "Cancelled" : reason.trim(), 200);
        BigDecimal paid = (BigDecimal) v.get("paid");
        UUID bill = c.get("orderId") == null ? null : UUID.fromString(c.get("orderId").toString());
        boolean done = true;
        if (bill != null) {
            if (paid.signum() > 0) {
                done = requests.cancelOrAsk(bill, why, "Cancel the catering order for " + c.get("name") + " on " + v.get("day")
                        + ". KES " + plain(paid) + " was paid and goes back. Reason: " + why);
            } else {
                orders.voidOrder(bill, why);
            }
        }
        if (done) {
            db.exec("UPDATE catering_orders SET status = 'CANCELLED', cancel_reason = :r WHERE id = :id", "id", id, "r", why);
            activity.record("CATERING_CANCELLED", paid.signum() > 0 ? ActivityLog.CONCERN : ActivityLog.NOTICE,
                    "Cancelled the catering order for " + c.get("name") + (paid.signum() > 0 ? " after KES " + plain(paid) + " was paid" : "") + ": " + why,
                    bill, null, paid, null, Map.of("reason", why));
        }
        Map<String, Object> out = new LinkedHashMap<>(view(id));
        out.put("askedManager", !done);
        return out;
    }

    // ---------------- the kitchen ----------------

    private boolean kitchenOn() {
        return Boolean.TRUE.equals(db.value("SELECT (settings->'features'->>'kitchen')::boolean FROM tenants WHERE id = :t", Boolean.class));
    }

    /** The dishes go to the kitchen screen like any order sent from the till. */
    public int toKitchen(UUID id) {
        UUID bill = ensureBill(id);
        int n = db.exec("""
                UPDATE order_lines SET kitchen_status = 'NEW', sent_at = now()
                WHERE order_id = :id AND kitchen_status = 'PENDING' AND item_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM items i WHERE i.id = order_lines.item_id AND (i.kind = 'MENU' OR i.station IS NOT NULL))""", "id", bill);
        db.exec("UPDATE catering_orders SET sent_to_kitchen_at = coalesce(sent_to_kitchen_at, now()) WHERE id = :id", "id", id);
        return n;
    }

    /** On the event day, the dishes of today's orders reach the kitchen (only if the business has a kitchen). */
    public void releaseToday() {
        if (!kitchenOn()) {
            return;
        }
        for (Map<String, Object> r : db.list("""
                SELECT c.id FROM catering_orders c
                WHERE c.status IN ('BOOKED', 'PREPARING') AND c.sent_to_kitchen_at IS NULL
                  AND (c.event_at AT TIME ZONE 'Africa/Nairobi')::date <= (now() AT TIME ZONE 'Africa/Nairobi')::date
                  AND c.event_at > now() - interval '1 day'""")) {
            UUID id = UUID.fromString(r.get("id").toString());
            try {
                // A savepoint per order: one that can't be sent doesn't stop the others.
                nested.executeWithoutResult(t -> {
                    if (!"CANCELLED".equals(view(id).get("status"))) {
                        toKitchen(id);
                    }
                });
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(Catering.class).warn("Sending catering order {} to the kitchen: {}", id, e.toString());
            }
        }
    }
}
