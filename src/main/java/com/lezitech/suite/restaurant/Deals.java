package com.lezitech.suite.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * R6: deals and happy hour, priced when ordered.
 * <ul>
 *   <li>Happy hour (PERCENT, PRICE): the dish's price at the moment it's added to the bill.</li>
 *   <li>BUNDLE and BOGO: worked out on the whole open bill each time it changes; only dishes ordered inside the deal's
 *       days and hours count, each dish counts towards one deal only, and the saving is a "Deal: ..." line.</li>
 * </ul>
 */
@Service
public class Deals {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter SAY = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final Db db;

    public Deals(Db db) {
        this.db = db;
    }

    // ---------------- when a deal is on ----------------

    static boolean on(Map<String, Object> deal, Instant at) {
        ZonedDateTime z = at.atZone(NAIROBI);
        Object days = deal.get("days");
        List<String> d = days instanceof Object[] a ? java.util.Arrays.stream(a).map(String::valueOf).toList()
                : days instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        if (!d.isEmpty() && !d.contains(DAYS.get(z.getDayOfWeek().getValue() - 1))) {
            return false;
        }
        LocalTime from = time(deal.get("timeFrom"));
        LocalTime to = time(deal.get("timeTo"));
        if (from == null || to == null) {
            return true;
        }
        LocalTime t = z.toLocalTime();
        // "10 pm to 2 am" runs past midnight.
        return from.isBefore(to) ? !t.isBefore(from) && t.isBefore(to) : !t.isBefore(from) || t.isBefore(to);
    }

