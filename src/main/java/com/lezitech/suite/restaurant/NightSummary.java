package com.lezitech.suite.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.notify.WebPush;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * R8, "close the night in 3 minutes": one summary of the day for the owner, by email and a phone alert, kept to open
 * again and download as a PDF. A business day runs 4 am to 4 am (bars close late). It's sent when the last till closes
 * (after 5 pm), or at the owner's latest time (9 pm unless changed) whatever is still open. On Mondays it adds the
 * plain-words menu report for the past week.
 */
@Service
public class NightSummary {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final LocalTime DAY_STARTS = LocalTime.of(4, 0);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);

    public record Rules(boolean on, LocalTime earliest, LocalTime latest, BigDecimal cashShortAlert, int discountAlertPct, List<String> emails) {
    }

    private final Db db;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Recipes recipes;
    private final Mailer mailer;
    private final WebPush push;

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.bottle.BottleCountController bottleCounts;

    public NightSummary(Db db, JdbcTemplate jdbc, ObjectMapper json, Recipes recipes, Mailer mailer, WebPush push) {
        this.db = db;
        this.jdbc = jdbc;
        this.json = json;
        this.recipes = recipes;
        this.mailer = mailer;
        this.push = push;
    }

    // ---------------- rules ----------------

    public Rules rules(UUID tenant) {
        JsonNode s;
        try {
            s = json.readTree(jdbc.queryForObject("SELECT coalesce(settings->'nightSummary', '{}'::jsonb)::text FROM tenants WHERE id = ?", String.class, tenant));
        } catch (Exception e) {
            s = json.createObjectNode();
        }
        List<String> emails = new ArrayList<>();
        s.path("emails").forEach(e -> emails.add(e.asText()));
        return new Rules(s.path("on").asBoolean(true), time(s.path("earliest").asText("17:00"), LocalTime.of(17, 0)),
                time(s.path("latest").asText("21:00"), LocalTime.of(21, 0)),
                s.path("cashShortAlert").isNumber() ? s.path("cashShortAlert").decimalValue() : new BigDecimal("200"),
                s.path("discountAlertPct").asInt(20), emails);
    }

    public Map<String, Object> rulesView(Rules r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("on", r.on());
        out.put("earliest", r.earliest().toString());
        out.put("latest", r.latest().toString());
        out.put("cashShortAlert", r.cashShortAlert());
        out.put("discountAlertPct", r.discountAlertPct());
        out.put("emails", r.emails());
        return out;
    }

    public void saveRules(Body b) {
        TenantContext.requireRole("MANAGER");
        List<String> emails = new ArrayList<>();
        if (b.raw("emails") instanceof List<?> l) {
            for (Object o : l) {
                String e = String.valueOf(o).trim();
                if (!e.isEmpty()) {
                    if (!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                        throw ApiException.bad(e + " doesn't look like an email");
                    }
                    emails.add(e);
                }
            }
        }
        LocalTime earliest = time(b.str("earliest", "17:00"), null);
        LocalTime latest = time(b.str("latest", "21:00"), null);
        if (earliest == null || latest == null) {
            throw ApiException.bad("Write times like 21:00");
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("on", b.bool("on", true));
        s.put("earliest", earliest.toString());
        s.put("latest", latest.toString());
        s.put("cashShortAlert", b.num("cashShortAlert", new BigDecimal("200")).max(BigDecimal.ZERO));
        s.put("discountAlertPct", Math.max(1, Math.min(100, b.integer("discountAlertPct", 20))));
        s.put("emails", emails.size() > 5 ? emails.subList(0, 5) : emails);
        jdbc.update("UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{nightSummary}', cast(? as jsonb)) WHERE id = ?",
                db.toJson(s), TenantContext.tenantId());
    }

    static LocalTime time(String s, LocalTime fallback) {
        try {
            return LocalTime.parse(s.length() > 5 ? s.substring(0, 5) : s);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** The business day "now" belongs to (before 4 am counts as the day before). */
    public static LocalDate businessDay(Instant at) {
        ZonedDateTime z = at.atZone(NAIROBI);
        return z.toLocalTime().isBefore(DAY_STARTS) ? z.toLocalDate().minusDays(1) : z.toLocalDate();
    }

    static Timestamp start(LocalDate d) {
        return Timestamp.from(d.atTime(DAY_STARTS).atZone(NAIROBI).toInstant());
    }

    // ---------------- the summary ----------------

    private static BigDecimal n(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    /** Everything about one business day, in the shape the email, the page and the PDF all use. */
    public Map<String, Object> build(LocalDate day) {
        Object[] p = {"from", start(day), "to", start(day.plusDays(1))};
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> biz = jdbc.queryForMap("SELECT name FROM tenants WHERE id = ?", TenantContext.tenantId());
        out.put("business", biz.get("name"));
        out.put("day", day.toString());
        out.put("dayLabel", DAY.format(day));

        // Sales: bills closed in the day.
        Map<String, Object> sales = db.one("Sales", """
                SELECT count(*) AS bills, coalesce(sum(total), 0) AS total, coalesce(sum(tip), 0) AS tips
                FROM orders WHERE kind = 'SALE' AND status IN ('PAID', 'CREDIT') AND closed_at >= :from AND closed_at < :to""", p);
        BigDecimal total = n(sales.get("total"));
        long bills = ((Number) sales.get("bills")).longValue();
        sales.put("average", bills == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(bills), 0, RoundingMode.HALF_UP));
        sales.put("byMethod", db.list("""
                SELECT method, sum(amount) AS amount FROM payments
                WHERE kind = 'PAYMENT' AND created_at >= :from AND created_at < :to GROUP BY method ORDER BY sum(amount) DESC""", p));
        sales.put("refunds", db.value("SELECT coalesce(-sum(amount), 0) FROM payments WHERE kind = 'REFUND' AND created_at >= :from AND created_at < :to",
                BigDecimal.class, p));
        Map<String, Object> open = db.one("Open", "SELECT count(*) AS bills, coalesce(sum(total - paid), 0) AS unpaid FROM orders WHERE kind = 'SALE' AND status = 'OPEN' AND created_at >= :from AND created_at < :to", p);
        sales.put("openBills", open.get("bills"));
        sales.put("openUnpaid", open.get("unpaid"));
        // Phase 1: what was spent today (expenses dated today, including cash paid out of tills).
        sales.put("expenses", db.value("SELECT coalesce(sum(amount), 0) FROM expenses WHERE spent_on = (cast(:from as timestamptz) AT TIME ZONE 'Africa/Nairobi')::date",
                BigDecimal.class, p));
        out.put("sales", sales);

        // Tills: each cashier's cash expected, counted, short or over.
        List<Map<String, Object>> tills = new ArrayList<>();
        for (Map<String, Object> s : db.list("""
                SELECT s.id, u.name AS cashier, s.status, s.opening_cash, s.counted_cash, s.closed_at,
                       coalesce((SELECT sum(amount) FROM payments x WHERE x.shift_id = s.id AND x.method = 'CASH'), 0) AS cash,
                       coalesce((SELECT sum(amount) FROM payments x WHERE x.shift_id = s.id AND x.method = 'MPESA'), 0) AS mpesa,
                       coalesce((SELECT sum(rider_pay) FROM deliveries d WHERE d.rider_paid_shift = s.id AND d.rider_paid_how IN ('TILL_CASH', 'FROM_COLLECTED')), 0) AS rider_pay,
                       coalesce((SELECT sum(e.amount) FROM expenses e WHERE e.shift_id = s.id), 0) AS paid_out
                FROM shifts s LEFT JOIN users u ON u.id = s.user_id
                WHERE (s.opened_at >= :from AND s.opened_at < :to) OR (s.closed_at >= :from AND s.closed_at < :to) OR s.status = 'OPEN'
                ORDER BY s.opened_at""", p)) {
            BigDecimal expected = n(s.get("openingCash")).add(n(s.get("cash"))).subtract(n(s.get("riderPay"))).subtract(n(s.get("paidOut")));
            s.put("expectedCash", expected);
            s.put("difference", s.get("countedCash") == null ? null : n(s.get("countedCash")).subtract(expected));
            s.put("open", "OPEN".equals(s.get("status")));
            s.remove("id");
            tills.add(s);
        }
        out.put("tills", tills);
        out.put("staffShort", db.list("""
                SELECT u.name, sum(d.amount) AS amount FROM staff_debts d JOIN users u ON u.id = d.user_id
                WHERE d.kind IN ('SHORTAGE', 'UNPAID_BILL') AND d.created_at >= :from AND d.created_at < :to GROUP BY u.name ORDER BY sum(d.amount) DESC""", p));

        // Dishes: what sold, food cost from the recipes, best and slowest.
        Map<UUID, BigDecimal> costs = recipes.unitCosts();
        List<Map<String, Object>> dishes = new ArrayList<>();
        BigDecimal foodSales = BigDecimal.ZERO;
        BigDecimal foodCost = BigDecimal.ZERO;
        for (Map<String, Object> d : db.list("""
                SELECT i.id, i.name, i.kind, i.cost, sum(l.qty * coalesce(l.pack_size, 1)) AS qty, sum(l.line_total) AS sales
                FROM order_lines l JOIN orders o ON o.id = l.order_id JOIN items i ON i.id = l.item_id
                WHERE o.kind = 'SALE' AND o.status IN ('PAID', 'CREDIT') AND o.closed_at >= :from AND o.closed_at < :to AND l.parent_line_id IS NULL
                GROUP BY i.id, i.name, i.kind, i.cost ORDER BY sum(l.qty) DESC""", p)) {
            UUID id = UUID.fromString(d.get("id").toString());
            BigDecimal each = portionCost(id, n(d.get("cost")), costs);
            BigDecimal cost = each.multiply(n(d.get("qty"))).setScale(2, RoundingMode.HALF_UP);
            d.put("cost", cost);
            d.put("profit", n(d.get("sales")).subtract(cost));
            if ("MENU".equals(d.get("kind")) || "COMBO".equals(d.get("kind"))) {
                foodSales = foodSales.add(n(d.get("sales")));
                foodCost = foodCost.add(cost);
            }
            d.remove("id");
            dishes.add(d);
        }
        out.put("foodCostPct", foodSales.signum() == 0 ? null : foodCost.multiply(BigDecimal.valueOf(100)).divide(foodSales, 1, RoundingMode.HALF_UP));
        out.put("topDishes", dishes.stream().limit(5).toList());
        out.put("bestEarner", dishes.stream().max(Comparator.comparing(x -> n(x.get("profit")))).orElse(null));
        out.put("slowest", db.list("""
                SELECT i.name FROM items i WHERE i.active AND i.kind = 'MENU'
                  AND NOT EXISTS (SELECT 1 FROM order_lines l JOIN orders o ON o.id = l.order_id
                                  WHERE l.item_id = i.id AND o.status IN ('PAID', 'CREDIT') AND o.closed_at >= :from AND o.closed_at < :to)
                ORDER BY i.name LIMIT 5""", p));

        // Waste, counts that came up short, and everything that needed an eye (theft alerts).
        out.put("waste", db.one("Waste", "SELECT count(*) AS entries, coalesce(sum(value), 0) AS value FROM waste_entries WHERE created_at >= :from AND created_at < :to", p));
        out.put("countsShort", db.list("""
                SELECT summary, amount FROM activity_log WHERE kind = 'STOCK_COUNT' AND created_at >= :from AND created_at < :to
                ORDER BY amount DESC NULLS LAST LIMIT 10""", p));
        Map<String, Object> bottles = bottleCounts.nightLine(day);
        if (bottles != null) {
            out.put("bottleCount", bottles);
        }
        out.put("voidsAndDiscounts", db.one("Voids", """
                SELECT count(*) FILTER (WHERE kind = 'VOID') AS voids, coalesce(sum(amount) FILTER (WHERE kind = 'VOID'), 0) AS void_value,
                       count(*) FILTER (WHERE kind IN ('DISCOUNT', 'PRICE_CHANGE', 'BELOW_LOWEST_PRICE')) AS discounts,
                       coalesce(sum(amount) FILTER (WHERE kind IN ('DISCOUNT', 'PRICE_CHANGE', 'BELOW_LOWEST_PRICE')), 0) AS discount_value,
                       count(*) FILTER (WHERE kind = 'ITEM_REMOVED') AS removed, coalesce(sum(amount) FILTER (WHERE kind = 'ITEM_REMOVED'), 0) AS removed_value,
                       count(*) FILTER (WHERE kind = 'REFUND') AS refunds
                FROM activity_log WHERE created_at >= :from AND created_at < :to""", p));
        out.put("alerts", db.list("""
                SELECT kind, summary, amount, user_name, created_at FROM activity_log
                WHERE severity = 'CONCERN' AND created_at >= :from AND created_at < :to ORDER BY created_at""", p));

        // Deliveries, apps and bookings, when the restaurant uses them.
        out.put("deliveries", db.one("Deliveries", """
                SELECT count(*) FILTER (WHERE status = 'DELIVERED') AS delivered, count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       coalesce(sum(collected) FILTER (WHERE collected > 0 AND handed_in_at IS NULL), 0) AS cash_with_riders
                FROM deliveries WHERE created_at >= :from AND created_at < :to""", p));
        out.put("apps", db.list("""
                SELECT delivery_app AS app, count(*) AS orders, sum(total) AS sales, sum(app_commission) AS cut FROM orders
                WHERE delivery_app IS NOT NULL AND status IN ('PAID', 'CREDIT') AND closed_at >= :from AND closed_at < :to GROUP BY delivery_app""", p));
        out.put("bookings", db.one("Bookings", """
                SELECT count(*) FILTER (WHERE status = 'SEATED') AS seated, count(*) FILTER (WHERE status = 'NO_SHOW') AS no_shows
                FROM reservations WHERE starts_at >= :from AND starts_at < :to""", p));

        // Monday: how each dish did over the past week.
        if (day.getDayOfWeek() == DayOfWeek.SUNDAY || day.getDayOfWeek() == DayOfWeek.MONDAY) {
            out.put("menuReport", menuReport(day.minusDays(6), day));
        }
        out.put("headline", headline(out));
        return out;
    }

    private BigDecimal portionCost(UUID item, BigDecimal itemCost, Map<UUID, BigDecimal> costs) {
        Map<UUID, BigDecimal> uses = recipes.usesFor(item, null);
        if (uses.isEmpty()) {
            return itemCost;
        }
        BigDecimal c = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> u : uses.entrySet()) {
            c = c.add(u.getValue().multiply(costs.getOrDefault(u.getKey(), BigDecimal.ZERO)));
        }
        return c;
    }

    /** One plain sentence for the top of the email and the phone alert. */
    @SuppressWarnings("unchecked")
    static String headline(Map<String, Object> s) {
        Map<String, Object> sales = (Map<String, Object>) s.get("sales");
        BigDecimal shortTotal = BigDecimal.ZERO;
        long open = ((List<Map<String, Object>>) s.get("tills")).stream().filter(t -> Boolean.TRUE.equals(t.get("open"))).count();
        for (Map<String, Object> t : (List<Map<String, Object>>) s.get("tills")) {
            if (t.get("difference") != null && n(t.get("difference")).signum() < 0) {
                shortTotal = shortTotal.add(n(t.get("difference")).negate());
            }
        }
        int alerts = ((List<?>) s.get("alerts")).size();
        String money = "KES " + String.format("%,d", n(sales.get("total")).setScale(0, RoundingMode.HALF_UP).longValue());
        return money + " from " + sales.get("bills") + " bill" + (((Number) sales.get("bills")).longValue() == 1 ? "" : "s")
                + (shortTotal.signum() > 0 ? ". Cash short KES " + String.format("%,d", shortTotal.setScale(0, RoundingMode.HALF_UP).longValue())
                        : open > 0 ? ". " + open + " till" + (open == 1 ? "" : "s") + " still open" : ". Cash all there")
                + (alerts > 0 ? ". " + alerts + " thing" + (alerts == 1 ? "" : "s") + " to look at." : ". Nothing worrying.");
    }

    // ---------------- the menu report ----------------

    /**
     * How each dish did: compared with the middle of the menu on orders and on profit per plate.
     * Stars (keep), promote (good profit, few orders), raise the price or cut the cost (popular, low profit), think
     * about dropping (neither).
     */
    public Map<String, Object> menuReport(LocalDate from, LocalDate to) {
        Object[] p = {"from", start(from), "to", start(to.plusDays(1))};
        Map<UUID, BigDecimal> costs = recipes.unitCosts();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> d : db.list("""
                SELECT i.id, i.name, i.price, i.cost,
                       coalesce((SELECT sum(l.qty) FROM order_lines l JOIN orders o ON o.id = l.order_id
                                 WHERE l.item_id = i.id AND l.parent_line_id IS NULL AND o.status IN ('PAID', 'CREDIT')
                                   AND o.closed_at >= :from AND o.closed_at < :to), 0) AS qty
                FROM items i WHERE i.active AND i.kind IN ('MENU', 'COMBO') AND i.price > 0 ORDER BY i.name""", p)) {
            UUID id = UUID.fromString(d.get("id").toString());
            BigDecimal price = n(d.get("price"));
            BigDecimal cost = portionCost(id, n(d.get("cost")), costs);
            BigDecimal profit = price.subtract(cost);
            d.put("costEach", cost.setScale(0, RoundingMode.HALF_UP));
            d.put("profitEach", profit.setScale(0, RoundingMode.HALF_UP));
            d.put("profit", profit.multiply(n(d.get("qty"))).setScale(0, RoundingMode.HALF_UP));
            d.put("costPct", price.signum() == 0 ? null : cost.multiply(BigDecimal.valueOf(100)).divide(price, 0, RoundingMode.HALF_UP));
            d.remove("cost");
            rows.add(d);
        }
        if (rows.isEmpty()) {
            return Map.of("from", from.toString(), "to", to.toString(), "dishes", List.of());
        }
        List<BigDecimal> qtys = rows.stream().map(r -> n(r.get("qty"))).sorted().toList();
        List<BigDecimal> profits = rows.stream().map(r -> n(r.get("profitEach"))).sorted().toList();
        BigDecimal midQty = qtys.get(qtys.size() / 2);
        BigDecimal midProfit = profits.get(profits.size() / 2);
        for (Map<String, Object> r : rows) {
            boolean popular = n(r.get("qty")).compareTo(midQty) >= 0 && n(r.get("qty")).signum() > 0;
            boolean earns = n(r.get("profitEach")).compareTo(midProfit) >= 0;
            String group = popular && earns ? "STAR" : earns ? "PROMOTE" : popular ? "FIX_PRICE" : "DROP";
            r.put("group", group);
            r.put("advice", switch (group) {
                case "STAR" -> "Sells well and makes good money: keep it where people see it.";
                case "PROMOTE" -> "Makes good money but few orders: show it off, let waiters suggest it, or add it to a deal.";
                case "FIX_PRICE" -> "Sells well but makes little: raise the price a little or make it cheaper to cook.";
                default -> "Few orders and little profit: think about dropping it or changing it.";
            });
            r.remove("id");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("dishes", rows);
        for (String g : List.of("STAR", "PROMOTE", "FIX_PRICE", "DROP")) {
            out.put(g.toLowerCase(), rows.stream().filter(r -> g.equals(r.get("group"))).map(r -> r.get("name")).limit(6).toList());
        }
        return out;
    }

    // ---------------- sending ----------------

    /** Build, keep and send the day's summary (once per day unless asked again). */
    public Map<String, Object> send(LocalDate day, String reason) {
        Map<String, Object> data = build(day);
        Map<String, Object> biz = jdbc.queryForMap("SELECT name, slug FROM tenants WHERE id = ?", TenantContext.tenantId());
        Rules r = rules(TenantContext.tenantId());
        List<String> to = new ArrayList<>(r.emails());
        if (to.isEmpty()) {
            to.addAll(jdbc.queryForList("SELECT email FROM users WHERE tenant_id = ? AND role = 'OWNER' AND active", String.class, TenantContext.tenantId()));
        }
        String link = mailer.appUrl() + "/night/" + day;
        for (String email : to) {
            mailer.send(email, null, "Your day at " + biz.get("name") + ": " + data.get("headline"), text(data, link), html(data, link));
        }
        db.exec("""
                INSERT INTO night_summaries (tenant_id, day, data, reason, emailed_to) VALUES (:t, :day, cast(:data as jsonb), :reason, :to)
                ON CONFLICT (tenant_id, day) DO UPDATE SET data = excluded.data, reason = excluded.reason, emailed_to = excluded.emailed_to, sent_at = now()""",
                "day", day, "data", db.toJson(data), "reason", reason, "to", String.join(", ", to));
        push.toStaff(List.of(), "Your day: " + biz.get("name"), (String) data.get("headline"), "/night/" + day, "night-" + day);
        return data;
    }

    /** Every few minutes: send today's summary when the last till has closed (after the earliest time) or at the latest time. */
    public String maybeSend(Instant now) {
        UUID tenant = TenantContext.tenantId();
        Rules r = rules(tenant);
        if (!r.on()) {
            return null;
        }
        LocalDate day = businessDay(now);
        if (db.value("SELECT count(*) FROM night_summaries WHERE day = :d", Long.class, "d", day) > 0) {
            return null;
        }
        LocalTime t = now.atZone(NAIROBI).toLocalTime();
        boolean pastLatest = !t.isBefore(r.latest()) || t.isBefore(DAY_STARTS);
        boolean pastEarliest = !t.isBefore(r.earliest()) || t.isBefore(DAY_STARTS);
        Object[] p = {"from", start(day), "to", start(day.plusDays(1))};
        long tillsToday = db.value("SELECT count(*) FROM shifts WHERE opened_at >= :from AND opened_at < :to", Long.class, p);
        long open = db.value("SELECT count(*) FROM shifts WHERE status = 'OPEN'", Long.class);
        long sales = db.value("SELECT count(*) FROM orders WHERE created_at >= :from AND created_at < :to", Long.class, p);
        if (sales == 0 && tillsToday == 0) {
            return null;
        }
        if (pastEarliest && tillsToday > 0 && open == 0) {
            send(day, "ALL_TILLS_CLOSED");
            return "ALL_TILLS_CLOSED";
        }
        if (pastLatest) {
            send(day, "LATEST_TIME");
            return "LATEST_TIME";
        }
        return null;
    }

    public Map<String, Object> stored(LocalDate day) {
        Map<String, Object> row = db.find("SELECT data, reason, emailed_to, sent_at FROM night_summaries WHERE day = :d", "d", day).orElse(null);
        if (row == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(json.convertValue(row.get("data"), Map.class));
        out.put("sentAt", row.get("sentAt"));
        out.put("reason", row.get("reason"));
        out.put("emailedTo", row.get("emailedTo"));
        return out;
    }

    public List<Map<String, Object>> recent() {
        return db.list("SELECT day, data->>'headline' AS headline, sent_at, reason FROM night_summaries ORDER BY day DESC LIMIT 60");
    }

    // ---------------- the email ----------------

    private static String kes(Object o) {
        return "KES " + String.format("%,d", n(o).setScale(0, RoundingMode.HALF_UP).longValue());
    }

    @SuppressWarnings("unchecked")
    String text(Map<String, Object> s, String link) {
        StringBuilder b = new StringBuilder();
        b.append(s.get("business")).append(", ").append(s.get("dayLabel")).append("\n\n").append(s.get("headline")).append("\n\n");
        for (Map<String, Object> a : (List<Map<String, Object>>) s.get("alerts")) {
            b.append("! ").append(a.get("summary")).append("\n");
        }
        b.append("\nThe full summary, and the PDF: ").append(link).append("\n");
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    String html(Map<String, Object> s, String link) {
        Map<String, Object> sales = (Map<String, Object>) s.get("sales");
        StringBuilder b = new StringBuilder();
        b.append(EmailLayout.h("Your day: " + Mailer.esc((String) s.get("dayLabel")), null));
        b.append(EmailLayout.p("<strong>" + Mailer.esc((String) s.get("headline")) + "</strong>"));
        List<String[]> facts = new ArrayList<>();
        facts.add(new String[]{"Sales", kes(sales.get("total")) + " · " + sales.get("bills") + " bills · average " + kes(sales.get("average"))});
        for (Map<String, Object> m : (List<Map<String, Object>>) sales.get("byMethod")) {
            facts.add(new String[]{"  " + methodName((String) m.get("method")), kes(m.get("amount"))});
        }
        if (n(sales.get("refunds")).signum() > 0) {
            facts.add(new String[]{"Money given back", kes(sales.get("refunds"))});
        }
        if (((Number) sales.get("openBills")).longValue() > 0) {
            facts.add(new String[]{"Bills still open", sales.get("openBills") + " (" + kes(sales.get("openUnpaid")) + " not paid yet)"});
        }
        if (s.get("foodCostPct") != null) {
            facts.add(new String[]{"Food cost", n(s.get("foodCostPct")).stripTrailingZeros().toPlainString() + "% of food sales"});
        }
        Map<String, Object> waste = (Map<String, Object>) s.get("waste");
        if (n(waste.get("value")).signum() > 0) {
            facts.add(new String[]{"Waste", kes(waste.get("value")) + " at cost"});
        }
        Map<String, Object> bottles = (Map<String, Object>) s.get("bottleCount");
        if (bottles != null) {
            facts.add(new String[]{"Bottle count", ((String) bottles.get("text")).replaceFirst("^Bottle count: ", "")});
        }
        Map<String, Object> v = (Map<String, Object>) s.get("voidsAndDiscounts");
        facts.add(new String[]{"Cancelled bills", v.get("voids") + (n(v.get("voidValue")).signum() > 0 ? " (" + kes(v.get("voidValue")) + ")" : "")});
        facts.add(new String[]{"Discounts and price changes", v.get("discounts") + (n(v.get("discountValue")).signum() > 0 ? " (" + kes(v.get("discountValue")) + " given up)" : "")});
        if (((Number) v.get("removed")).longValue() > 0) {
            facts.add(new String[]{"Items taken off after printing or cooking", v.get("removed") + " (" + kes(v.get("removedValue")) + ")"});
        }
        b.append(EmailLayout.facts(facts.toArray(new String[0][])));

        List<Map<String, Object>> tills = (List<Map<String, Object>>) s.get("tills");
        if (!tills.isEmpty()) {
            List<String[]> t = new ArrayList<>();
            for (Map<String, Object> x : tills) {
                String what = Boolean.TRUE.equals(x.get("open")) ? "still open"
                        : x.get("difference") == null ? "not counted"
                        : n(x.get("difference")).signum() == 0 ? "all there"
                        : n(x.get("difference")).signum() < 0 ? "SHORT " + kes(n(x.get("difference")).negate()) : "over " + kes(x.get("difference"));
                t.add(new String[]{String.valueOf(x.get("cashier")), "cash " + kes(x.get("expectedCash")) + " expected · " + what});
            }
            b.append(EmailLayout.p("<strong>Tills</strong>")).append(EmailLayout.facts(t.toArray(new String[0][])));
        }
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) s.get("alerts");
        if (!alerts.isEmpty()) {
            StringBuilder a = new StringBuilder("<strong>Worth a look</strong><br>");
            for (Map<String, Object> x : alerts) {
                a.append("&bull; ").append(Mailer.esc(String.valueOf(x.get("summary")))).append(x.get("userName") != null ? " <span style=\"color:#6b6b66\">(" + Mailer.esc(String.valueOf(x.get("userName"))) + ")</span>" : "").append("<br>");
            }
            b.append(EmailLayout.p(a.toString()));
        }
        List<Map<String, Object>> top = (List<Map<String, Object>>) s.get("topDishes");
        if (!top.isEmpty()) {
            StringBuilder a = new StringBuilder("<strong>Best sellers</strong><br>");
            for (Map<String, Object> x : top) {
                a.append(Mailer.esc(String.valueOf(x.get("name")))).append(": ").append(n(x.get("qty")).stripTrailingZeros().toPlainString()).append(" sold, ").append(kes(x.get("sales"))).append("<br>");
            }
            List<Map<String, Object>> slow = (List<Map<String, Object>>) s.get("slowest");
            if (!slow.isEmpty()) {
                a.append("<span style=\"color:#6b6b66\">Didn't sell today: ").append(Mailer.esc(String.join(", ", slow.stream().map(x -> String.valueOf(x.get("name"))).toList()))).append("</span>");
            }
            b.append(EmailLayout.p(a.toString()));
        }
        Map<String, Object> menu = (Map<String, Object>) s.get("menuReport");
        if (menu != null && !((List<?>) menu.get("dishes")).isEmpty()) {
            StringBuilder a = new StringBuilder("<strong>Your menu this past week</strong><br>");
            a.append(line("Keep (sell well, good money)", (List<Object>) menu.get("star")));
            a.append(line("Promote (good money, few orders)", (List<Object>) menu.get("promote")));
            a.append(line("Raise the price a little (popular, little money)", (List<Object>) menu.get("fix_price")));
            a.append(line("Think about dropping", (List<Object>) menu.get("drop")));
            b.append(EmailLayout.p(a.toString()));
        }
        b.append(EmailLayout.button("Open the full summary and PDF", link, null));
        return b.toString();
    }

    private static String line(String label, List<Object> names) {
        return names == null || names.isEmpty() ? "" : Mailer.esc(label) + ": " + Mailer.esc(String.join(", ", names.stream().map(String::valueOf).toList())) + "<br>";
    }

    static String methodName(String m) {
        return switch (m == null ? "" : m) {
            case "CASH" -> "Cash";
            case "MPESA" -> "M-Pesa";
            case "CARD" -> "Card";
            case "CREDIT" -> "On account";
            case "APP" -> "Delivery apps";
            case "POINTS" -> "Loyalty points";
            case "WAITER" -> "Unpaid, on the waiter";
            default -> m;
        };
    }
}
