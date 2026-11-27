package com.lezitech.suite.finance;

import com.lezitech.suite.common.Db;
import com.lezitech.suite.mpesa.PayHeroFees;
import com.lezitech.suite.restaurant.Recipes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Did I make money?": what came in (sales without VAT), what it cost to make or buy what was sold, what was lost
 * (waste, stock-take shortages, stock used in the business), what the business spent (expenses, commissions paid,
 * M-Pesa charges), and what's left. Compared with the same number of days just before.
 *
 * Cost of what was sold: a dish or service with a recipe costs its ingredients (or the products it uses up); anything
 * else costs the buying price saved on the line at the time of sale ({@code order_lines.unit_cost}).
 */
@Service
@Transactional(readOnly = true)
public class ProfitLossService {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final String SOLD = " o.status IN ('PAID','CREDIT') AND o.kind <> 'QUOTE' AND o.channel <> 'DEPOSIT'";

    private final Db db;
    private final Recipes recipes;
    private final PayHeroFees payHeroFees;

    public ProfitLossService(Db db, Recipes recipes, PayHeroFees payHeroFees) {
        this.db = db;
        this.recipes = recipes;
        this.payHeroFees = payHeroFees;
    }

    public Map<String, Object> report(LocalDate from, LocalDate to) {
        Map<String, Object> now = period(from, to);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        Map<String, Object> before = period(from.minusDays(days), from.minusDays(1));
        now.put("previous", Map.of("from", before.get("from"), "to", before.get("to"), "sales", before.get("sales"),
                "grossProfit", before.get("grossProfit"), "netProfit", before.get("netProfit")));
        return now;
    }

    Map<String, Object> period(LocalDate from, LocalDate to) {
        Object[] range = {"from", Timestamp.from(from.atStartOfDay(NAIROBI).toInstant()),
                "to", Timestamp.from(to.plusDays(1).atStartOfDay(NAIROBI).toInstant()), "d1", from, "d2", to};
        String window = SOLD + " AND o.closed_at >= :from AND o.closed_at < :to";

        Map<String, Object> totals = db.one("Totals", "SELECT coalesce(sum(o.total), 0) AS total, coalesce(sum(o.tax), 0) AS tax, count(*) AS orders FROM orders o WHERE "
                + " " + window, range);
        BigDecimal sales = n(totals.get("total")).subtract(n(totals.get("tax")));

        // What was sold, per item, and what it cost.
        Map<UUID, BigDecimal> unit = recipes.unitCosts();
        BigDecimal cogs = BigDecimal.ZERO;
        for (Map<String, Object> r : db.list("""
                SELECT l.item_id, sum(l.qty * coalesce(l.pack_size, 1)) AS qty,
                       sum(l.qty * coalesce(l.pack_size, 1) * coalesce(l.unit_cost, i.cost, 0)) AS saved_cost
                FROM order_lines l JOIN orders o ON o.id = l.order_id LEFT JOIN items i ON i.id = l.item_id
                WHERE l.item_id IS NOT NULL AND """ + window + " GROUP BY l.item_id", range)) {
            UUID item = UUID.fromString(r.get("itemId").toString());
            Map<UUID, BigDecimal> uses = recipes.usesFor(item, null);
            if (uses.isEmpty()) {
                cogs = cogs.add(n(r.get("savedCost")));
            } else {
                BigDecimal each = BigDecimal.ZERO;
                for (Map.Entry<UUID, BigDecimal> u : uses.entrySet()) {
                    each = each.add(u.getValue().multiply(unit.getOrDefault(u.getKey(), costOf(u.getKey()))));
                }
                cogs = cogs.add(each.multiply(n(r.get("qty"))));
            }
        }
        cogs = money(cogs);
        BigDecimal gross = sales.subtract(cogs);

        // Stock lost or used up, at buying price.
        BigDecimal waste = BigDecimal.ZERO, shortages = BigDecimal.ZERO, usedInHouse = BigDecimal.ZERO;
        for (Map<String, Object> m : db.list("""
                SELECT m.item_id, m.reason, sum(m.qty) AS qty, coalesce(max(i.cost), 0) AS cost
                FROM stock_moves m JOIN items i ON i.id = m.item_id
                WHERE m.created_at >= :from AND m.created_at < :to AND m.qty < 0
                  AND m.reason IN ('WASTE', 'GIVE_AWAY', 'EXPIRE', 'ADJUST', 'COUNT', 'USE', 'DELIVERY_SHORT')
                GROUP BY m.item_id, m.reason""", range)) {
            UUID item = UUID.fromString(m.get("itemId").toString());
            BigDecimal value = n(m.get("qty")).negate().multiply(unit.getOrDefault(item, n(m.get("cost"))));
            switch ((String) m.get("reason")) {
                case "WASTE", "GIVE_AWAY", "EXPIRE" -> waste = waste.add(value);
                case "USE" -> usedInHouse = usedInHouse.add(value);
                default -> shortages = shortages.add(value);
            }
        }

        // Spending.
        List<Map<String, Object>> byCategory = db.list("""
                SELECT category, sum(amount) AS amount FROM expenses WHERE spent_on >= :d1 AND spent_on <= :d2
                GROUP BY category ORDER BY sum(amount) DESC""", range);
        BigDecimal expenses = byCategory.stream().map(r -> n(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commissions = db.value("""
                SELECT coalesce(sum(amount), 0) FROM commission_payouts WHERE created_at >= :from AND created_at < :to""", BigDecimal.class, range);
        BigDecimal fees = BigDecimal.ZERO;
        for (Map<String, Object> f : db.list("""
                SELECT amount FROM mpesa_requests WHERE provider = 'PAYHERO' AND status = 'PAID' AND purpose <> 'SUBSCRIPTION'
                  AND tenant_id = cast(:t as uuid) AND created_at >= :from AND created_at < :to""", range)) {
            fees = fees.add(payHeroFees.fee(n(f.get("amount"))));
        }

        List<Map<String, Object>> costs = new ArrayList<>();
        line(costs, "Stock thrown away or given away", money(waste));
        line(costs, "Stock missing at counts", money(shortages));
        line(costs, "Stock used in the business", money(usedInHouse));
        for (Map<String, Object> c : byCategory) {
            line(costs, (String) c.get("category"), money(n(c.get("amount"))));
        }
        line(costs, "Staff commissions paid", money(commissions));
        line(costs, "M-Pesa charges (PayHero)", money(fees));
        BigDecimal otherCosts = costs.stream().map(c -> (BigDecimal) c.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = gross.subtract(otherCosts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("orders", totals.get("orders"));
        out.put("salesWithVat", money(n(totals.get("total"))));
        out.put("vat", money(n(totals.get("tax"))));
        out.put("sales", money(sales));
        out.put("costOfSales", cogs);
        out.put("grossProfit", money(gross));
        out.put("grossMargin", pct(gross, sales));
        out.put("costs", costs);
        out.put("expenses", money(expenses));
        out.put("otherCosts", money(otherCosts));
        out.put("netProfit", money(net));
        out.put("netMargin", pct(net, sales));
        return out;
    }

    private BigDecimal costOf(UUID item) {
        BigDecimal c = db.value("SELECT cost FROM items WHERE id = :id", BigDecimal.class, "id", item);
        return c == null ? BigDecimal.ZERO : c;
    }

    private static void line(List<Map<String, Object>> list, String what, BigDecimal amount) {
        if (amount.signum() != 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("what", what);
            m.put("amount", amount);
            list.add(m);
        }
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? null : part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal n(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
}