    public static LocalTime time(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Time t) {
            return t.toLocalTime();
        }
        if (o instanceof LocalTime t) {
            return t;
        }
        return LocalTime.parse(o.toString().length() > 5 ? o.toString().substring(0, 5) : o.toString());
    }

    /** Put each dish's happy-hour price on a list of dishes (happyPrice, happyUntil, happyDeal), loading the deals once. */
    public void markHappy(List<Map<String, Object>> items) {
        List<Map<String, Object>> loaded = active();
        if (loaded.stream().noneMatch(d -> List.of("PERCENT", "PRICE").contains(d.get("kind")))) {
            return;
        }
        Instant now = Instant.now();
        for (Map<String, Object> i : items) {
            Happy h = happyPrice(i, now, loaded);
            if (h != null) {
                i.put("happyPrice", h.price());
                i.put("happyUntil", h.until());
                i.put("happyDeal", h.dealName());
            }
        }
    }

    public List<Map<String, Object>> active() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : db.list("SELECT * FROM menu_deals WHERE active ORDER BY created_at")) {
            Object days = d.get("days");
            if (days instanceof java.sql.Array arr) {
                try {
                    d.put("days", arr.getArray());
                } catch (Exception e) {
                    d.put("days", new String[0]);
                }
            }
            out.add(d);
        }
        return out;
    }

    private static Set<String> ids(JsonNode items) {
        Set<String> s = new HashSet<>();
        if (items != null) {
            items.forEach(i -> s.add(i.path("itemId").asText()));
        }
        return s;
    }

    private static Set<String> cats(Object categories) {
        Set<String> s = new HashSet<>();
        if (categories instanceof java.sql.Array arr) {
            try {
                for (Object o : (Object[]) arr.getArray()) {
                    s.add(String.valueOf(o));
                }
            } catch (Exception ignored) {
                // no categories
            }
        } else if (categories instanceof Object[] a) {
            for (Object o : a) {
                s.add(String.valueOf(o));
            }
        } else if (categories instanceof List<?> l) {
            l.forEach(o -> s.add(String.valueOf(o)));
        }
        return s;
    }

    // ---------------- happy hour: the price when ordered ----------------

    public record Happy(BigDecimal price, UUID dealId, String dealName, String until) {
    }

    /** The happy-hour price of a dish right now, if a deal makes it cheaper; null if none. */
    public Happy happyPrice(Map<String, Object> item, Instant at) {
        return happyPrice(item, at, active());
    }

    /** As above, with the active deals already loaded (for a whole menu at once). */
    public Happy happyPrice(Map<String, Object> item, Instant at, List<Map<String, Object>> loaded) {
        BigDecimal base = (BigDecimal) item.get("price");
        String id = String.valueOf(item.get("id"));
        String cat = item.get("categoryId") == null ? null : item.get("categoryId").toString();
        Happy best = null;
        for (Map<String, Object> d : loaded) {
            String kind = (String) d.get("kind");
            if (!("PERCENT".equals(kind) || "PRICE".equals(kind)) || !on(d, at)) {
                continue;
            }
            BigDecimal p = null;
            JsonNode items = (JsonNode) d.get("items");
            if ("PRICE".equals(kind)) {
                for (JsonNode i : items) {
                    if (id.equals(i.path("itemId").asText()) && i.path("price").isNumber()) {
                        p = i.path("price").decimalValue();
                    }
                }
            } else if (ids(items).contains(id) || (cat != null && cats(d.get("categories")).contains(cat))) {
                BigDecimal pct = (BigDecimal) d.get("percent");
                p = base.multiply(BigDecimal.ONE.subtract(pct.movePointLeft(2))).setScale(0, RoundingMode.HALF_UP);
            }
            if (p != null && p.compareTo(base) < 0 && p.signum() >= 0 && (best == null || p.compareTo(best.price()) < 0)) {
                LocalTime to = time(d.get("timeTo"));
                best = new Happy(p, UUID.fromString(String.valueOf(d.get("id"))), (String) d.get("name"), to == null ? null : to.format(SAY).toLowerCase());
            }
        }
        return best;
    }

    // ---------------- bundles and buy-one-get-one on the bill ----------------

    private static final class Unit {
        final UUID itemId;
        final String categoryId;
        final BigDecimal price;
        final Instant at;
        boolean used;

        Unit(UUID itemId, String categoryId, BigDecimal price, Instant at) {
            this.itemId = itemId;
            this.categoryId = categoryId;
            this.price = price;
            this.at = at;
        }
    }

    /** Work out the bundle and buy-one-get-one savings on an open bill again, as "Deal: ..." lines. */
    public void apply(UUID orderId) {
        String status = db.value("SELECT status FROM orders WHERE id = :id", String.class, "id", orderId);
        if (!"OPEN".equals(status)) {
            return;
        }
        db.exec("DELETE FROM order_lines WHERE order_id = :id AND deal_id IS NOT NULL", "id", orderId);
        List<Map<String, Object>> deals = active().stream().filter(d -> List.of("BUNDLE", "BOGO").contains(d.get("kind"))).toList();
        if (deals.isEmpty()) {
            return;
        }
        // Each portion on the bill, at what it was charged (happy-hour price included; add-ons not counted).
        List<Unit> units = new ArrayList<>();
        for (Map<String, Object> l : db.list("""
                SELECT l.item_id, i.category_id, l.qty, l.unit_price, l.discount, extract(epoch FROM l.created_at) AS at_epoch,
                       coalesce((SELECT sum((o->>'price')::numeric) FROM jsonb_array_elements(l.options) o), 0) AS extras
                FROM order_lines l JOIN items i ON i.id = l.item_id
                WHERE l.order_id = :id AND l.parent_line_id IS NULL AND l.deal_id IS NULL AND l.line_total > 0 AND i.kind <> 'COMBO'""", "id", orderId)) {
            int n = ((BigDecimal) l.get("qty")).intValue();
            BigDecimal each = ((BigDecimal) l.get("unitPrice")).subtract((BigDecimal) l.get("extras"));
            Instant at = Instant.ofEpochMilli(Math.round(((Number) l.get("atEpoch")).doubleValue() * 1000));
            for (int k = 0; k < n; k++) {
                units.add(new Unit(UUID.fromString(l.get("itemId").toString()), l.get("categoryId") == null ? null : l.get("categoryId").toString(), each, at));
            }
        }
        for (Map<String, Object> d : deals) {
            BigDecimal saving = "BUNDLE".equals(d.get("kind")) ? bundle(d, units) : bogo(d, units);
            if (saving != null && saving.signum() > 0) {
                db.exec("""
                        INSERT INTO order_lines (tenant_id, order_id, item_id, name, qty, unit_price, tax_rate, discount, line_total,
                                                 options, kitchen_status, deal_id)
                        VALUES (:t, :o, NULL, :name, 1, :neg, 16, 0, :neg, '[]'::jsonb, 'SERVED', :d)""",
                        "o", orderId, "name", "Deal: " + d.get("name"), "neg", saving.negate(), "d", UUID.fromString(String.valueOf(d.get("id"))));
            }
        }
    }

    /** As many full sets as are on the bill: each set costs the deal price instead of its parts. */
    private BigDecimal bundle(Map<String, Object> d, List<Unit> units) {
        JsonNode items = (JsonNode) d.get("items");
        BigDecimal price = (BigDecimal) d.get("dealPrice");
        if (items == null || items.isEmpty() || price == null) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        while (true) {
            List<Unit> set = new ArrayList<>();
            boolean full = true;
            for (JsonNode need : items) {
                UUID id = UUID.fromString(need.path("itemId").asText());
                int qty = Math.max(1, need.path("qty").asInt(1));
                List<Unit> found = units.stream().filter(u -> !u.used && !set.contains(u) && u.itemId.equals(id) && on(d, u.at))
                        .sorted(Comparator.comparing((Unit u) -> u.price).reversed()).limit(qty).toList();
                if (found.size() < qty) {
                    full = false;
                    break;
                }
                set.addAll(found);
            }
            if (!full) {
                break;
            }
            BigDecimal parts = set.stream().map(u -> u.price).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (parts.compareTo(price) <= 0) {
                break;
            }
            set.forEach(u -> u.used = true);
            total = total.add(parts.subtract(price));
        }
        return total;
    }

    /** Buy N get M free: of every N+M portions that count, the cheapest M are free. */
    private BigDecimal bogo(Map<String, Object> d, List<Unit> units) {
        int buy = d.get("buy") == null ? 1 : ((Number) d.get("buy")).intValue();
        int get = d.get("get") == null ? 1 : ((Number) d.get("get")).intValue();
        Set<String> ids = ids((JsonNode) d.get("items"));
        Set<String> cats = cats(d.get("categories"));
        List<Unit> pool = new ArrayList<>(units.stream().filter(u -> !u.used && on(d, u.at)
                && (ids.contains(u.itemId.toString()) || (u.categoryId != null && cats.contains(u.categoryId)))).toList());
        pool.sort(Comparator.comparing((Unit u) -> u.price).reversed());
        int sets = pool.size() / (buy + get);
        if (sets == 0) {
            return null;
        }
        // The dearest go towards "buy", the cheapest are the free ones.
        List<Unit> counted = pool.subList(0, sets * (buy + get));
        List<Unit> free = counted.subList(counted.size() - sets * get, counted.size());
        counted.forEach(u -> u.used = true);
        return free.stream().map(u -> u.price).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------- the owner's list ----------------

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Instant now = Instant.now();
        for (Map<String, Object> d : active()) {
            d.put("onNow", on(d, now));
            out.add(d);
        }
        for (Map<String, Object> d : db.list("SELECT * FROM menu_deals WHERE NOT active ORDER BY created_at")) {
            d.put("onNow", false);
            Object days = d.get("days");
            if (days instanceof java.sql.Array arr) {
                try {
                    d.put("days", arr.getArray());
                } catch (Exception e) {
                    d.put("days", new String[0]);
                }
            }
            out.add(d);
        }
        return out;
    }

    public Map<String, Object> save(UUID id, Body b) {
        TenantContext.requireRole("MANAGER");
        String name = b.str("name", "").trim();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiException.bad("Give the deal a short name, e.g. Happy hour beers");
        }
        String kind = b.str("kind", "").toUpperCase();
        if (!List.of("BUNDLE", "PERCENT", "PRICE", "BOGO").contains(kind)) {
            throw ApiException.bad("Choose what kind of deal it is");
        }
        List<String> days = new ArrayList<>();
        if (b.raw("days") instanceof List<?> l) {
            for (Object o : l) {
                String x = String.valueOf(o).toUpperCase();
                if (DAYS.contains(x) && !days.contains(x)) {
                    days.add(x);
                }
            }
        }
        LocalTime from = b.has("timeFrom") && !b.str("timeFrom").isBlank() ? parse(b.str("timeFrom")) : null;
        LocalTime to = b.has("timeTo") && !b.str("timeTo").isBlank() ? parse(b.str("timeTo")) : null;
        if ((from == null) != (to == null) || (from != null && from.equals(to))) {
            throw ApiException.bad("Give both a start and an end time, or neither for all day");
        }
        List<Map<String, Object>> items = b.list("items") == null ? List.of() : b.list("items");
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Map<String, Object> i : items) {
            Body ib = new Body(i);
            UUID itemId = UUID.fromString(ib.str("itemId"));
            db.one("Dish", "SELECT id FROM items WHERE id = :id", "id", itemId);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("itemId", itemId.toString());
            if ("BUNDLE".equals(kind)) {
                c.put("qty", Math.max(1, Math.min(20, ib.integer("qty", 1))));
            }
            if ("PRICE".equals(kind)) {
                BigDecimal p = ib.num("price", null);
                if (p == null || p.signum() < 0) {
                    throw ApiException.bad("Give each dish its happy-hour price");
                }
                c.put("price", p);
            }
            clean.add(c);
        }
        List<UUID> categories = new ArrayList<>();
        if (b.raw("categories") instanceof List<?> l) {
            for (Object o : l) {
                categories.add(UUID.fromString(String.valueOf(o)));
            }
        }
        BigDecimal percent = null;
        BigDecimal dealPrice = null;
        Integer buy = null;
        Integer get = null;
        switch (kind) {
            case "BUNDLE" -> {
                dealPrice = b.num("dealPrice", null);
                if (clean.size() < 2 && clean.stream().mapToInt(c -> (Integer) c.get("qty")).sum() < 2) {
                    throw ApiException.bad("A bundle needs at least two dishes");
                }
                if (dealPrice == null || dealPrice.signum() <= 0) {
                    throw ApiException.bad("Give the bundle's price");
                }
            }
            case "PERCENT" -> {
                percent = b.num("percent", null);
                if (percent == null || percent.signum() <= 0 || percent.compareTo(new BigDecimal("90")) > 0) {
                    throw ApiException.bad("The % off must be between 1 and 90");
                }
                if (clean.isEmpty() && categories.isEmpty()) {
                    throw ApiException.bad("Choose the dishes or categories it's for");
                }
            }
            case "PRICE" -> {
                if (clean.isEmpty()) {
                    throw ApiException.bad("Choose the dishes and their happy-hour price");
                }
            }
            default -> {
                buy = Math.max(1, Math.min(10, b.integer("buy", 1)));
                get = Math.max(1, Math.min(10, b.integer("get", 1)));
                if (clean.isEmpty() && categories.isEmpty()) {
                    throw ApiException.bad("Choose the dishes or categories it's for");
                }
            }
        }
        Object[] p = {"name", name, "kind", kind, "active", b.bool("active", true), "days", days.toArray(new String[0]),
                "from", from == null ? null : Time.valueOf(from), "to", to == null ? null : Time.valueOf(to), "items", db.toJson(clean),
                "cats", categories.toArray(new UUID[0]), "pct", percent, "price", dealPrice, "buy", buy, "get", get, "id", id};
        if (id == null) {
            id = db.insert("""
                    INSERT INTO menu_deals (tenant_id, name, kind, active, days, time_from, time_to, items, categories, percent, deal_price, buy, get)
                    VALUES (:t, :name, :kind, :active, :days, :from, :to, cast(:items as jsonb), :cats, :pct, :price, :buy, :get)
                    RETURNING id""", p);
        } else {
            db.one("Deal", "SELECT id FROM menu_deals WHERE id = :id", "id", id);
            db.exec("""
                    UPDATE menu_deals SET name = :name, kind = :kind, active = :active, days = :days, time_from = :from, time_to = :to,
                                     items = cast(:items as jsonb), categories = :cats, percent = :pct, deal_price = :price, buy = :buy, get = :get
                    WHERE id = :id""", p);
        }
        UUID saved = id;
        return list().stream().filter(d -> saved.toString().equals(String.valueOf(d.get("id")))).findFirst().orElseThrow();
    }

    public void delete(UUID id) {
        TenantContext.requireRole("MANAGER");
        db.exec("DELETE FROM menu_deals WHERE id = :id", "id", id);
    }

    static LocalTime parse(String s) {
        try {
            return LocalTime.parse(s.length() > 5 ? s.substring(0, 5) : s, HM);
        } catch (Exception e) {
            throw ApiException.bad("Write times like 17:00");
        }
    }

    /** For the menu: what's on now, in plain words (e.g. "Happy hour beers until 7:00 pm"). */
    public List<Map<String, Object>> onNow() {
        List<Map<String, Object>> out = new ArrayList<>();
        Instant now = Instant.now();
        for (Map<String, Object> d : active()) {
            if (!on(d, now)) {
                continue;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("name", d.get("name"));
            m.put("kind", d.get("kind"));
            LocalTime to = time(d.get("timeTo"));
            m.put("until", to == null ? null : to.format(SAY).toLowerCase());
            m.put("text", describe(d));
            out.add(m);
        }
        return out;
    }

    String describe(Map<String, Object> d) {
        return switch ((String) d.get("kind")) {
            case "BUNDLE" -> {
                List<String> parts = new ArrayList<>();
                for (JsonNode i : (JsonNode) d.get("items")) {
                    String n = db.value("SELECT name FROM items WHERE id = cast(:id as uuid)", String.class, "id", i.path("itemId").asText());
                    int q = i.path("qty").asInt(1);
                    parts.add((q > 1 ? q + " × " : "") + n);
                }
                yield String.join(" + ", parts) + " for KES " + ((BigDecimal) d.get("dealPrice")).toBigInteger();
            }
            case "PERCENT" -> ((BigDecimal) d.get("percent")).stripTrailingZeros().toPlainString() + "% off";
            case "PRICE" -> "Special prices";
            default -> "Buy " + d.get("buy") + ", get " + d.get("get") + " free";
        };
    }
}
