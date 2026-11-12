package com.lezitech.suite.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loyalty points, on the owner's own rules ({@code settings.loyalty}: points per 100 spent, what a point is worth, the
 * least that can be used, and expiry after months without a visit). Off until the owner switches it on.
 *
 * Points are earned when a bill is fully paid and has a customer, or a phone number (the customer is found or added by
 * that number). They're used at the till as a payment ("POINTS"), never as a discount, so the manager's discount rules
 * stay separate and reports show what was paid with points. A refund takes the earned points back and returns any
 * points that were used. Every change is a row in {@code loyalty_moves}; the balance lives on the customer.
 *
 * Callers run inside the business's transaction (row-level security on customers and loyalty_moves).
 */
@Service
@org.springframework.transaction.annotation.Transactional
public class LoyaltyService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record Rules(BigDecimal pointsPer100, BigDecimal pointValue, int minToRedeem, int expiryMonths) {
    }

    private final Db db;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public LoyaltyService(Db db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    /** The business's rules, or null while loyalty is off. */
    public Rules rules() {
        return rules(TenantContext.tenantId());
    }

    @SuppressWarnings("unchecked")
    public Rules rules(UUID tenant) {
        String raw = jdbc.queryForObject("SELECT coalesce(settings->'loyalty', '{}'::jsonb)::text FROM tenants WHERE id = ?", String.class, tenant);
        try {
            Map<String, Object> m = json.readValue(raw, Map.class);
            if (!Boolean.TRUE.equals(m.get("on"))) {
                return null;
            }
            return new Rules(new BigDecimal(String.valueOf(m.getOrDefault("pointsPer100", 1))),
                    new BigDecimal(String.valueOf(m.getOrDefault("pointValue", 1))),
                    new BigDecimal(String.valueOf(m.getOrDefault("minToRedeem", 0))).intValue(),
                    new BigDecimal(String.valueOf(m.getOrDefault("expiryMonths", 0))).intValue());
        } catch (Exception e) {
            return null;
        }
    }

    /** What a number of points is worth in money (rounded down to the cent). */
    public static BigDecimal worth(BigDecimal points, Rules r) {
        return points.multiply(r.pointValue()).setScale(2, RoundingMode.DOWN);
    }

    /** The customer's points now, after expiring them if they've had no visit for longer than the rules allow. */
    public BigDecimal balance(UUID customer) {
        Rules r = rules();
        if (r != null) {
            expireIfIdle(customer, r);
        }
        BigDecimal b = db.value("SELECT loyalty_points FROM customers WHERE id = :id", BigDecimal.class, "id", customer);
        return b == null ? BigDecimal.ZERO : b;
    }

    private void expireIfIdle(UUID customer, Rules r) {
        if (r.expiryMonths() <= 0) {
            return;
        }
        Boolean idle = db.value("""
                SELECT c.loyalty_points > 0 AND coalesce((SELECT max(created_at) FROM loyalty_moves m
                        WHERE m.customer_id = c.id AND m.kind IN ('EARN', 'REDEEM')), c.created_at)
                    < now() - make_interval(months => :months)
                FROM customers c WHERE c.id = :id""", Boolean.class, "id", customer, "months", r.expiryMonths());
        if (Boolean.TRUE.equals(idle)) {
            BigDecimal b = db.value("SELECT loyalty_points FROM customers WHERE id = :id FOR UPDATE", BigDecimal.class, "id", customer);
            move(customer, null, "EXPIRE", b.negate(), "No visit for " + r.expiryMonths() + " months");
        }
    }

    /**
     * A bill was fully paid: give its customer points on what they paid (not on what they paid with points). A phone
     * order without a customer gets one, found or added by the phone number.
     */
    public void earn(UUID orderId) {
        Rules r = rules();
        if (r == null) {
            return;
        }
        Map<String, Object> o = db.one("Order", "SELECT number, total, customer_id, customer_phone, customer_label FROM orders WHERE id = :id", "id", orderId);
        UUID customer = o.get("customerId") == null ? null : UUID.fromString(o.get("customerId").toString());
        if (customer == null && o.get("customerPhone") != null) {
            customer = customerByPhone((String) o.get("customerPhone"), (String) o.get("customerLabel"));
            db.exec("UPDATE orders SET customer_id = :c WHERE id = :id", "c", customer, "id", orderId);
        }
        if (customer == null) {
            return;
        }
        BigDecimal withPoints = db.value("SELECT coalesce(sum(amount), 0) FROM payments WHERE order_id = :id AND method = 'POINTS'",
                BigDecimal.class, "id", orderId);
        BigDecimal base = ((BigDecimal) o.get("total")).subtract(withPoints).max(BigDecimal.ZERO);
        BigDecimal points = base.multiply(r.pointsPer100()).divide(HUNDRED, 2, RoundingMode.DOWN);
        if (points.signum() > 0) {
            expireIfIdle(customer, r);
            move(customer, orderId, "EARN", points, "Bill #" + o.get("number"));
        }
    }

    /** Find the customer with this phone (by its last 9 digits), or add them. */
    UUID customerByPhone(String phone, String label) {
        String digits = phone.replaceAll("\\D", "");
        String last9 = digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
        UUID found = db.value("""
                SELECT id FROM customers WHERE right(regexp_replace(coalesce(phone, ''), '\\D', '', 'g'), 9) = :p
                ORDER BY created_at LIMIT 1""", UUID.class, "p", last9);
        if (found != null) {
            return found;
        }
        String name = label != null && !label.isBlank() ? label.trim() : "Guest ••• " + last9.substring(Math.max(0, last9.length() - 3));
        return db.insert("INSERT INTO customers (tenant_id, name, phone) VALUES (:t, :n, :p) RETURNING id", "n", name, "p", "0" + last9);
    }

    /**
     * Check a use of points on an open bill and say what it's worth. The caller then records the POINTS payment and
     * {@link #spent}. The bill must have a customer, with enough points, at least the minimum, and no more than is due.
     */
    public BigDecimal checkSpend(UUID orderId, BigDecimal points) {
        Rules r = rules();
        if (r == null) {
            throw ApiException.bad("Loyalty points are switched off. The owner can switch them on in Settings.");
        }
        Map<String, Object> o = db.one("Order", "SELECT status, total, paid, customer_id FROM orders WHERE id = :id FOR UPDATE", "id", orderId);
        if (!"OPEN".equals(o.get("status"))) {
            throw ApiException.conflict("This bill is already closed");
        }
        if (o.get("customerId") == null) {
            throw ApiException.bad("Pick the customer on this bill first, so we know whose points to use");
        }
        if (points == null || points.signum() <= 0 || points.stripTrailingZeros().scale() > 0) {
            throw ApiException.bad("Use a whole number of points");
        }
        UUID customer = UUID.fromString(o.get("customerId").toString());
        db.list("SELECT id FROM customers WHERE id = :id FOR UPDATE", "id", customer);
        BigDecimal have = balance(customer);
        if (points.compareTo(BigDecimal.valueOf(r.minToRedeem())) < 0) {
            throw ApiException.bad("Points can be used from " + r.minToRedeem() + " at a time");
        }
        if (points.compareTo(have) > 0) {
            throw ApiException.bad("They have " + have.stripTrailingZeros().toPlainString() + " points");
        }
        BigDecimal value = worth(points, r);
        BigDecimal due = ((BigDecimal) o.get("total")).subtract((BigDecimal) o.get("paid"));
        if (value.compareTo(due) > 0) {
            BigDecimal most = due.divide(r.pointValue(), 0, RoundingMode.DOWN);
            throw ApiException.bad("That's worth more than what's left to pay. Use up to " + most.toPlainString() + " points on this bill.");
        }
        if (value.signum() <= 0) {
            throw ApiException.bad("Those points are worth nothing yet");
        }
        return value;
    }

    /** Record points used on a bill (after its POINTS payment). */
    public void spent(UUID orderId, BigDecimal points) {
        Map<String, Object> o = db.one("Order", "SELECT number, customer_id FROM orders WHERE id = :id", "id", orderId);
        move(UUID.fromString(o.get("customerId").toString()), orderId, "REDEEM", points.negate(), "Used on bill #" + o.get("number"));
    }

    /** A refunded bill: take back the points it earned and give back the points used on it. */
    public void refunded(UUID orderId) {
        Integer n = db.value("SELECT number FROM orders WHERE id = :id", Integer.class, "id", orderId);
        // One line for the points it earned (taken back), one for the points used on it (given back).
        for (Map<String, Object> m : db.list("""
                SELECT customer_id, kind, sum(points) AS points FROM loyalty_moves WHERE order_id = :o AND kind IN ('EARN', 'REDEEM')
                GROUP BY customer_id, kind ORDER BY kind""", "o", orderId)) {
            BigDecimal pts = (BigDecimal) m.get("points");
            if (pts.signum() != 0) {
                String note = "EARN".equals(m.get("kind")) ? "Bill #" + n + " refunded: points it earned taken back"
                        : "Bill #" + n + " refunded: points used on it given back";
                move(UUID.fromString(m.get("customerId").toString()), orderId, "REFUND", pts.negate(), note);
            }
        }
    }

    /** The owner or a manager adds or takes away points by hand, with a reason. */
    public void adjust(UUID customer, BigDecimal points, String note) {
        if (points == null || points.signum() == 0) {
            throw ApiException.bad("Enter the points to add (or a minus number to take away)");
        }
        if (note == null || note.isBlank()) {
            throw ApiException.bad("Say why, so it's clear later");
        }
        BigDecimal have = balance(customer);
        if (have.add(points).signum() < 0) {
            throw ApiException.bad("They only have " + have.stripTrailingZeros().toPlainString() + " points");
        }
        move(customer, null, "ADJUST", points, note.trim().length() > 200 ? note.trim().substring(0, 200) : note.trim());
    }

    public Map<String, Object> summary(UUID customer) {
        Rules r = rules();
        Map<String, Object> out = new LinkedHashMap<>();
        BigDecimal b = balance(customer);
        out.put("on", r != null);
        out.put("points", b);
        out.put("worth", r == null ? null : worth(b, r));
        out.put("minToRedeem", r == null ? null : r.minToRedeem());
        out.put("moves", history(customer));
        return out;
    }

    /**
     * For the diner's own order page: points this bill earned and their balance (no name or history). Null while
     * loyalty is off or the bill has no customer yet.
     */
    public Map<String, Object> forOrder(UUID orderId) {
        Rules r = rules();
        if (r == null || orderId == null) {
            return null;
        }
        UUID customer = db.value("SELECT customer_id FROM orders WHERE id = :id", UUID.class, "id", orderId);
        if (customer == null) {
            return null;
        }
        BigDecimal earned = db.value("SELECT coalesce(sum(points), 0) FROM loyalty_moves WHERE order_id = :o AND kind = 'EARN'",
                BigDecimal.class, "o", orderId);
        BigDecimal b = balance(customer);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("earned", earned);
        out.put("points", b);
        out.put("worth", worth(b, r));
        out.put("minToRedeem", r.minToRedeem());
        return out;
    }

    public List<Map<String, Object>> history(UUID customer) {
        return db.list("""
                SELECT m.kind, m.points, m.note, m.created_at, o.number AS order_number, u.name AS by_name
                FROM loyalty_moves m LEFT JOIN orders o ON o.id = m.order_id LEFT JOIN users u ON u.id = m.created_by
                WHERE m.customer_id = :c ORDER BY m.created_at DESC LIMIT 100""", "c", customer);
    }

    private void move(UUID customer, UUID orderId, String kind, BigDecimal points, String note) {
        db.exec("""
                INSERT INTO loyalty_moves (tenant_id, customer_id, order_id, kind, points, note, created_by)
                VALUES (:t, :c, :o, :k, :p, :n, :u)""", "c", customer, "o", orderId, "k", kind, "p", points, "n", note);
        db.exec("UPDATE customers SET loyalty_points = greatest(loyalty_points + :p, 0) WHERE id = :c", "c", customer, "p", points);
    }
}
