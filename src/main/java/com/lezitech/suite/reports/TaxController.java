package com.lezitech.suite.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "How much tax do I owe?": an estimate per day, per month and for the year from the sales recorded in BizNez.
 * Not a tax return and not tax advice; there is no KRA eTIMS link for now.
 *
 * The business picks its tax in Settings (settings.tax):
 *  regime   VAT  - VAT-registered: VAT on sales (already inside prices) minus VAT on stock bought (input VAT, estimated
 *                  from received purchases assuming buying prices include VAT at the item's rate). Due the 20th of the next month.
 *           TOT  - Turnover Tax: a percentage of gross sales (KRA: 1.5% for businesses under KES 25M a year). Due the 20th.
 *           NONE - neither (e.g. below the threshold); the page still shows what each would be.
 *  totRate  default 1.5
 *  tourismLevy, tourismLevyRate  the Tourism Fund levy on hotels and restaurants, default 2% of sales excluding VAT, due the 9th.
 * Every rate can be changed by the owner, because the law changes.
 */
@RestController
@Transactional(readOnly = true)
public class TaxController {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    static final BigDecimal DEFAULT_TOT = new BigDecimal("1.5");
    static final BigDecimal DEFAULT_LEVY = new BigDecimal("2");

    private final Db db;

    public TaxController(Db db) {
        this.db = db;
    }

    /**
     * One month (with a line per day) when month is given, otherwise the year with a line per month. With from and to
     * (any dates, e.g. this week), those days: a line per day up to two months, otherwise per month.
     */
    @GetMapping("/api/reports/tax")
    public Map<String, Object> tax(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
                                   @RequestParam(name = "from", required = false) LocalDate fromDay,
                                   @RequestParam(name = "to", required = false) LocalDate toDay) {
        LocalDate today = LocalDate.now(NAIROBI);
        boolean custom = fromDay != null || toDay != null;
        if (custom && (fromDay == null || toDay == null || toDay.isBefore(fromDay) || fromDay.isBefore(toDay.minusYears(2)))) {
            throw com.lezitech.suite.common.ApiException.bad("Choose a start and an end date, the start first, up to two years apart");
        }
        int y = year == null ? today.getYear() : year;
        boolean monthly = custom || month != null || year == null;
        int m = month == null ? today.getMonthValue() : month;
        LocalDate from = custom ? fromDay : monthly ? LocalDate.of(y, m, 1) : LocalDate.of(y, 1, 1);
        LocalDate to = custom ? toDay.plusDays(1) : monthly ? from.plusMonths(1) : from.plusYears(1);
        // Long custom ranges are shown per month (the lines start on the 1st), short ones per day.
        boolean byDay = custom ? java.time.temporal.ChronoUnit.DAYS.between(from, to) <= 62 : monthly;
        if (custom && !byDay) {
            monthly = false;
        }

        // tenants has no row-level security: always filter by this workspace.
        JsonNode settings = db.one("Workspace", "SELECT settings FROM tenants WHERE id = :id", "id", TenantContext.tenantId()).get("settings") instanceof JsonNode j ? j : null;
        JsonNode t = settings == null ? null : settings.path("tax");
        String regime = t == null || t.path("regime").asText("").isBlank() ? "NOT_SET" : t.path("regime").asText();
        BigDecimal totRate = rate(t, "totRate", DEFAULT_TOT);
        boolean levyOn = t != null && t.path("tourismLevy").asBoolean(false);
        BigDecimal levyRate = rate(t, "tourismLevyRate", DEFAULT_LEVY);

        String unit = monthly ? "day" : "month";
        Object[] range = {"from", ts(from), "to", ts(to)};
        List<Map<String, Object>> sales = db.list("""
                SELECT to_char(date_trunc('{unit}', o.closed_at AT TIME ZONE 'Africa/Nairobi'), 'YYYY-MM-DD') AS period,
                       coalesce(sum(o.total), 0) AS gross, coalesce(sum(o.tax), 0) AS vat, count(*) AS orders
                FROM orders o
                WHERE o.status IN ('PAID','CREDIT') AND o.kind <> 'QUOTE' AND o.closed_at >= :from AND o.closed_at < :to
                GROUP BY 1 ORDER BY 1""".replace("{unit}", unit), range);
        List<Map<String, Object>> buys = db.list("""
                SELECT to_char(date_trunc('{unit}', p.received_at AT TIME ZONE 'Africa/Nairobi'), 'YYYY-MM-DD') AS period,
                       coalesce(sum(l.qty * l.unit_cost), 0) AS bought,
                       coalesce(sum(round(l.qty * l.unit_cost * i.tax_rate / (100 + i.tax_rate), 2)), 0) AS input_vat
                FROM purchase_orders p JOIN purchase_lines l ON l.po_id = p.id JOIN items i ON i.id = l.item_id
                WHERE p.status = 'RECEIVED' AND p.received_at >= :from AND p.received_at < :to
                GROUP BY 1 ORDER BY 1""".replace("{unit}", unit), range);

        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        LocalDate first = monthly ? from : from.withDayOfMonth(1);
        for (LocalDate d = first; d.isBefore(to); d = monthly ? d.plusDays(1) : d.plusMonths(1)) {
            if (d.isAfter(today)) {
                break;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("period", d.toString());
            r.put("gross", BigDecimal.ZERO);
            r.put("vat", BigDecimal.ZERO);
            r.put("orders", 0L);
            r.put("bought", BigDecimal.ZERO);
            r.put("inputVat", BigDecimal.ZERO);
            rows.put(d.toString(), r);
        }
        for (Map<String, Object> s : sales) {
            Map<String, Object> r = rows.get((String) s.get("period"));
            if (r != null) {
                r.put("gross", s.get("gross"));
                r.put("vat", s.get("vat"));
                r.put("orders", s.get("orders"));
            }
        }
        for (Map<String, Object> b : buys) {
            Map<String, Object> r = rows.get((String) b.get("period"));
            if (r != null) {
                r.put("bought", b.get("bought"));
                r.put("inputVat", b.get("inputVat"));
            }
        }

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        BigDecimal input = BigDecimal.ZERO;
        BigDecimal bought = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> r : rows.values()) {
            BigDecimal g = num(r.get("gross"));
            BigDecimal v = num(r.get("vat"));
            BigDecimal in = num(r.get("inputVat"));
            gross = gross.add(g);
            vat = vat.add(v);
            input = input.add(in);
            bought = bought.add(num(r.get("bought")));
            r.put("tot", pct(g, totRate));
            r.put("tourismLevy", levyOn ? pct(g.subtract(v), levyRate) : BigDecimal.ZERO);
            r.put("owed", owed(regime, g, v, in, totRate, levyOn, levyRate));
            lines.add(r);
        }
        BigDecimal vatPayable = vat.subtract(input);
        BigDecimal tot = pct(gross, totRate);
        BigDecimal levy = levyOn ? pct(gross.subtract(vat), levyRate) : BigDecimal.ZERO;
        BigDecimal owed = owed(regime, gross, vat, input, totRate, levyOn, levyRate);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("grossSales", gross);
        totals.put("salesExVat", gross.subtract(vat));
        totals.put("outputVat", vat);
        totals.put("bought", bought);
        totals.put("inputVat", input);
        totals.put("vatPayable", vatPayable.max(BigDecimal.ZERO));
        totals.put("vatCredit", vatPayable.signum() < 0 ? vatPayable.negate() : BigDecimal.ZERO);
        totals.put("tot", tot);
        totals.put("tourismLevy", levy);
        totals.put("owed", owed);
        // Spread over the days so far: what to put aside each day.
        int daysSoFar = (int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(from, today.isBefore(to) ? today.plusDays(1) : to));
        totals.put("perDay", owed.divide(BigDecimal.valueOf(daysSoFar), 2, RoundingMode.HALF_UP));
        totals.put("days", daysSoFar);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.minusDays(1).toString());
        out.put("unit", unit);
        out.put("settings", Map.of("regime", regime, "totRate", totRate, "tourismLevy", levyOn, "tourismLevyRate", levyRate));
        out.put("totals", totals);
        out.put("lines", lines);
        out.put("due", due(regime, levyOn, monthly && !custom ? YearMonth.of(y, m) : null, owed, vatPayable.max(BigDecimal.ZERO), tot, levy, today));
        out.put("etr", etrCoverage(range));
        return out;
    }

    /**
     * Which paid sales in the period have the business's KRA receipt (ETR) attached, so the owner sees what still needs
     * one. Works without eTIMS.
     */
    private Map<String, Object> etrCoverage(Object[] range) {
        Map<String, Object> c = new LinkedHashMap<>(db.one("Sales", """
                SELECT count(*) FILTER (WHERE etr_file_id IS NOT NULL) AS with_etr,
                       coalesce(sum(total) FILTER (WHERE etr_file_id IS NOT NULL), 0) AS with_etr_amount,
                       count(*) FILTER (WHERE etr_file_id IS NULL) AS without_etr,
                       coalesce(sum(total) FILTER (WHERE etr_file_id IS NULL), 0) AS without_etr_amount
                FROM orders WHERE status IN ('PAID','CREDIT') AND kind <> 'QUOTE' AND closed_at >= :from AND closed_at < :to""", range));
        c.put("missing", db.list("""
                SELECT o.id, o.number, o.total, o.closed_at, c.name AS customer_name
                FROM orders o LEFT JOIN customers c ON c.id = o.customer_id
                WHERE o.status IN ('PAID','CREDIT') AND o.kind <> 'QUOTE' AND o.etr_file_id IS NULL
                  AND o.closed_at >= :from AND o.closed_at < :to
                ORDER BY o.closed_at DESC LIMIT 50""", range));
        return c;
    }

    /** What is owed under the chosen regime (nothing chosen: VAT if the prices carry VAT, otherwise Turnover Tax). */
    static BigDecimal owed(String regime, BigDecimal gross, BigDecimal vat, BigDecimal input, BigDecimal totRate, boolean levyOn, BigDecimal levyRate) {
        BigDecimal main = switch (regime) {
            case "VAT" -> vat.subtract(input).max(BigDecimal.ZERO);
            case "TOT" -> pct(gross, totRate);
            case "NONE" -> BigDecimal.ZERO;
            default -> vat.signum() > 0 ? vat.subtract(input).max(BigDecimal.ZERO) : pct(gross, totRate);
        };
        return main.add(levyOn ? pct(gross.subtract(vat), levyRate) : BigDecimal.ZERO);
    }

    /** Filing dates for a month: VAT and Turnover Tax by the 20th of the next month, the tourism levy by the 9th. */
    static List<Map<String, Object>> due(String regime, boolean levyOn, YearMonth ym, BigDecimal owed, BigDecimal vat, BigDecimal tot,
                                         BigDecimal levy, LocalDate today) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ym == null) {
            return out;
        }
        YearMonth next = ym.plusMonths(1);
        if (regime.equals("VAT") || regime.equals("NOT_SET")) {
            out.add(dueRow("VAT", "VAT return and payment (iTax)", next.atDay(20), vat, today));
        }
        if (regime.equals("TOT") || regime.equals("NOT_SET")) {
            out.add(dueRow("TOT", "Turnover Tax return and payment (iTax)", next.atDay(20), tot, today));
        }
        if (levyOn) {
            out.add(dueRow("TOURISM_LEVY", "Tourism Fund levy", next.atDay(9), levy, today));
        }
        return out;
    }

    private static Map<String, Object> dueRow(String tax, String label, LocalDate date, BigDecimal amount, LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tax", tax);
        m.put("label", label);
        m.put("date", date.toString());
        m.put("daysLeft", java.time.temporal.ChronoUnit.DAYS.between(today, date));
        m.put("amount", amount);
        return m;
    }

    static BigDecimal pct(BigDecimal v, BigDecimal rate) {
        return v.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(JsonNode t, String key, BigDecimal fallback) {
        if (t == null || !t.has(key) || t.get(key).isNull()) {
            return fallback;
        }
        try {
            BigDecimal r = new BigDecimal(t.get(key).asText());
            return r.signum() < 0 || r.compareTo(HUNDRED) > 0 ? fallback : r;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static BigDecimal num(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static Timestamp ts(LocalDate d) {
        return Timestamp.from(d.atStartOfDay(NAIROBI).toInstant());
    }
}
