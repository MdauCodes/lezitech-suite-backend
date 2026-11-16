package com.lezitech.suite.platform;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Offers LeziTech sends to every business (or only those on free days, only paying ones, or one kind of business):
 * percent off monthly payments for some months, a set monthly price for a plan, or extra free days. A business sees
 * the offers meant for it on its dashboard, and the price it pays already has the best one taken off.
 */
@Component
public class Offers {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    /** What a payment costs after offers: the amount, and the offer used (null if none). */
    public record Price(long amount, long full, UUID offerId, String offerTitle) {
    }

    private final JdbcTemplate jdbc;
    private final PlatformSettings platform;

    public Offers(JdbcTemplate jdbc, PlatformSettings platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    // ---------- control panel ----------

    public List<Map<String, Object>> all() {
        return jdbc.queryForList("""
                SELECT o.*, (o.active AND o.ends_at > now()) AS live,
                       (SELECT count(*) FROM subscription_requests s WHERE s.offer_id = o.id AND s.status = 'PAID') AS payments
                FROM platform_offers o ORDER BY o.created_at DESC LIMIT 100""");
    }

    /** Make an offer. Extra free days are added to the matching free trials straight away. */
    public Map<String, Object> create(Map<String, Object> raw, String by) {
        Body b = new Body(raw);
        String kind = b.str("kind").toUpperCase();
        if (!Set.of("PERCENT", "PRICE", "DAYS").contains(kind)) {
            throw ApiException.bad("Choose what the offer gives");
        }
        String title = b.str("title").trim();
        if (title.isEmpty() || title.length() > 80) {
            throw ApiException.bad("Give the offer a short title (what businesses see first)");
        }
        String message = b.str("message", "").trim();
        if (message.length() > 300) {
            throw ApiException.bad("Keep the message under 300 letters");
        }
        String audience = "DAYS".equals(kind) ? "TRIAL" : b.str("audience", "ALL").toUpperCase();
        if (!Set.of("ALL", "TRIAL", "PAYING").contains(audience)) {
            throw ApiException.bad("Choose who gets the offer");
        }
        String sector = b.str("sector", "").trim();
        LocalDate until;
        try {
            until = LocalDate.parse(b.str("endsOn"));
        } catch (RuntimeException e) {
            throw ApiException.bad("Choose the last day of the offer");
        }
        if (until.isBefore(LocalDate.now(NAIROBI))) {
            throw ApiException.bad("The last day can't be in the past");
        }
        Instant endsAt = until.plusDays(1).atStartOfDay(NAIROBI).toInstant();
        Integer percent = null, months = null, days = null, price = null;
        String plan = null;
        switch (kind) {
            case "PERCENT" -> {
                percent = b.integer("percent", null);
                months = b.integer("months", null);
                if (percent == null || percent < 1 || percent > 90) {
                    throw ApiException.bad("Percent off must be between 1 and 90");
                }
                if (months == null || months < 1 || months > 24) {
                    throw ApiException.bad("Choose for how many months (1 to 24)");
                }
            }
            case "PRICE" -> {
                plan = b.str("plan", "").toUpperCase();
                price = b.integer("price", null);
                if (platform.plan(plan) == null) {
                    throw ApiException.bad("Choose the plan");
                }
                if (price == null || price < 0) {
                    throw ApiException.bad("Type the monthly price in KES");
                }
            }
            default -> {
                days = b.integer("days", null);
                if (days == null || days < 1 || days > 60) {
                    throw ApiException.bad("Extra free days must be between 1 and 60");
                }
            }
        }
        UUID id = jdbc.queryForObject("""
                INSERT INTO platform_offers (kind, title, message, percent, months, days, plan, price, audience, sector, ends_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class,
                kind, title, message.isEmpty() ? null : message, percent, months, days, plan, price, audience,
                sector.isEmpty() ? null : sector, java.sql.Timestamp.from(endsAt), by);
        int reached;
        if ("DAYS".equals(kind)) {
            // Everyone still on free days, and anyone whose free days ended in the last two weeks.
            reached = jdbc.update("""
                    UPDATE tenants SET trial_ends_at = greatest(trial_ends_at, now()) + make_interval(days => ?)
                    WHERE status = 'TRIAL' AND NOT is_demo AND trial_ends_at > now() - interval '14 days'
                      AND (cast(? as text) IS NULL OR sector = cast(? as text))""", days, sector.isEmpty() ? null : sector, sector.isEmpty() ? null : sector);
        } else {
            reached = jdbc.queryForObject("SELECT count(*) FROM tenants t WHERE NOT is_demo AND " + audienceSql(audience)
                    + " AND (cast(? as text) IS NULL OR sector = cast(? as text))", Integer.class, sector.isEmpty() ? null : sector, sector.isEmpty() ? null : sector);
        }
        jdbc.update("UPDATE platform_offers SET reached = ? WHERE id = ?", reached, id);
        return jdbc.queryForMap("SELECT * FROM platform_offers WHERE id = ?", id);
    }

    public void stop(UUID id) {
        if (jdbc.update("UPDATE platform_offers SET active = false WHERE id = ?", id) == 0) {
            throw ApiException.notFound("Offer");
        }
    }

    private static String audienceSql(String audience) {
        return switch (audience) {
            case "TRIAL" -> "t.status = 'TRIAL'";
            case "PAYING" -> "t.status = 'ACTIVE'";
            default -> "t.status IN ('TRIAL', 'ACTIVE')";
        };
    }

    // ---------- a business ----------

    /** The live offers meant for this business, newest first. */
    public List<Map<String, Object>> forTenant(UUID tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.id, o.kind, o.title, o.message, o.percent, o.months, o.days, o.plan, o.price, o.ends_at
                FROM platform_offers o, tenants t
                WHERE t.id = ? AND NOT t.is_demo AND o.active AND o.ends_at > now()
                  AND (o.sector IS NULL OR o.sector = t.sector)
                  AND (o.audience = 'ALL' OR (o.audience = 'TRIAL' AND t.status = 'TRIAL') OR (o.audience = 'PAYING' AND t.status = 'ACTIVE'))
                  AND (o.kind <> 'PERCENT' OR (SELECT count(*) FROM subscription_requests s
                         WHERE s.offer_id = o.id AND s.tenant_id = t.id AND s.status = 'PAID') < o.months)
                ORDER BY o.created_at DESC""", tenant);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("endsAt", ((java.sql.Timestamp) r.get("ends_at")).toInstant().toString());
            m.remove("ends_at");
            out.add(m);
        }
        return out;
    }

    /** What this business pays for a plan now: the lowest price any live offer gives (percent off is for monthly payments). */
    public Price price(UUID tenant, String plan, String cycle) {
        Map<String, Object> p = platform.plan(plan);
        if (p == null) {
            throw ApiException.bad("Unknown plan");
        }
        long monthly = ((Number) p.get("monthly")).longValue();
        boolean annual = "ANNUAL".equals(cycle);
        long full = annual ? monthly * 10 : monthly;
        Price best = new Price(full, full, null, null);
        for (Map<String, Object> o : forTenant(tenant)) {
            long amount = full;
            switch ((String) o.get("kind")) {
                case "PERCENT" -> {
                    if (annual) {
                        continue;
                    }
                    amount = Math.round(monthly * (100 - ((Number) o.get("percent")).intValue()) / 100.0);
                }
                case "PRICE" -> {
                    if (!plan.equals(o.get("plan"))) {
                        continue;
                    }
                    long m = ((Number) o.get("price")).longValue();
                    amount = annual ? m * 10 : m;
                }
                default -> {
                    continue;
                }
            }
            if (amount < best.amount()) {
                best = new Price(amount, full, (UUID) o.get("id"), (String) o.get("title"));
            }
        }
        return best;
    }
}
