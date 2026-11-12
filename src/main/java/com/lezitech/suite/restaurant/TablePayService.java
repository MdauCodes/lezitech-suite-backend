package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.mpesa.MpesaService;
import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Diners pay from their phone: one person, or split across phones with prompts sent one at a time (a phone that
 * fails or cancels is tried once more after the others). A tip rides on top and is kept apart from the sale.
 * When a pay-first order is fully paid it goes to the kitchen. Afterwards the diner can leave a rating.
 *
 * Callers run as the restaurant (TenantContext.runAs) inside a transaction; M-Pesa results arrive later as events.
 */
@Service
public class TablePayService {

    private static final Logger log = LoggerFactory.getLogger(TablePayService.class);
    static final int MAX_PAYERS = 10;

    private final Db db;
    private final JdbcTemplate jdbc;
    private final MpesaService mpesa;
    private final TableOrderService tableOrders;
    private final TransactionTemplate tx;

    public TablePayService(Db db, JdbcTemplate jdbc, MpesaService mpesa, TableOrderService tableOrders, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = jdbc;
        this.mpesa = mpesa;
        this.tableOrders = tableOrders;
        this.tx = new TransactionTemplate(txm);
    }

    /**
     * What a payment is for: one order ({@code batch} null), or a group order's combined bill, filed on its lead order
     * (the group's first order by number).
     */
    public record Bill(UUID lead, UUID batch) {
        public static Bill of(UUID orderId) {
            return new Bill(orderId, null);
        }
    }

    /** The combined bill of a group order; 404 if there's no such group. */
    @org.springframework.transaction.annotation.Transactional
    public Bill groupBill(UUID batch) {
        UUID lead = db.value("SELECT id FROM orders WHERE batch_id = :b ORDER BY number LIMIT 1", UUID.class, "b", batch);
        if (lead == null) {
            throw ApiException.notFound("Group order");
        }
        return new Bill(lead, batch);
    }

    /** What's left to pay, and whether paying is open now (food ready, or a pay-first order). */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> payable(UUID orderId) {
        return payable(Bill.of(orderId));
    }

