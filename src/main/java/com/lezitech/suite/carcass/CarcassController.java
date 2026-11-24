package com.lezitech.suite.carcass;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.stock.StockService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Butcheries: receive a carcass (or part) bought whole, cut it up into by-weight items, and see what you got from it.
 * Cost is spread over the cuts by weight: every kilo of meat to sell carries the same share of what the carcass cost, so
 * waste and trim are paid for by the meat that is sold. Each cut's item cost becomes the weighted average of what was
 * on the shelf and what this carcass added, so profit reports see a real cost per kilo.
 */
@RestController
@RequestMapping("/api/carcasses")
@Transactional
public class CarcassController {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final BigDecimal MAX_KG = BigDecimal.valueOf(2000);

    private final Db db;
    private final StockService stock;
    private final ActivityLog activity;
    private final CarcassFeature feature;

    public CarcassController(Db db, StockService stock, ActivityLog activity, CarcassFeature feature) {
        this.feature = feature;
        this.db = db;
        this.stock = stock;
        this.activity = activity;
    }

    private static String kg(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal kilos(BigDecimal v, String what) {
        if (v == null || v.signum() < 0) {
            throw ApiException.bad("Say how many kg " + what);
        }
        if (v.stripTrailingZeros().scale() > 3) {
            throw ApiException.bad("Weigh " + what + " to 3 decimals at most, for example 0.750 kg");
        }
        if (v.compareTo(MAX_KG) > 0) {
            throw ApiException.bad("That is too many kg for " + what);
        }
        return v;
    }

    /** Receive a carcass: {kind, supplier, weightKg, cost, date, note}. */
    @PostMapping
    public Map<String, Object> receive(@RequestBody Map<String, Object> raw) {
        feature.require();
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        Body b = new Body(raw);
        String kind = b.str("kind", "").trim();
        if (kind.isEmpty() || kind.length() > 40) {
            throw ApiException.bad("Say what kind it is: beef, goat, mutton, pork, chicken or type your own");
        }
        BigDecimal weight = kilos(b.num("weightKg", null), "the whole carcass weighs");
        if (weight.signum() == 0) {
            throw ApiException.bad("Say how many kg the carcass weighs");
        }
        BigDecimal cost = b.num("cost", null);
        if (cost == null || cost.signum() < 0) {
            throw ApiException.bad("Say what you paid for it in total");
        }
        LocalDate day = b.date("date") == null ? LocalDate.now(NAIROBI) : b.date("date");
        if (day.isAfter(LocalDate.now(NAIROBI))) {
            throw ApiException.bad("The date can't be in the future");
        }
        UUID id = db.insert("""
                INSERT INTO carcasses (tenant_id, kind, supplier, weight_kg, cost, received_on, note, user_id)
                VALUES (:t, :kind, :supplier, :w, :cost, :day, :note, :u) RETURNING id""",
                "kind", kind, "supplier", blankToNull(b.str("supplier", null), 120), "w", weight, "cost", cost.setScale(2, RoundingMode.HALF_UP),
                "day", day, "note", blankToNull(b.str("note", null), 200));
        activity.record("CARCASS", ActivityLog.INFO, "Carcass received: " + kg(weight) + " kg " + kind.toLowerCase() + " for "
                + cost.setScale(0, RoundingMode.HALF_UP).toPlainString(), null, null, cost, null, Map.of());
        return one(id);
    }

    /** Cut it up: {cuts: [{itemId, kg}], wasteKg}. Once only: stock goes up by each cut's kilos and cost is spread by weight. */
    @PostMapping("/{id}/cut")
    public Map<String, Object> cut(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        feature.require();
        TenantContext.requireRole("MANAGER", "STOREKEEPER");
        Map<String, Object> c = db.one("Carcass", "SELECT * FROM carcasses WHERE id = :id FOR UPDATE", "id", id);
        if (!"RECEIVED".equals(c.get("status"))) {
            throw ApiException.conflict("This carcass has already been cut up");
        }
        Body b = new Body(raw);
        BigDecimal weight = (BigDecimal) c.get("weightKg");
        BigDecimal cost = (BigDecimal) c.get("cost");
        BigDecimal waste = b.has("wasteKg") ? kilos(b.num("wasteKg"), "of waste") : BigDecimal.ZERO;
        List<Map<String, Object>> rows = b.has("cuts") ? b.list("cuts") : null;
        if (rows == null || rows.isEmpty()) {
            throw ApiException.bad("Add at least one cut, with how many kg you got");
        }
        Set<UUID> seen = new HashSet<>();
        Map<UUID, BigDecimal> kgBy = new LinkedHashMap<>();
        BigDecimal cutTotal = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            Body l = new Body(r);
            UUID itemId = UUID.fromString(l.str("itemId"));
            Map<String, Object> item = db.one("Cut", "SELECT name, track_stock, sold_by_weight FROM items WHERE id = :id AND active", "id", itemId);
            String name = (String) item.get("name");
            if (!seen.add(itemId)) {
                throw ApiException.bad(name + " is in the list twice. Add its kg together");
            }
            if (!Boolean.TRUE.equals(item.get("soldByWeight")) || !Boolean.TRUE.equals(item.get("trackStock"))) {
                throw ApiException.bad(name + " is not sold by the kg with stock counted. Switch that on in Products first");
            }
            BigDecimal q = kilos(l.num("kg", null), name);
            if (q.signum() == 0) {
                continue;
            }
            kgBy.put(itemId, q);
            cutTotal = cutTotal.add(q);
        }
        if (kgBy.isEmpty()) {
            throw ApiException.bad("Add at least one cut, with how many kg you got");
        }
        BigDecimal used = cutTotal.add(waste);
        if (used.compareTo(weight) > 0) {
            throw ApiException.bad("Cuts and waste come to " + kg(used) + " kg, but the carcass was only " + kg(weight) + " kg. Check the weights");
        }
        BigDecimal unaccounted = weight.subtract(used);

        UUID loc = stock.defaultLocation();
        String ref = "Carcass: " + c.get("kind") + (c.get("supplier") == null ? "" : " from " + c.get("supplier"));
        BigDecimal left = cost;
        int n = 0;
        for (Map.Entry<UUID, BigDecimal> e : kgBy.entrySet()) {
            n++;
            BigDecimal q = e.getValue();
            BigDecimal share = n == kgBy.size() ? left : cost.multiply(q).divide(cutTotal, 2, RoundingMode.HALF_UP).min(left);
            left = left.subtract(share);
            // Weighted average with what is already on the shelf (a negative count counts as nothing).
            // Lock the item first so two carcasses cut at once can not both average from the same old cost.
            BigDecimal oldCost = db.value("SELECT coalesce(cost, 0) FROM items WHERE id = :i FOR UPDATE", BigDecimal.class, "i", e.getKey());
            BigDecimal onHand = db.value("SELECT coalesce(sum(qty), 0) FROM stock_levels WHERE item_id = :i", BigDecimal.class, "i", e.getKey()).max(BigDecimal.ZERO);
            BigDecimal avg = onHand.multiply(oldCost).add(share).divide(onHand.add(q), 2, RoundingMode.HALF_UP);
            db.exec("INSERT INTO carcass_cuts (tenant_id, carcass_id, item_id, kg, cost_share) VALUES (:t, :c, :i, :kg, :share)",
                    "c", id, "i", e.getKey(), "kg", q, "share", share);
            stock.move(e.getKey(), loc, q, "RECEIVE", ref, "Cut from a carcass");
            db.exec("UPDATE items SET cost = :cost WHERE id = :i", "i", e.getKey(), "cost", avg);
        }
        db.exec("UPDATE carcasses SET status = 'CUT', waste_kg = :w, unaccounted_kg = :u, cut_at = now() WHERE id = :id",
                "id", id, "w", waste, "u", unaccounted);
        String says = "Carcass cut up: " + kg(weight) + " kg " + String.valueOf(c.get("kind")).toLowerCase() + " gave " + kg(cutTotal)
                + " kg of meat to sell, " + kg(waste) + " kg waste" + (unaccounted.signum() > 0 ? ", " + kg(unaccounted) + " kg not accounted for" : "");
        activity.record("CARCASS", unaccounted.signum() > 0 ? ActivityLog.NOTICE : ActivityLog.INFO, says, null, null, cost, null, Map.of());
        return one(id);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        feature.require();
        TenantContext.requireRole("MANAGER", "STOREKEEPER", "ACCOUNTANT");
        return one(id);
    }

