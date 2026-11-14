package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.lezitech.suite.notify.Mailer.esc;

/**
 * Property agents and the landlords they collect for (owner's decisions, 2026-11-14). Each landlord's commission is a %
 * of rent collected, a % of rent billed, or a fixed monthly fee. A month's statement for a landlord: their units' rent
 * billed and collected (cash applied to bills that month, deposits aside), withholding-tax certificates received,
 * commission, expenses paid for them, the rental income tax (MRI) on residential rent for their own filing, and what's
 * owed to them after payouts. Statements are emailed on the 1st and open from a private link. Callers run inside the
 * workspace's transaction.
 */
@Service
public class Landlords {

    static final List<String> KINDS = List.of("PCT_COLLECTED", "PCT_BILLED", "FIXED");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH);

    /** The month a bill belongs to: its rent month, its stall day, or the day it was made. */
    static final String BILL_MONTH = "coalesce(o.period, to_char(o.charge_day, 'YYYY-MM'), to_char(o.created_at AT TIME ZONE 'Africa/Nairobi', 'YYYY-MM'))";

    private final Db db;
    private final Mailer mailer;

    public Landlords(Db db, Mailer mailer) {
        this.db = db;
        this.mailer = mailer;
    }

    public Map<String, Object> save(UUID id, Body b) {
        String name = b.str("name", "").trim();
        if (name.isEmpty()) {
            throw ApiException.bad("Enter the landlord's name");
        }
        String kind = b.str("commissionKind", "PCT_COLLECTED").toUpperCase();
        if (!KINDS.contains(kind)) {
            throw ApiException.bad("Choose the commission: % of rent collected, % of rent billed, or a fixed monthly fee");
        }
        BigDecimal value = b.num("commissionValue", BigDecimal.ZERO);
        if (value.signum() < 0 || (!"FIXED".equals(kind) && value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw ApiException.bad("Check the commission");
        }
        Object[] p = {"name", name, "phone", blank(b.str("phone", null)), "email", blank(b.str("email", null)) == null ? null : b.str("email").trim().toLowerCase(),
                "kind", kind, "value", value, "payout", blank(b.str("payoutDetails", null)), "pin", blank(b.str("kraPin", null))};
        if (id == null) {
            id = db.insert("""
                    INSERT INTO landlords (tenant_id, name, phone, email, commission_kind, commission_value, payout_details, kra_pin)
                    VALUES (:t, :name, :phone, :email, :kind, :value, :payout, :pin) RETURNING id""", p);
        } else {
            db.one("Landlord", "SELECT id FROM landlords WHERE id = :id", "id", id);
            Object[] q = java.util.Arrays.copyOf(p, p.length + 2);
            q[p.length] = "id";
            q[p.length + 1] = id;
            db.exec("""
                    UPDATE landlords SET name = :name, phone = :phone, email = :email, commission_kind = :kind, commission_value = :value,
                                         payout_details = :payout, kra_pin = :pin WHERE id = :id""", q);
        }
        return db.one("Landlord", "SELECT * FROM landlords WHERE id = :id", "id", id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Which landlord owns these units (null = the business itself). */
    public void assignUnits(UUID landlordId, List<String> spaceIds) {
        if (landlordId != null) {
            db.one("Landlord", "SELECT id FROM landlords WHERE id = :id", "id", landlordId);
        }
        for (String s : spaceIds) {
            db.exec("UPDATE spaces SET landlord_id = :l WHERE id = :s", "l", landlordId, "s", UUID.fromString(s));
        }
    }

    public Map<String, Object> addExpense(UUID landlordId, Body b) {
        db.one("Landlord", "SELECT id FROM landlords WHERE id = :id", "id", landlordId);
        String what = b.str("what", "").trim();
        BigDecimal amount = b.num("amount", BigDecimal.ZERO);
        if (what.isEmpty() || amount.signum() <= 0) {
            throw ApiException.bad("Say what was paid for and how much");
        }
        db.exec("""
                INSERT INTO landlord_expenses (tenant_id, landlord_id, space_id, what, amount, spent_on, reference, user_id)
                VALUES (:t, :l, :s, :w, :a, :d, :r, :u)""", "l", landlordId,
                "s", b.has("spaceId") && !b.str("spaceId", "").isBlank() ? UUID.fromString(b.str("spaceId")) : null,
                "w", what.length() > 150 ? what.substring(0, 150) : what, "a", amount, "d", b.has("spentOn") ? b.date("spentOn") : LeaseLedger.today(),
                "r", blank(b.str("reference", null)), "u", TenantContext.userId());
        return Map.of("saved", true);
    }

    public Map<String, Object> addPayout(UUID landlordId, Body b) {
        db.one("Landlord", "SELECT id FROM landlords WHERE id = :id", "id", landlordId);
        BigDecimal amount = b.num("amount", BigDecimal.ZERO);
        String method = b.str("method", "BANK").toUpperCase();
        if (amount.signum() <= 0) {
            throw ApiException.bad("Enter the amount paid to the landlord");
        }
        if (!List.of("BANK", "MPESA", "CASH", "CHEQUE").contains(method)) {
            throw ApiException.bad("Choose how it was paid");
        }
        db.exec("""
                INSERT INTO landlord_payouts (tenant_id, landlord_id, amount, method, reference, paid_on, note, user_id)
                VALUES (:t, :l, :a, :m, :r, :d, :n, :u)""", "l", landlordId, "a", amount, "m", method, "r", blank(b.str("reference", null)),
                "d", b.has("paidOn") ? b.date("paidOn") : LeaseLedger.today(), "n", blank(b.str("note", null)), "u", TenantContext.userId());
        return Map.of("saved", true);
    }

    public List<Map<String, Object>> list(YearMonth period) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> l : db.list("""
                SELECT l.*, (SELECT count(*) FROM spaces sp WHERE sp.landlord_id = l.id AND sp.active) AS units
                FROM landlords l WHERE l.active ORDER BY l.name""")) {
            Map<String, Object> s = statement(UUID.fromString(l.get("id").toString()), period);
            Map<String, Object> row = new LinkedHashMap<>(l);
            row.remove("statementToken");
            row.put("collected", s.get("collected"));
            row.put("net", s.get("net"));
            row.put("owed", s.get("owed"));
            out.add(row);
        }
        return out;
    }

    /** A landlord's statement for a month (live figures), with what's owed to them overall after payouts. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> statement(UUID landlordId, YearMonth period) {
        Map<String, Object> l = db.one("Landlord", "SELECT * FROM landlords WHERE id = :id", "id", landlordId);
        String p = period.toString();
        LocalDate first = period.atDay(1);
        LocalDate last = period.atEndOfMonth();
        List<Map<String, Object>> units = db.list("""
                SELECT sp.id, sp.name, sp.kind, c.name AS tenant_name,
                       (SELECT coalesce(sum(o.total), 0) FROM orders o JOIN leases x ON x.id = o.lease_id
                         WHERE x.space_id = sp.id AND o.status <> 'VOID' AND o.channel <> 'DEPOSIT' AND """ + " " + BILL_MONTH + " " + """
                         = :p) AS billed,
                       (SELECT coalesce(sum(pm.amount), 0) FROM payments pm JOIN orders o ON o.id = pm.order_id JOIN leases x ON x.id = o.lease_id
                         WHERE x.space_id = sp.id AND o.channel <> 'DEPOSIT' AND pm.method <> 'WHT'
                           AND (pm.created_at AT TIME ZONE 'Africa/Nairobi')::date BETWEEN cast(:f as date) AND cast(:e as date)) AS collected,
                       (SELECT coalesce(sum(pm.amount), 0) FROM payments pm JOIN orders o ON o.id = pm.order_id JOIN leases x ON x.id = o.lease_id
                         WHERE x.space_id = sp.id AND pm.method = 'WHT'
                           AND (pm.created_at AT TIME ZONE 'Africa/Nairobi')::date BETWEEN cast(:f as date) AND cast(:e as date)) AS wht,
                       (SELECT coalesce(sum(o.total - o.paid), 0) FROM orders o JOIN leases x ON x.id = o.lease_id
                         WHERE x.space_id = sp.id AND o.status = 'OPEN' AND o.channel <> 'DEPOSIT') AS arrears
                FROM spaces sp LEFT JOIN leases ls ON ls.space_id = sp.id AND ls.status = 'ACTIVE' LEFT JOIN customers c ON c.id = ls.customer_id
                WHERE sp.landlord_id = :l ORDER BY sp.sort, sp.name""", "l", landlordId, "p", p, "f", first, "e", last);
        BigDecimal billed = sum(units, "billed");
        BigDecimal collected = sum(units, "collected");
        BigDecimal wht = sum(units, "wht");
        BigDecimal residential = units.stream().filter(u -> "APARTMENT".equals(u.get("kind"))).map(u -> (BigDecimal) u.get("collected")).reduce(BigDecimal.ZERO, BigDecimal::add);
        String kind = (String) l.get("commissionKind");
        BigDecimal v = (BigDecimal) l.get("commissionValue");
        BigDecimal commission = ("FIXED".equals(kind) ? v : ("PCT_BILLED".equals(kind) ? billed : collected).multiply(v).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        List<Map<String, Object>> expenses = db.list("""
                SELECT e.id, e.what, e.amount, e.spent_on, e.reference, sp.name AS unit_name FROM landlord_expenses e LEFT JOIN spaces sp ON sp.id = e.space_id
                WHERE e.landlord_id = :l AND e.spent_on BETWEEN cast(:f as date) AND cast(:e as date) ORDER BY e.spent_on""", "l", landlordId, "f", first, "e", last);
        BigDecimal spent = sum(expenses, "amount");
        List<Map<String, Object>> payouts = db.list("""
                SELECT id, amount, method, reference, paid_on, note FROM landlord_payouts WHERE landlord_id = :l
                  AND paid_on BETWEEN cast(:f as date) AND cast(:e as date) ORDER BY paid_on""", "l", landlordId, "f", first, "e", last);
        BigDecimal net = collected.subtract(commission).subtract(spent);
        Map<String, Object> rs = RentSettings.withDefaults(db.value("SELECT coalesce(settings->'rent', '{}'::jsonb)::text FROM tenants WHERE id = :t", String.class));
        BigDecimal mriRate = new BigDecimal(String.valueOf(rs.get("mriRate")));

        // Owed to the landlord overall: sent statements' net (this month live if it hasn't been sent yet), less every payout.
        BigDecimal sentNet = db.value("SELECT coalesce(sum(net), 0) FROM landlord_statements WHERE landlord_id = :l AND period <> :p", BigDecimal.class, "l", landlordId, "p", p);
        Map<String, Object> stored = db.find("SELECT * FROM landlord_statements WHERE landlord_id = :l AND period = :p", "l", landlordId, "p", p).orElse(null);
        BigDecimal allPayouts = db.value("SELECT coalesce(sum(amount), 0) FROM landlord_payouts WHERE landlord_id = :l", BigDecimal.class, "l", landlordId);
        BigDecimal owed = sentNet.add(stored == null ? net : (BigDecimal) stored.get("net")).subtract(allPayouts);

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> landlord = new LinkedHashMap<>();
        for (String k : List.of("id", "name", "phone", "email", "commissionKind", "commissionValue", "payoutDetails", "kraPin")) {
            landlord.put(k, l.get(k));
        }
        out.put("landlord", landlord);
        out.put("period", p);
        out.put("units", units);
        out.put("billed", billed);
        out.put("collected", collected);
        out.put("wht", wht);
        out.put("commission", commission);
        out.put("expenses", expenses);
        out.put("expensesTotal", spent);
        out.put("net", net);
        out.put("payouts", payouts);
        out.put("paidOut", sum(payouts, "amount"));
        out.put("owed", owed);
        out.put("arrears", sum(units, "arrears"));
        Map<String, Object> tax = new LinkedHashMap<>();
        tax.put("residentialCollected", residential);
        tax.put("rate", mriRate);
        tax.put("mri", residential.multiply(mriRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        tax.put("fileBy", period.plusMonths(1).atDay(20).toString());
        out.put("tax", tax);
        out.put("sent", stored != null);
        out.put("link", "/owner/" + db.value("SELECT slug FROM tenants WHERE id = :t", String.class) + "/" + l.get("statementToken"));
        return out;
    }

    /** Keep the month's figures (once) and email the landlord their statement link. Returns where it went. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> send(UUID landlordId, YearMonth period) {
        Map<String, Object> s = statement(landlordId, period);
        Map<String, Object> l = (Map<String, Object>) s.get("landlord");
        String email = (String) l.get("email");
        int stored = db.exec("""
                INSERT INTO landlord_statements (tenant_id, landlord_id, period, billed, collected, commission, expenses, net, emailed_to)
                VALUES (:t, :l, :p, :b, :c, :k, :e, :n, :to) ON CONFLICT (landlord_id, period) DO NOTHING""",
                "l", landlordId, "p", period.toString(), "b", s.get("billed"), "c", s.get("collected"), "k", s.get("commission"),
                "e", s.get("expensesTotal"), "n", s.get("net"), "to", email);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kept", stored > 0);
        if (email == null) {
            out.put("emailed", false);
            out.put("problem", "No email for this landlord: send the link on WhatsApp instead");
            return out;
        }
        Map<String, Object> biz = db.one("Business", "SELECT name, brand_color, phone FROM tenants WHERE id = :t");
        String business = (String) biz.get("name");
        String link = mailer.appUrl() + s.get("link") + "?period=" + period;
        String first = ((String) l.get("name")).trim().split("\\s+")[0];
        String lines = "Rent collected: " + LeaseLife.kes((BigDecimal) s.get("collected")) + "\nCommission: " + LeaseLife.kes((BigDecimal) s.get("commission"))
                + "\nExpenses paid for you: " + LeaseLife.kes((BigDecimal) s.get("expensesTotal")) + "\nDue to you for the month: " + LeaseLife.kes((BigDecimal) s.get("net"));
        String err = mailer.trySend(email, (String) l.get("name"), "Your statement for " + MONTH.format(period.atDay(1)) + " from " + business,
                "Hi " + first + ",\n\nYour statement for " + MONTH.format(period.atDay(1)) + ":\n\n" + lines + "\n\nSee every unit, payment and expense: " + link
                        + "\n\n" + business,
                com.lezitech.suite.property.portal.RenterAuth.branded((String) biz.get("brandColor"), business,
                        "<p>Hi " + esc(first) + ",</p><p>Your statement for <b>" + MONTH.format(period.atDay(1)) + "</b>:</p>"
                                + "<p style=\"background:#f6f7f3;border-radius:10px;padding:12px 14px;white-space:pre-line\">" + esc(lines) + "</p>"
                                + com.lezitech.suite.property.portal.RenterAuth.button((String) biz.get("brandColor"), link, "See your full statement")));
        out.put("emailed", err == null);
        out.put("problem", err);
        return out;
    }

    /** For the monthly job: send last month's statements to every landlord with units. Returns how many were emailed. */
    public int sendAll(YearMonth period) {
        int sent = 0;
        for (Map<String, Object> l : db.list("""
                SELECT l.id FROM landlords l WHERE l.active AND EXISTS (SELECT 1 FROM spaces sp WHERE sp.landlord_id = l.id)
                  AND NOT EXISTS (SELECT 1 FROM landlord_statements s WHERE s.landlord_id = l.id AND s.period = :p)""", "p", period.toString())) {
            if (Boolean.TRUE.equals(send(UUID.fromString(l.get("id").toString()), period).get("emailed"))) {
                sent++;
            }
        }
        return sent;
    }

    /** Rental tax for the business: residential rent received in the month, MRI due by the 20th, and WHT certificates received. */
    public Map<String, Object> rentalTax(YearMonth period) {
        LocalDate first = period.atDay(1);
        LocalDate last = period.atEndOfMonth();
        Map<String, Object> rs = RentSettings.withDefaults(db.value("SELECT coalesce(settings->'rent', '{}'::jsonb)::text FROM tenants WHERE id = :t", String.class));
        BigDecimal rate = new BigDecimal(String.valueOf(rs.get("mriRate")));
        List<Map<String, Object>> byOwner = db.list("""
                SELECT coalesce(ll.name, 'Your own units') AS owner, ll.kra_pin, coalesce(sum(pm.amount), 0) AS received
                FROM payments pm JOIN orders o ON o.id = pm.order_id JOIN leases x ON x.id = o.lease_id JOIN spaces sp ON sp.id = x.space_id
                LEFT JOIN landlords ll ON ll.id = sp.landlord_id
                WHERE sp.kind = 'APARTMENT' AND o.channel <> 'DEPOSIT' AND pm.method <> 'WHT'
                  AND (pm.created_at AT TIME ZONE 'Africa/Nairobi')::date BETWEEN cast(:f as date) AND cast(:e as date)
                GROUP BY ll.name, ll.kra_pin ORDER BY 1""", "f", first, "e", last);
        for (Map<String, Object> r : byOwner) {
            r.put("mri", ((BigDecimal) r.get("received")).multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }
        BigDecimal received = sum(byOwner, "received");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period.toString());
        out.put("rate", rate);
        out.put("residentialReceived", received);
        out.put("mri", received.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        out.put("fileBy", period.plusMonths(1).atDay(20).toString());
        out.put("byOwner", byOwner);
        out.put("whtCertificates", db.list("""
                SELECT r.reference AS certificate, r.amount, r.received_on, sp.name AS unit_name, c.name AS tenant_name
                FROM lease_receipts r JOIN leases x ON x.id = r.lease_id JOIN spaces sp ON sp.id = x.space_id JOIN customers c ON c.id = x.customer_id
                WHERE r.method = 'WHT' AND r.received_on BETWEEN cast(:f as date) AND cast(:e as date) ORDER BY r.received_on""", "f", first, "e", last));
        return out;
    }

    static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(r -> (BigDecimal) r.get(key)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String day(LocalDate d) {
        return DAY.format(d);
    }
}