    /** For a group: the sum over its open orders; paying opens once no open order is still cooking (or all wait for payment). */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> payable(Bill bill) {
        if (bill.batch() != null) {
            List<Map<String, Object>> rows = db.list("""
                    SELECT o.total, o.paid, o.held_for_payment,
                           EXISTS (SELECT 1 FROM order_lines l WHERE l.order_id = o.id AND l.kitchen_status IN ('NEW', 'PREPARING', 'ON_HOLD')) AS cooking
                    FROM orders o WHERE o.batch_id = :b AND o.status = 'OPEN'""", "b", bill.batch());
            BigDecimal due = BigDecimal.ZERO;
            boolean ready = true;
            for (Map<String, Object> o : rows) {
                due = due.add(((BigDecimal) o.get("total")).subtract((BigDecimal) o.get("paid")).max(BigDecimal.ZERO));
                ready &= Boolean.TRUE.equals(o.get("heldForPayment")) || !Boolean.TRUE.equals(o.get("cooking"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("due", due);
            out.put("open", due.signum() > 0 && ready);
            return out;
        }
        UUID orderId = bill.lead();
        Map<String, Object> o = db.one("Order", """
                SELECT o.id, o.status, o.total, o.paid, o.held_for_payment,
                       EXISTS (SELECT 1 FROM order_lines l WHERE l.order_id = o.id AND l.kitchen_status IN ('NEW', 'PREPARING', 'ON_HOLD')) AS cooking
                FROM orders o WHERE o.id = :id""", "id", orderId);
        BigDecimal due = ((BigDecimal) o.get("total")).subtract((BigDecimal) o.get("paid")).max(BigDecimal.ZERO);
        boolean open = "OPEN".equals(o.get("status")) && due.signum() > 0
                && (Boolean.TRUE.equals(o.get("heldForPayment")) || !Boolean.TRUE.equals(o.get("cooking")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("due", due);
        out.put("open", open);
        return out;
    }

    private BigDecimal requireOpen(Bill bill) {
        Map<String, Object> p = payable(bill);
        if (!Boolean.TRUE.equals(p.get("open"))) {
            throw ApiException.conflict("You can pay once your food is ready. We'll show the button here.");
        }
        // Any prompt on the way for this order, or (if it's part of a group) for any order in the group, blocks a new one:
        // a table paying its own part and the group paying the whole can't both be prompted at once.
        UUID batch = bill.batch() != null ? bill.batch()
                : db.value("SELECT batch_id FROM orders WHERE id = :id", UUID.class, "id", bill.lead());
        String scope = batch == null ? "SELECT CAST(:id AS uuid)" : "SELECT id FROM orders WHERE batch_id = :b";
        if (db.value("SELECT count(*) FROM pay_splits WHERE order_id IN (" + scope + ") AND status IN ('WAITING', 'PROMPTED')",
                Long.class, "id", bill.lead(), "b", batch) > 0
                || db.value("SELECT count(*) FROM mpesa_requests WHERE order_id IN (" + scope + ") AND status = 'PENDING'"
                        + " AND created_at > now() - interval '2 minutes'", Long.class, "id", bill.lead(), "b", batch) > 0) {
            throw ApiException.conflict("A payment prompt is already on its way. Check your phone.");
        }
        return (BigDecimal) p.get("due");
    }

    /** One phone pays everything that's due, plus a tip if they like. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> payAll(UUID orderId, String phone, BigDecimal tip) {
        return payAll(Bill.of(orderId), phone, tip);
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> payAll(Bill bill, String phone, BigDecimal tip) {
        BigDecimal due = requireOpen(bill);
        return mpesa.startTablePayment(bill.lead(), phone, due, tip(tip), null, bill.batch());
    }

    /**
     * Split across phones. Each share is a whole-shilling amount; they must add up to exactly what's due. The tip, if
     * any, goes with the first phone's prompt.
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> split(UUID orderId, List<Map<String, Object>> payers, BigDecimal tip) {
        return split(Bill.of(orderId), payers, tip);
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> split(Bill bill, List<Map<String, Object>> payers, BigDecimal tip) {
        UUID orderId = bill.lead();
        BigDecimal due = requireOpen(bill);
        if (payers == null || payers.size() < 2 || payers.size() > MAX_PAYERS) {
            throw ApiException.bad("Split between 2 and " + MAX_PAYERS + " phones");
        }
        List<String> phones = new ArrayList<>();
        List<BigDecimal> amounts = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> p : payers) {
            String phone = TableOrderService.phone(String.valueOf(p.getOrDefault("phone", "")));
            if (phone == null) {
                throw ApiException.bad("Enter an M-Pesa number for every person");
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(String.valueOf(p.get("amount"))).setScale(2, RoundingMode.HALF_UP);
            } catch (RuntimeException e) {
                throw ApiException.bad("Enter an amount for every person");
            }
            if (amount.signum() <= 0) {
                throw ApiException.bad("Every share must be more than zero");
            }
            phones.add(phone);
            amounts.add(amount);
            sum = sum.add(amount);
        }
        if (sum.compareTo(due) != 0) {
            throw ApiException.bad("The shares add up to " + sum.toPlainString() + " but " + due.toPlainString() + " is due");
        }
        BigDecimal t = tip(tip);
        for (int i = 0; i < phones.size(); i++) {
            db.exec("""
                    INSERT INTO pay_splits (tenant_id, order_id, phone, amount, position, tip, batch_id)
                    VALUES (:t, :o, :p, :a, :pos, :tip, :b)""",
                    "o", orderId, "p", phones.get(i), "a", amounts.get(i), "pos", i + 1, "tip", i == 0 ? t : BigDecimal.ZERO,
                    "b", bill.batch());
        }
        promptNext(orderId);
        return Map.of("ok", true, "payers", phones.size());
    }

    /** Equal shares in whole shillings; the first person rounds up so the total is exact. */
    public static List<BigDecimal> equalShares(BigDecimal due, int people) {
        BigDecimal base = due.divide(BigDecimal.valueOf(people), 0, RoundingMode.DOWN);
        List<BigDecimal> out = new ArrayList<>();
        BigDecimal rest = due.subtract(base.multiply(BigDecimal.valueOf(people)));
        for (int i = 0; i < people; i++) {
            out.add(i == 0 ? base.add(rest) : base);
        }
        return out;
    }

    /** Send the next waiting share's prompt; a phone that can't be prompted at all is marked failed and skipped. */
    void promptNext(UUID orderId) {
        for (int guard = 0; guard < MAX_PAYERS * 2; guard++) {
            Map<String, Object> next = db.find("""
                    SELECT id, phone, amount, tip, batch_id FROM pay_splits WHERE order_id = :o AND status = 'WAITING'
                    ORDER BY position LIMIT 1""", "o", orderId).orElse(null);
            if (next == null) {
                return;
            }
            UUID splitId = UUID.fromString(next.get("id").toString());
            UUID batch = next.get("batchId") == null ? null : UUID.fromString(next.get("batchId").toString());
            db.exec("UPDATE pay_splits SET status = 'PROMPTED', attempts = attempts + 1 WHERE id = :id", "id", splitId);
            try {
                Map<String, Object> req = mpesa.startTablePayment(orderId, (String) next.get("phone"), (BigDecimal) next.get("amount"),
                        (BigDecimal) next.get("tip"), splitId, batch);
                db.exec("UPDATE pay_splits SET mpesa_request_id = :r WHERE id = :id", "r", UUID.fromString(req.get("id").toString()), "id", splitId);
                return;
            } catch (RuntimeException e) {
                log.info("Split prompt to {} couldn't be sent: {}", next.get("phone"), e.getMessage());
                db.exec("UPDATE pay_splits SET status = 'FAILED' WHERE id = :id", "id", splitId);
            }
        }
    }

    /** M-Pesa's answer for a prompt from a diner's phone. */
    @EventListener
    public void onSettled(MpesaService.OrderPromptSettled e) {
        try {
            TenantContext.runAs(e.tenant(), null, () -> tx.execute(s -> {
                settled(e);
                return null;
            }));
        } catch (RuntimeException ex) {
            log.warn("After payment on order {}: {}", e.orderId(), ex.toString());
        }
    }

    private void settled(MpesaService.OrderPromptSettled e) {
        if ("PAID".equals(e.outcome()) && e.tip() != null && e.tip().signum() > 0) {
            recordTip(e.orderId(), e.tip());
        }
        if (e.splitId() != null) {
            if ("PAID".equals(e.outcome())) {
                db.exec("UPDATE pay_splits SET status = 'PAID' WHERE id = :id", "id", e.splitId());
            } else {
                // Failed or cancelled: try that phone once more, after everyone else.
                Integer attempts = db.value("SELECT attempts FROM pay_splits WHERE id = :id", Integer.class, "id", e.splitId());
                if (!"UNAPPLIED".equals(e.outcome()) && attempts != null && attempts < 2) {
                    db.exec("""
                            UPDATE pay_splits SET status = 'WAITING',
                                   position = (SELECT max(position) + 1 FROM pay_splits WHERE order_id = :o)
                            WHERE id = :id""", "o", e.orderId(), "id", e.splitId());
                } else {
                    db.exec("UPDATE pay_splits SET status = 'FAILED' WHERE id = :id", "id", e.splitId());
                }
            }
        }
        // A pay-first order goes to the kitchen once it's fully paid; for a group, every order the payment closed.
        List<UUID> paidAndHeld = db.list(e.batchId() == null
                        ? "SELECT id FROM orders WHERE id = :o AND held_for_payment AND status <> 'OPEN'"
                        : "SELECT id FROM orders WHERE batch_id = :b AND held_for_payment AND status NOT IN ('OPEN', 'VOID') ORDER BY number",
                "o", e.orderId(), "b", e.batchId()).stream().map(r -> UUID.fromString(r.get("id").toString())).toList();
        for (UUID id : paidAndHeld) {
            db.exec("UPDATE orders SET held_for_payment = false WHERE id = :id", "id", id);
            tableOrders.sendToKitchen(id);
        }
        if (e.splitId() != null) {
            boolean stillOwed = e.batchId() != null
                    ? mpesa.groupDue(e.batchId()).signum() > 0
                    : "OPEN".equals(db.value("SELECT status FROM orders WHERE id = :id", String.class, "id", e.orderId()));
            if (stillOwed) {
                promptNext(e.orderId());
            }
        }
    }

    /** Tips are shared between kitchen, business and the server, by the restaurant's shares at the time. */
    void recordTip(UUID orderId, BigDecimal tip) {
        Map<String, Object> o = db.one("Order", "SELECT assigned_to, user_id FROM orders WHERE id = :id", "id", orderId);
        Object server = o.get("assignedTo") != null ? o.get("assignedTo") : o.get("userId");
        String raw = jdbc.queryForObject("SELECT coalesce(settings->'ordering', '{}'::jsonb)::text FROM tenants WHERE id = ?", String.class,
                TenantContext.tenantId());
        Map<String, Object> shares = shares(raw);
        BigDecimal kitchen = tip.multiply(pct(shares.get("kitchen"))).setScale(2, RoundingMode.DOWN);
        BigDecimal business = tip.multiply(pct(shares.get("business"))).setScale(2, RoundingMode.DOWN);
        BigDecimal serverShare = tip.subtract(kitchen).subtract(business);
        db.exec("""
                INSERT INTO tips (tenant_id, order_id, amount, server_id, kitchen_share, business_share, server_share)
                VALUES (:t, :o, :a, :s, :k, :b, :sv)""",
                "o", orderId, "a", tip, "s", server == null ? null : UUID.fromString(server.toString()),
                "k", kitchen, "b", business, "sv", serverShare);
        db.exec("UPDATE orders SET tip = tip + :a WHERE id = :id", "a", tip, "id", orderId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shares(String raw) {
        try {
            Map<String, Object> o = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
            return (Map<String, Object>) OrderingSettings.withDefaults(o, null).get("tipShares");
        } catch (Exception e) {
            return new LinkedHashMap<>(OrderingSettings.DEFAULT_SHARES);
        }
    }

    private static BigDecimal pct(Object v) {
        return new BigDecimal(String.valueOf(v == null ? 0 : v)).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal tip(BigDecimal t) {
        if (t == null || t.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (t.compareTo(BigDecimal.valueOf(100_000)) > 0) {
            throw ApiException.bad("That tip is too big");
        }
        return t.setScale(0, RoundingMode.HALF_UP);
    }

    /** How paying is going, for the diner's tracking page. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> progress(UUID orderId) {
        return progress(Bill.of(orderId));
    }

    /** A table's own link shows its own payments; the group's link shows the group's. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> progress(Bill bill) {
        Map<String, Object> out = new LinkedHashMap<>(payable(bill));
        // Leading space: a text block drops the trailing space after WHERE.
        String mine = bill.batch() == null ? " order_id = :o AND batch_id IS NULL" : " batch_id = :b";
        out.put("splits", db.list("""
                SELECT position, '••• ' || right(phone, 3) AS phone, amount, tip, status FROM pay_splits WHERE """ + mine + " ORDER BY position",
                "o", bill.lead(), "b", bill.batch()));
        out.put("last", db.find("""
                SELECT status, result_desc AS message, '••• ' || right(phone, 3) AS phone, amount, created_at
                FROM mpesa_requests WHERE """ + mine + " ORDER BY created_at DESC LIMIT 1", "o", bill.lead(), "b", bill.batch()).orElse(null));
        return out;
    }

    /** The group rates its visit once: every paid order in the group that isn't rated yet takes the rating. */
    @org.springframework.transaction.annotation.Transactional
    public void rateGroup(UUID batch, int stars, String comment) {
        List<Map<String, Object>> list = db.list("SELECT id, status, rated_at FROM orders WHERE batch_id = :b", "b", batch);
        boolean anyOpen = list.stream().anyMatch(o -> "OPEN".equals(o.get("status")));
        List<UUID> toRate = list.stream().filter(o -> "PAID".equals(o.get("status")) && o.get("ratedAt") == null)
                .map(o -> UUID.fromString(o.get("id").toString())).toList();
        if (anyOpen) {
            throw ApiException.conflict("You can rate once you've paid");
        }
        if (toRate.isEmpty()) {
            throw ApiException.conflict("Thanks, you've already rated this order");
        }
        for (UUID id : toRate) {
            rate(id, stars, comment);
        }
    }

    /** One rating per order, once it's paid. */
    @org.springframework.transaction.annotation.Transactional
    public void rate(UUID orderId, int stars, String comment) {
        if (stars < 1 || stars > 5) {
            throw ApiException.bad("Choose 1 to 5 stars");
        }
        Map<String, Object> o = db.one("Order", "SELECT status, rated_at FROM orders WHERE id = :id", "id", orderId);
        if ("OPEN".equals(o.get("status")) || "VOID".equals(o.get("status"))) {
            throw ApiException.conflict("You can rate once you've paid");
        }
        if (o.get("ratedAt") != null) {
            throw ApiException.conflict("Thanks, you've already rated this order");
        }
        String c = comment == null || comment.isBlank() ? null : comment.trim();
        db.exec("UPDATE orders SET rating = :r, rating_comment = :c, rated_at = now() WHERE id = :id",
                "r", stars, "c", c == null ? null : c.length() > 500 ? c.substring(0, 500) : c, "id", orderId);
    }
}