    /** The last 90 days of carcasses, newest first, and this week's summary. */
    @GetMapping
    public Map<String, Object> list() {
        feature.require();
        TenantContext.requireRole("MANAGER", "STOREKEEPER", "ACCOUNTANT");
        List<Map<String, Object>> all = build(null);
        LocalDate from = LocalDate.now(NAIROBI).minusDays(6);
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal sold = BigDecimal.ZERO;
        BigDecimal money = BigDecimal.ZERO;
        BigDecimal wasted = BigDecimal.ZERO;
        BigDecimal unaccounted = BigDecimal.ZERO;
        for (Map<String, Object> c : all) {
            if (LocalDate.parse(c.get("receivedOn").toString()).isBefore(from)) {
                continue;
            }
            in = in.add((BigDecimal) c.get("weightKg"));
            cost = cost.add((BigDecimal) c.get("cost"));
            sold = sold.add((BigDecimal) c.get("soldKg"));
            money = money.add((BigDecimal) c.get("soldMoney"));
            wasted = wasted.add((BigDecimal) c.get("wasteKg"));
            unaccounted = unaccounted.add((BigDecimal) c.get("unaccountedKg"));
        }
        // Waste written down during the day for meat sold by the kg also counts.
        BigDecimal daily = db.value("""
                SELECT coalesce(sum(w.qty), 0) FROM waste_entries w JOIN items i ON i.id = w.item_id
                WHERE i.sold_by_weight AND w.created_at >= :from""", BigDecimal.class,
                "from", java.sql.Timestamp.from(from.atStartOfDay(NAIROBI).toInstant()));
        Map<String, Object> week = new LinkedHashMap<>();
        week.put("from", from.toString());
        week.put("carcassKgIn", in);
        week.put("carcassCost", cost);
        week.put("kgSold", sold);
        week.put("moneyIn", money);
        week.put("kgWasted", wasted.add(daily));
        week.put("kgNotAccountedFor", unaccounted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("week", week);
        out.put("carcasses", all);
        return out;
    }

    private Map<String, Object> one(UUID id) {
        List<Map<String, Object>> found = build(id);
        if (found.isEmpty()) {
            throw ApiException.notFound("Carcass");
        }
        return found.get(0);
    }

    /**
     * Each carcass with its cuts. Kilos sold so far are approximate: what the till sold of a cut since it was first cut
     * up, handed to the carcasses that produced it oldest first, never more than each one produced.
     */
    private List<Map<String, Object>> build(UUID only) {
        List<Map<String, Object>> cuts = db.list("""
                SELECT k.carcass_id, k.item_id, k.kg, k.cost_share, i.name
                FROM carcass_cuts k JOIN carcasses c ON c.id = k.carcass_id JOIN items i ON i.id = k.item_id
                WHERE c.received_on >= current_date - 90 OR c.id = cast(:one as uuid)
                ORDER BY c.cut_at, c.created_at, i.name""", "one", only);
        Map<UUID, Map<String, BigDecimal>> sales = new HashMap<>();
        for (Map<String, Object> s : db.list("""
                SELECT l.item_id, sum(l.qty) AS kg, sum(l.line_total) AS money
                FROM order_lines l JOIN orders o ON o.id = l.order_id
                WHERE o.status IN ('PAID','CREDIT') AND o.kind <> 'QUOTE' AND l.item_id IN (SELECT item_id FROM carcass_cuts)
                  AND o.created_at >= (SELECT min(c.cut_at) FROM carcass_cuts k JOIN carcasses c ON c.id = k.carcass_id
                                       WHERE k.item_id = l.item_id AND (c.received_on >= current_date - 90 OR c.id = cast(:one as uuid)))
                GROUP BY l.item_id""", "one", only)) {
            sales.put(UUID.fromString(s.get("itemId").toString()), new HashMap<>(Map.of("kg", (BigDecimal) s.get("kg"), "money", (BigDecimal) s.get("money"))));
        }
        Map<UUID, List<Map<String, Object>>> byCarcass = new HashMap<>();
        for (Map<String, Object> k : cuts) {
            UUID item = UUID.fromString(k.get("itemId").toString());
            Map<String, BigDecimal> pool = sales.get(item);
            BigDecimal made = (BigDecimal) k.get("kg");
            BigDecimal soldKg = BigDecimal.ZERO;
            BigDecimal soldMoney = BigDecimal.ZERO;
            if (pool != null && pool.get("kg").signum() > 0) {
                BigDecimal poolKg = pool.get("kg");
                BigDecimal take = poolKg.min(made);
                soldMoney = pool.get("money").multiply(take).divide(poolKg, 2, RoundingMode.HALF_UP);
                soldKg = take;
                pool.put("money", pool.get("money").subtract(soldMoney));
                pool.put("kg", poolKg.subtract(take));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", item);
            m.put("name", k.get("name"));
            m.put("kg", made);
            m.put("costShare", k.get("costShare"));
            m.put("costPerKg", ((BigDecimal) k.get("costShare")).divide(made, 2, RoundingMode.HALF_UP));
            m.put("soldKg", soldKg);
            m.put("soldMoney", soldMoney);
            byCarcass.computeIfAbsent(UUID.fromString(k.get("carcassId").toString()), x -> new ArrayList<>()).add(m);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> rows = only == null
                ? db.list("SELECT c.*, u.name AS by_name FROM carcasses c LEFT JOIN users u ON u.id = c.user_id WHERE c.received_on >= current_date - 90 ORDER BY c.received_on DESC, c.created_at DESC LIMIT 200")
                : db.list("SELECT c.*, u.name AS by_name FROM carcasses c LEFT JOIN users u ON u.id = c.user_id WHERE c.id = :id", "id", only);
        for (Map<String, Object> c : rows) {
            Map<String, Object> m = new LinkedHashMap<>(c);
            List<Map<String, Object>> mine = byCarcass.getOrDefault(UUID.fromString(c.get("id").toString()), List.of());
            BigDecimal cutKg = BigDecimal.ZERO;
            BigDecimal soldKg = BigDecimal.ZERO;
            BigDecimal soldMoney = BigDecimal.ZERO;
            for (Map<String, Object> x : mine) {
                cutKg = cutKg.add((BigDecimal) x.get("kg"));
                soldKg = soldKg.add((BigDecimal) x.get("soldKg"));
                soldMoney = soldMoney.add((BigDecimal) x.get("soldMoney"));
            }
            BigDecimal w = (BigDecimal) c.get("weightKg");
            m.put("cuts", mine);
            m.put("cutKg", cutKg);
            m.put("soldKg", soldKg);
            m.put("soldMoney", soldMoney);
            m.put("yieldPercent", cutKg.multiply(BigDecimal.valueOf(100)).divide(w, 1, RoundingMode.HALF_UP));
            out.add(m);
        }
        return out;
    }

    private static String blankToNull(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
