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
    public record Price(long amount, long full, UUID offerId, String offerTitle, long credit) {
        public Price(long amount, long full, UUID offerId, String offerTitle) {
            this(amount, full, offerId, offerTitle, 0);
        }
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
                FROM platform_offers o WHERE o.tenant_id IS NULL ORDER BY o.created_at DESC LIMIT 100""");
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

    /** The businesses an offer made for everyone (or a kind of business) reaches now: the ones to tell about it. */
    public List<UUID> reached(Map<String, Object> offer) {
        String sector = (String) offer.get("sector");
        String where = "DAYS".equals(offer.get("kind"))
                ? "t.status = 'TRIAL' AND t.trial_ends_at > now() - interval '14 days'"
                : audienceSql(String.valueOf(offer.get("audience")));
        return jdbc.queryForList("SELECT t.id FROM tenants t WHERE NOT t.is_demo AND " + where
                + " AND (cast(? as text) IS NULL OR t.sector = cast(? as text))", UUID.class, sector, sector);
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
        return offersFor(List.of(tenant)).getOrDefault(tenant, List.of());
    }

    /** The live offers for each of these businesses (one query): those meant for everyone who matches, and those made for one business. */
    private Map<UUID, List<Map<String, Object>>> offersFor(java.util.Collection<UUID> tenants) {
        Map<UUID, List<Map<String, Object>>> out = new java.util.HashMap<>();
        if (tenants.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id AS tenant_id, o.id, o.kind, o.title, o.message, o.percent, o.months, o.days, o.plan, o.price, o.ends_at
                FROM platform_offers o, tenants t
                WHERE t.id = ANY (?) AND NOT t.is_demo AND o.active AND o.ends_at > now()
                  AND (o.tenant_id IS NULL OR o.tenant_id = t.id)
                  AND (o.sector IS NULL OR o.sector = t.sector)
                  AND (o.audience = 'ALL' OR (o.audience = 'TRIAL' AND t.status = 'TRIAL') OR (o.audience = 'PAYING' AND t.status = 'ACTIVE'))
                  AND (o.kind <> 'PERCENT' OR (SELECT count(*) FROM subscription_requests s
                         WHERE s.offer_id = o.id AND s.tenant_id = t.id AND s.status = 'PAID') < o.months)
                ORDER BY o.created_at DESC""", (Object) tenants.toArray(new UUID[0]));
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            UUID t = (UUID) m.remove("tenant_id");
            m.put("endsAt", ((java.sql.Timestamp) r.get("ends_at")).toInstant().toString());
            m.remove("ends_at");
            out.computeIfAbsent(t, k -> new ArrayList<>()).add(m);
        }
        return out;
    }

    // ---------- offers for one business ----------

    /** An offer made for this business only. PERCENT and PRICE change what it pays (and show on its next bill at once). */
    public Map<String, Object> createFor(UUID tenant, Map<String, Object> raw, String by) {
        Body b = new Body(raw);
        String kind = b.str("kind").toUpperCase();
        if (!Set.of("PERCENT", "PRICE").contains(kind)) {
            throw ApiException.bad("Choose a percent off or a set monthly price");
        }
        String title = b.str("title").trim();
        if (title.isEmpty() || title.length() > 80) {
            throw ApiException.bad("Give the offer a short title (what the business sees first)");
        }
        LocalDate until;
        try {
            until = LocalDate.parse(b.str("endsOn"));
        } catch (RuntimeException e) {
            throw ApiException.bad("Choose the last day of the offer");
        }
        if (until.isBefore(LocalDate.now(NAIROBI))) {
            throw ApiException.bad("The last day can't be in the past");
        }
        Integer percent = null, months = null, price = null;
        String plan = null;
        if ("PERCENT".equals(kind)) {
            percent = b.integer("percent", null);
            months = b.integer("months", null);
            if (percent == null || percent < 1 || percent > 90) {
                throw ApiException.bad("Percent off must be between 1 and 90");
            }
            if (months == null || months < 1 || months > 24) {
                throw ApiException.bad("Choose for how many months (1 to 24)");
            }
        } else {
            plan = b.str("plan", "").toUpperCase();
            price = b.integer("price", null);
            if (platform.plan(plan) == null) {
                throw ApiException.bad("Choose the plan");
            }
            if (price == null || price < 0) {
                throw ApiException.bad("Type the monthly price in KES");
            }
        }
        String message = b.str("message", "").trim();
        UUID id = jdbc.queryForObject("""
                INSERT INTO platform_offers (kind, title, message, percent, months, plan, price, audience, ends_at, created_by, tenant_id, reached)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ALL', ?, ?, ?, 1) RETURNING id""", UUID.class,
                kind, title, message.isEmpty() ? null : message, percent, months, plan, price,
                java.sql.Timestamp.from(until.plusDays(1).atStartOfDay(NAIROBI).toInstant()), by, tenant);
        return jdbc.queryForMap("SELECT * FROM platform_offers WHERE id = ?", id);
    }

    /** Every offer made for this business, newest first, with how often it was used. */
    public List<Map<String, Object>> madeFor(UUID tenant) {
        return jdbc.queryForList("""
                SELECT o.id, o.kind, o.title, o.percent, o.months, o.plan, o.price, o.ends_at, o.active, o.created_by, o.created_at,
                       (o.active AND o.ends_at > now()) AS live,
                       (SELECT count(*) FROM subscription_requests s WHERE s.offer_id = o.id AND s.status = 'PAID') AS used
                FROM platform_offers o WHERE o.tenant_id = ? ORDER BY o.created_at DESC LIMIT 50""", tenant);
    }

    /** End an offer made for this business. Returns its title. */
    public String endFor(UUID tenant, UUID offerId) {
        List<String> t = jdbc.queryForList("SELECT title FROM platform_offers WHERE id = ? AND tenant_id = ? AND active", String.class, offerId, tenant);
        if (t.isEmpty()) {
            throw ApiException.notFound("Offer for this business");
        }
        jdbc.update("UPDATE platform_offers SET active = false WHERE id = ?", offerId);
        return t.get(0);
    }

    // ---------- a plan price that went up: kept for a paying business until its bill after next ----------

    /**
     * The monthly price a paying business is held at, by plan, until its bill after next. A plan whose price went up reaches a paying
     * business at the bill after its next one: its next bill (the one on its paid-until date) keeps the old price.
     */
    private Map<UUID, Map<String, Long>> heldFor(java.util.Collection<UUID> tenants) {
        Map<UUID, Map<String, Long>> out = new java.util.HashMap<>();
        if (tenants.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT h.tenant_id, h.plan, h.monthly FROM tenant_price_holds h JOIN tenants t ON t.id = h.tenant_id
                WHERE h.tenant_id = ANY (?) AND t.status IN ('ACTIVE', 'SUSPENDED') AND t.paid_until IS NOT NULL AND h.until_bill >= t.paid_until""",
                (Object) tenants.toArray(new UUID[0]))) {
            out.computeIfAbsent((UUID) r.get("tenant_id"), k -> new LinkedHashMap<>()).put((String) r.get("plan"), ((Number) r.get("monthly")).longValue());
        }
        return out;
    }

    /** Keep every paying business at this plan's old price for its next bill: called when the plan's price goes up. */
    public int holdOldPrice(String plan, long oldMonthly) {
        return jdbc.update("""
                INSERT INTO tenant_price_holds (tenant_id, plan, monthly, until_bill)
                SELECT t.id, ?, ?, t.paid_until FROM tenants t
                WHERE t.status = 'ACTIVE' AND NOT t.is_demo AND t.paid_until IS NOT NULL
                ON CONFLICT (tenant_id, plan) DO UPDATE SET
                    monthly = CASE WHEN tenant_price_holds.until_bill >= (SELECT paid_until FROM tenants WHERE id = tenant_price_holds.tenant_id)
                                   THEN tenant_price_holds.monthly ELSE excluded.monthly END,
                    until_bill = CASE WHEN tenant_price_holds.until_bill >= (SELECT paid_until FROM tenants WHERE id = tenant_price_holds.tenant_id)
                                      THEN tenant_price_holds.until_bill ELSE excluded.until_bill END""", plan, oldMonthly);
    }

    /**
     * The one place that decides what a business pays for a plan now. Everything that shows or charges a price uses this
     * (the plans list, "keep everything running", the payment request and so the billing queue, activation and the paid email).
     * Order: the normal plan price (a paying business keeps the old one until its bill after next when the price went up);
     * then the plan offer from "Free days and plans" if it is live; then this business's own offers
     * (percent off, set price, a mall tenant's discount). Offers never stack: whichever gives the LOWEST price wins, so the
     * customer always gets the best one (on a tie the plan offer wins). Only the thank-you credit comes off afterwards.
     * The price never goes below 0. Annual is ten times the monthly price.
     */
    public Price price(UUID tenant, String plan, String cycle) {
        return price(context(tenant), plan, cycle);
    }

    /** What one price calculation needs about a business: its live offers, discount and credit. Load once, price many plans. */
    public record Context(UUID tenant, List<Map<String, Object>> offers, int mall, long credit) {
    }

    /** Loads the business's offers and tenant row once, so a caller looping over plans does not repeat the queries. */
    public Context context(UUID tenant) {
        Map<String, Object> own = jdbc.queryForMap("SELECT mall_discount, billing_credit FROM tenants WHERE id = ?", tenant);
        return new Context(tenant, forTenant(tenant), ((Number) own.get("mall_discount")).intValue(), ((Number) own.get("billing_credit")).longValue());
    }

    /** Same as {@link #price(UUID, String, String)} using an already loaded {@link Context}. */
    public Price price(Context ctx, String plan, String cycle) {
        Map<String, Long> held = heldFor(List.of(ctx.tenant())).get(ctx.tenant());
        return choose(plan, cycle, ctx.offers(), held == null ? null : held.get(plan), ctx.mall(), ctx.credit());
    }

    /**
     * The same price for many businesses at once (the control panel's lists and totals): the data is read in a few queries for all of
     * them and every price is then worked out by the one rule in {@link #choose}, so a list and a business's own bill never disagree.
     */
    public Map<UUID, Price> prices(Map<UUID, String> planOf, String cycle) {
        Map<UUID, Price> out = new java.util.HashMap<>();
        if (planOf.isEmpty()) {
            return out;
        }
        List<UUID> ids = new ArrayList<>(planOf.keySet());
        Map<UUID, List<Map<String, Object>>> offers = offersFor(ids);
        Map<UUID, Map<String, Long>> held = heldFor(ids);
        Map<UUID, Map<String, Object>> own = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, mall_discount, billing_credit FROM tenants WHERE id = ANY (?)", (Object) ids.toArray(new UUID[0]))) {
            own.put((UUID) r.get("id"), r);
        }
        for (UUID t : ids) {
            Map<String, Object> o = own.get(t);
            if (o == null) {
                throw ApiException.notFound("Workspace");
            }
            String plan = planOf.get(t);
            out.put(t, choose(plan, cycle, offers.getOrDefault(t, List.of()), held.getOrDefault(t, Map.of()).get(plan),
                    ((Number) o.get("mall_discount")).intValue(), ((Number) o.get("billing_credit")).longValue()));
        }
        return out;
    }

    private Price choose(String plan, String cycle, List<Map<String, Object>> mine, Long held, int mall, long billingCredit) {
        Map<String, Object> p = platform.plan(plan);
        if (p == null) {
            throw ApiException.bad("Unknown plan");
        }
        long listed = ((Number) p.get("monthly")).longValue();
        long monthly = held != null ? Math.min(held, listed) : listed; // a held price only protects against a rise
        boolean annual = "ANNUAL".equals(cycle);
        long full = annual ? monthly * 10 : monthly;
        Price best = new Price(full, full, null, null);
        Map<String, Object> planOffer = platform.liveOffer(plan);
        if (planOffer != null) {
            long m = Math.max(0, ((Number) planOffer.get("price")).longValue());
            String label = String.valueOf(planOffer.getOrDefault("label", "")).trim();
            if ((annual ? m * 10 : m) < best.amount()) {
                best = new Price(annual ? m * 10 : m, full, null, label.isEmpty() ? "Offer" : label);
            }
        }
        for (Map<String, Object> o : mine) {
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
            if (amount >= 0 && amount < best.amount()) {
                best = new Price(amount, full, (UUID) o.get("id"), (String) o.get("title"));
            }
        }
        if (mall > 0) {
            long amount = Math.round(full * (100 - mall) / 100.0);
            if (amount < best.amount()) {
                best = new Price(amount, full, null, "Tenants' offer: " + mall + "% off");
            }
        }
        long credit = Math.min(best.amount(), billingCredit);
        if (credit > 0) {
            best = new Price(best.amount() - credit, full, best.offerId(),
                    (best.offerTitle() == null ? "" : best.offerTitle() + " · ") + "KSh " + String.format("%,d", credit) + " thank-you credit", credit);
        }
        return best;
    }
}
