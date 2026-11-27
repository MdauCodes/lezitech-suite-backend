package com.lezitech.suite.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.sector.SectorCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Things LeziTech changes from the control panel without a new release: how many free days a new business gets, and
 * the plans (name, monthly price, people, branches, products) and which tools each includes. Read often, so kept for 30 seconds.
 */
@Component
public class PlatformSettings {

    /** The plans until LeziTech changes them. Every plan has the screens for the business's own kind; the tools differ (PlanFeatures). */
    public static final List<Map<String, Object>> DEFAULT_PLANS = List.of(
            plan("LITE", "Lite", 2650, 3, 1, 0, "Everything to run one shop, kiosk or café day to day. 1 branch, 3 staff"),
            plan("GROWTH", "Growth", 4500, 8, 3, 0, "Adds suppliers, back-office reports, sales leads, quotes, loyalty and phone ordering. 3 branches, 8 staff"),
            // 0 means no limit (see PlanLimits.check): the top plan caps nothing.
            plan("PRO", "Pro", 9000, 0, 0, 0, "Adds reports across branches, full history, owner powers for managers and priority support. Unlimited branches and staff"));

    private static final List<String> PLAN_ORDER = List.of("LITE", "GROWTH", "PRO");

    public static final int MIN_TRIAL = 3;
    public static final int MAX_TRIAL = 60;

    private static Map<String, Object> plan(String code, String name, int monthly, int users, int locations, int items, String blurb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("monthly", monthly);
        m.put("users", users);
        m.put("locations", locations);
        m.put("items", items);
        m.put("blurb", blurb);
        return m;
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SectorCatalog sectors;
    private final int defaultTrialDays;
    private volatile Map<String, String> cache;
    private volatile long cachedAt;

    public PlatformSettings(JdbcTemplate jdbc, ObjectMapper json, SectorCatalog sectors, @Value("${suite.trial-days}") int defaultTrialDays) {
        this.jdbc = jdbc;
        this.json = json;
        this.sectors = sectors;
        this.defaultTrialDays = defaultTrialDays;
    }

    private Map<String, String> all() {
        Map<String, String> c = cache;
        if (c == null || System.currentTimeMillis() - cachedAt > 30_000) {
            Map<String, String> fresh = new LinkedHashMap<>();
            jdbc.queryForList("SELECT key, value::text AS value FROM platform_settings WHERE key IN ('trialDays', 'plans', 'planFeatures', 'planOffers', 'sectorPlanNames', 'kindPlans', 'minClientRelease', 'latestClientRelease', 'reloadMessage')").forEach(r -> fresh.put((String) r.get("key"), (String) r.get("value")));
            cache = c = fresh;
            cachedAt = System.currentTimeMillis();
        }
        return c;
    }

    /** Read the settings again on next use (after a change made straight in the database). */
    public void forget() {
        cache = null;
    }

    /** Free days for a business that signs up (or is handed over) from now on. */
    public int trialDays() {
        try {
            String v = all().get("trialDays");
            return v == null ? defaultTrialDays : Math.max(MIN_TRIAL, Math.min(MAX_TRIAL, Integer.parseInt(v)));
        } catch (RuntimeException e) {
            return defaultTrialDays;
        }
    }

    /** The plans, cheapest first. */
    public List<Map<String, Object>> plans() {
        String v = all().get("plans");
        if (v == null) {
            return DEFAULT_PLANS;
        }
        try {
            List<Map<String, Object>> p = json.readValue(v, new TypeReference<>() { });
            p.sort(Comparator.comparingInt(x -> ((Number) x.get("monthly")).intValue()));
            return p;
        } catch (Exception e) {
            return DEFAULT_PLANS;
        }
    }

    public Map<String, Object> plan(String code) {
        return plans().stream().filter(p -> code.equals(p.get("code"))).findFirst().orElse(null);
    }

    public void setTrialDays(int days, String by) {
        if (days < MIN_TRIAL || days > MAX_TRIAL) {
            throw ApiException.bad("Free days must be between " + MIN_TRIAL + " and " + MAX_TRIAL);
        }
        put("trialDays", String.valueOf(days), by);
    }

    /** Change the plans' names, prices and limits. The three plan codes stay (businesses are on them). */
    public void setPlans(List<Map<String, Object>> plans, String by) {
        if (plans == null || plans.size() != 3) {
            throw ApiException.bad("Send the three plans");
        }
        List<Map<String, Object>> clean = new ArrayList<>();
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (Map<String, Object> p : plans) {
            String code = String.valueOf(p.get("code"));
            if (!Set.of("LITE", "GROWTH", "PRO").contains(code) || !codes.add(code)) {
                throw ApiException.bad("Plans are LITE, GROWTH and PRO, once each");
            }
            String name = String.valueOf(p.getOrDefault("name", "")).trim();
            if (name.isEmpty() || name.length() > 30) {
                throw ApiException.bad("Give each plan a short name");
            }
            int monthly = num(p.get("monthly"), "price"), users = num(p.get("users"), "staff"), locations = num(p.get("locations"), "branches"),
                    items = 0; // products are never limited
            if (monthly < 0 || users < 0 || locations < 0 || items < 0) {
                throw ApiException.bad("Check the numbers for " + name + " (0 staff or branches means no limit)");
            }
            String blurb = String.valueOf(p.getOrDefault("blurb", "")).trim();
            clean.add(plan(code, name, monthly, users, locations, items, blurb.length() > 200 ? blurb.substring(0, 200) : blurb));
        }
        try {
            put("plans", json.writeValueAsString(clean), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- plan names for each kind of business ----------

    /** The names a kind of business sees for LITE, GROWTH and PRO until LeziTech edits them (other kinds see the plans' own names). */
    public static final Map<String, List<String>> DEFAULT_SECTOR_NAMES = Map.ofEntries(
            Map.entry("restaurant", List.of("Starter Kitchen", "Busy Kitchen", "Kitchen Group")),
            Map.entry("hotel", List.of("Guest House", "Full House", "Hotel Group")),
            Map.entry("retail", List.of("Corner Shop", "Busy Shop", "Shop Chain")),
            Map.entry("beauty", List.of("Salon Chair", "Busy Salon", "Salon Group")),
            Map.entry("wholesale", List.of("Stockist", "Distributor", "Distribution Network")),
            Map.entry("services", List.of("Solo", "Team", "Firm")),
            Map.entry("tours", List.of("Trips", "Tour Company", "Tour Group")),
            Map.entry("property", List.of("Landlord", "Property Manager", "Portfolio")));

    private static final List<String> CODES = List.of("LITE", "GROWTH", "PRO");

    /** What LeziTech saved for each kind of business: sector key to the names that were changed. */
    public Map<String, Map<String, String>> savedSectorNames() {
        String v = all().get("sectorPlanNames");
        if (v == null) {
            return Map.of();
        }
        try {
            return json.readValue(v, new TypeReference<Map<String, Map<String, String>>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** The name this kind of business has until it is edited: the table above, or the plan's own name. */
    public String defaultPlanName(String sector, String code) {
        List<String> d = sector == null ? null : DEFAULT_SECTOR_NAMES.get(sector);
        int i = CODES.indexOf(code);
        if (d != null && i >= 0) {
            return d.get(i);
        }
        Map<String, Object> p = plan(code);
        return p == null ? code : String.valueOf(p.get("name"));
    }

    /** The name this kind of business sees for a plan code. */
    public String planName(String sector, String code) {
        String saved = sector == null ? null : savedSectorNames().getOrDefault(sector, Map.of()).get(code);
        return saved != null && !saved.isBlank() ? saved : defaultPlanName(sector, code);
    }

    /** The name this business sees for a plan code (its own kind of business decides). */
    public String planName(java.util.UUID tenant, String code) {
        List<Map<String, Object>> s = jdbc.queryForList("SELECT sector, sub_sector FROM tenants WHERE id = ?", tenant);
        return s.isEmpty() ? planName((String) null, code) : planName((String) s.get(0).get("sector"), (String) s.get(0).get("sub_sector"), code);
    }

    /** The name a business of this sector and sub-sector sees: its kind plan's own name when it has one, else the sector's name. */
    public String planName(String sector, String subSector, String code) {
        Map<String, Object> kp = kindPlan(sector, subSector);
        return kp != null && code != null && code.equals(kp.get("plan")) ? String.valueOf(kp.get("name")) : planName(sector, code);
    }

    /** All three names for a kind of business, in plan order. */
    public Map<String, String> planNames(String sector) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String c : CODES) {
            out.put(c, planName(sector, c));
        }
        return out;
    }

    /** Save the names for one kind of business. A blank name goes back to the default; the three must differ. */
    public Map<String, String> setSectorPlanNames(String sector, Map<String, Object> names, String by) {
        Map<String, Map<String, String>> all = new LinkedHashMap<>(savedSectorNames());
        Map<String, String> mine = new LinkedHashMap<>();
        for (String c : CODES) {
            Object raw = names == null ? null : names.get(c);
            String n = raw == null ? "" : String.valueOf(raw).trim();
            if (n.length() > 30) {
                throw ApiException.bad("Keep each plan name under 30 letters");
            }
            if (!n.isEmpty() && !n.equals(defaultPlanName(sector, c))) {
                mine.put(c, n);
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String c : CODES) {
            result.put(c, mine.getOrDefault(c, defaultPlanName(sector, c)));
        }
        if (new java.util.HashSet<>(result.values()).size() < 3) {
            throw ApiException.bad("Give the three plans different names");
        }
        if (mine.isEmpty()) {
            all.remove(sector);
        } else {
            all.put(sector, mine);
        }
        try {
            put("sectorPlanNames", json.writeValueAsString(all), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    // ---------- a plan of its own for one kind of business ----------

    /** What a kind of business pays until LeziTech saves its own: Busynez Supply for suppliers to hotels and hospitals. */
    public static final Map<String, Map<String, Object>> DEFAULT_KIND_PLANS = Map.of(
            "wholesale/hotelSupplier", kindPlanOf("PRO", "Busynez Supply", 4500,
                    "Everything a supplier to hotels and hospitals needs: orders, deliveries, invoices, stock and reports. Unlimited staff and branches",
                    kindOffer(3000, "Launch price", null, null, true)));

    private static Map<String, Object> kindOffer(int price, String label, String startsOn, String endsOn, boolean on) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("price", price);
        o.put("label", label);
        o.put("startsOn", startsOn);
        o.put("endsOn", endsOn);
        o.put("on", on);
        return o;
    }

    private static Map<String, Object> kindPlanOf(String plan, String name, int monthly, String blurb, Map<String, Object> offer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", plan);
        m.put("name", name);
        m.put("monthly", monthly);
        m.put("blurb", blurb);
        m.put("offer", offer);
        return m;
    }

    /** The kind plans, by kind id ("sectorKey/subSectorKey"): what LeziTech saved, or the built-in ones until it saves its own. */
    public Map<String, Map<String, Object>> kindPlans() {
        Map<String, String> now = all();
        KindCache c = kindCache;
        if (c != null && c.from == now) {
            return c.plans;
        }
        Map<String, Map<String, Object>> parsed = DEFAULT_KIND_PLANS;
        String v = now.get("kindPlans");
        if (v != null) {
            try {
                parsed = json.readValue(v, new TypeReference<Map<String, Map<String, Object>>>() { });
            } catch (Exception e) {
                parsed = DEFAULT_KIND_PLANS;
            }
        }
        kindCache = new KindCache(now, parsed);
        return parsed;
    }

    /** The parsed kind plans, kept for as long as the settings read from the database are kept. */
    private record KindCache(Map<String, String> from, Map<String, Map<String, Object>> plans) {
    }

    private volatile KindCache kindCache;

    /** The plan a business pays on now, or null while it is on free days or not paying. */
    public String payingPlan(java.util.UUID tenant) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT plan, status, suspended_from FROM tenants WHERE id = ?", tenant);
        if (r.isEmpty() || r.get(0).get("plan") == null) {
            return null;
        }
        Map<String, Object> t = r.get(0);
        boolean paying = "ACTIVE".equals(t.get("status")) || "SUSPENDED".equals(t.get("status")) && "ACTIVE".equals(t.get("suspended_from"));
        return paying ? (String) t.get("plan") : null;
    }

    /** Whether the kind price applies to a plan asked for by a business paying on {@code ownPlan} (null: not paying yet). Paying on another plan keeps it and its normal price. */
    public static boolean kindPriceApplies(Map<String, Object> kind, String ownPlan, String asked) {
        return kind != null && (ownPlan == null || ownPlan.equals(kind.get("plan")) || asked.equals(kind.get("plan")));
    }

    /** The kind id for a sector and a sub-sector (its name or key), or null when the catalogue does not know the sub-sector. */
    public String kindOf(String sector, String subSector) {
        if (sector == null || subSector == null || !sectors.hasSector(sector)) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode sub = sectors.findSub(sector, subSector);
        return sub == null ? null : sector + "/" + sub.get("key").asText();
    }

    public String kindOf(java.util.UUID tenant) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT sector, sub_sector FROM tenants WHERE id = ?", tenant);
        return r.isEmpty() ? null : kindOf((String) r.get(0).get("sector"), (String) r.get(0).get("sub_sector"));
    }

    /** This kind's plan, or null when it has none. */
    public Map<String, Object> kindPlan(String sector, String subSector) {
        String kind = kindOf(sector, subSector);
        return kind == null ? null : kindPlans().get(kind);
    }

    /** This business's kind plan, or null when its kind has none (most businesses). */
    public Map<String, Object> kindPlan(java.util.UUID tenant) {
        String kind = kindOf(tenant);
        return kind == null ? null : kindPlans().get(kind);
    }

    /**
     * Whether a business of kind {@code tenantKind} is reached by a change meant for {@code scopeKind}: with a kind id, only that kind's
     * businesses; with null (the platform's own plans), every business that has no plan of its own for its kind.
     */
    public boolean inScope(String tenantKind, String scopeKind, String code) {
        if (scopeKind != null) {
            return scopeKind.equals(tenantKind);
        }
        Map<String, Object> own = tenantKind == null ? null : kindPlans().get(tenantKind);
        return own == null || !own.get("plan").equals(code); // paying on another plan keeps it, with its normal price
    }

    /** The kind plan's offer if it is on, has started, has not ended and is lower than its price; otherwise null. Dates are Nairobi days. */
    public Map<String, Object> liveKindOffer(Map<String, Object> kindPlan) {
        return liveKindOffer(kindPlan, java.time.LocalDate.now(NAIROBI));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> liveKindOffer(Map<String, Object> kindPlan, java.time.LocalDate today) {
        Object raw = kindPlan == null ? null : kindPlan.get("offer");
        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> o = (Map<String, Object>) raw;
        if (!Boolean.TRUE.equals(o.get("on"))) {
            return null;
        }
        try {
            long price = ((Number) o.get("price")).longValue();
            if (price < 1 || price >= ((Number) kindPlan.get("monthly")).longValue()) {
                return null;
            }
            String start = (String) o.get("startsOn"), end = (String) o.get("endsOn");
            if (start != null && !start.isBlank() && today.isBefore(java.time.LocalDate.parse(start))) {
                return null;
            }
            if (end != null && !end.isBlank() && today.isAfter(java.time.LocalDate.parse(end))) {
                return null;
            }
            return o;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What each kind of business pays a month now: its offer's price while one is live, else its plan's price. By kind id. */
    public Map<String, Long> kindEffectivePrices() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : kindPlans().entrySet()) {
            Map<String, Object> live = liveKindOffer(e.getValue());
            out.put(e.getKey(), live != null ? ((Number) live.get("price")).longValue() : ((Number) e.getValue().get("monthly")).longValue());
        }
        return out;
    }

    /** A short stand-in for a kind id that fits the notice jobs' 20-letter plan column (no new table needed). */
    public static String kindToken(String kind) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5").digest(kind.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "K:" + java.util.HexFormat.of().formatHex(d).substring(0, 17);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The kind id a token stands for, among the kinds that have a plan now; null when none (or not a token). */
    public String kindForToken(String token) {
        if (token == null || !token.startsWith("K:")) {
            return null;
        }
        return kindPlans().keySet().stream().filter(k -> kindToken(k).equals(token)).findFirst().orElse(null);
    }

    /** The kind plan as its businesses see it: its name, normal price, price now, and the offer's label and last day. */
    public Map<String, Object> kindPlanView(Map<String, Object> kp) {
        Map<String, Object> plan = new LinkedHashMap<>(plan((String) kp.get("plan")));
        plan.put("name", kp.get("name"));
        plan.put("monthly", kp.get("monthly"));
        plan.put("blurb", kp.get("blurb"));
        Map<String, Object> o = liveKindOffer(kp);
        long normal = ((Number) kp.get("monthly")).longValue();
        plan.put("price", normal);
        plan.put("priceNow", o != null ? ((Number) o.get("price")).longValue() : normal);
        plan.put("offerLabel", o == null ? null : (String.valueOf(o.getOrDefault("label", "")).isBlank() ? "Offer" : o.get("label")));
        String end = o == null ? null : (String) o.get("endsOn");
        plan.put("offerEnds", end == null || end.isBlank() ? null : end);
        return plan;
    }

    /**
     * Save the kind plans (the whole map). Every kind id must be a known "sector/subSector"; the plan code LITE, GROWTH or PRO;
     * the price 0 or more; an offer's price 1 or more and under the price. Returns what was saved.
     */
    public Map<String, Map<String, Object>> setKindPlans(Map<String, Map<String, Object>> plans, String by) {
        Map<String, Map<String, Object>> clean = new LinkedHashMap<>();
        java.time.LocalDate today = java.time.LocalDate.now(NAIROBI);
        for (Map.Entry<String, Map<String, Object>> e : (plans == null ? Map.<String, Map<String, Object>>of() : plans).entrySet()) {
            String[] parts = e.getKey() == null ? new String[0] : e.getKey().split("/");
            com.fasterxml.jackson.databind.JsonNode sub = parts.length == 2 && sectors.hasSector(parts[0]) ? sectors.findSub(parts[0], parts[1]) : null;
            if (sub == null) {
                throw ApiException.bad("Choose a kind of business that Busynez knows");
            }
            String kind = parts[0] + "/" + sub.get("key").asText();
            Map<String, Object> p = e.getValue();
            if (p == null) {
                continue;
            }
            String code = String.valueOf(p.get("plan"));
            if (!Set.of("LITE", "GROWTH", "PRO").contains(code)) {
                throw ApiException.bad("The plan for " + sub.get("name").asText() + " must be Lite, Growth or Pro");
            }
            String name = String.valueOf(p.getOrDefault("name", "")).trim();
            if (name.isEmpty() || name.length() > 30) {
                throw ApiException.bad("Give the plan for " + sub.get("name").asText() + " a short name");
            }
            int monthly = num(p.get("monthly"), "the price for " + name);
            if (monthly < 0) {
                throw ApiException.bad("The price for " + name + " can't be below 0");
            }
            String blurb = String.valueOf(p.getOrDefault("blurb", "")).trim();
            Map<String, Object> offer = null;
            if (p.get("offer") instanceof Map<?, ?> om && !om.isEmpty()) {
                boolean on = Boolean.TRUE.equals(om.get("on")) || "true".equals(String.valueOf(om.get("on")));
                Object rawPrice = om.get("price");
                if (rawPrice == null || String.valueOf(rawPrice).isBlank()) {
                    if (on) {
                        throw ApiException.bad("Type the offer price for " + name);
                    }
                } else {
                    int price = num(rawPrice, "the offer price for " + name);
                    if (price < 1) {
                        throw ApiException.bad("The offer price for " + name + " must be at least KES 1");
                    }
                    if (price >= monthly) {
                        throw ApiException.bad("The offer price for " + name + " must be lower than its normal price of KES " + String.format("%,d", monthly));
                    }
                    String label = String.valueOf(om.get("label") == null ? "" : om.get("label")).trim();
                    if (label.length() > 40) {
                        throw ApiException.bad("Keep the offer label for " + name + " to 40 letters or fewer");
                    }
                    java.time.LocalDate start = day(om.get("startsOn"), "the start date for " + name);
                    java.time.LocalDate end = day(om.get("endsOn"), "the end date for " + name);
                    if (start != null && end != null && end.isBefore(start)) {
                        throw ApiException.bad("The offer for " + name + " can't end before it starts");
                    }
                    if (on && end != null && end.isBefore(today)) {
                        throw ApiException.bad("The end date for " + name + " has already passed");
                    }
                    offer = kindOffer(price, label, start == null ? null : start.toString(), end == null ? null : end.toString(), on);
                }
            }
            clean.put(kind, kindPlanOf(code, name, monthly, blurb.length() > 200 ? blurb.substring(0, 200) : blurb, offer));
        }
        try {
            for (Map.Entry<String, Map<String, Object>> old : kindPlans().entrySet()) {
                Map<String, Object> now = clean.get(old.getKey());
                if (now != null && now.get("plan").equals(old.getValue().get("plan"))) {
                    continue;
                }
                long n = 0;
                for (Map<String, Object> t : jdbc.queryForList("SELECT sector, sub_sector FROM tenants WHERE sector = ?", old.getKey().substring(0, old.getKey().indexOf('/')))) {
                    if (old.getKey().equals(kindOf((String) t.get("sector"), (String) t.get("sub_sector")))) {
                        n++;
                    }
                }
                if (n > 0) {
                    throw ApiException.bad(n + (n == 1 ? " business is" : " businesses are") + " on the plan for " + old.getValue().get("name")
                            + ", so it can't be removed or moved to another plan code. Change its name or price instead.");
                }
            }
            put("kindPlans", json.writeValueAsString(clean), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        return clean;
    }

    // ---------- plan offers: a crossed-out normal price and a lower price for a while ----------

    private static final java.time.ZoneId NAIROBI = java.time.ZoneId.of("Africa/Nairobi");

    /** What the owner saved for each plan: price, label, startsOn, endsOn (yyyy-MM-dd, end may be empty) and on. Empty map when none. */
    public Map<String, Map<String, Object>> planOffers() {
        String v = all().get("planOffers");
        if (v == null) {
            return Map.of();
        }
        try {
            return json.readValue(v, new TypeReference<Map<String, Map<String, Object>>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** The plan's offer if it is on, has started, has not ended and is lower than the normal price; otherwise null. Dates are Nairobi days. */
    public Map<String, Object> liveOffer(String code) {
        return liveOffer(code, java.time.LocalDate.now(NAIROBI));
    }

    public Map<String, Object> liveOffer(String code, java.time.LocalDate today) {
        Map<String, Object> plan = plan(code);
        Map<String, Object> o = planOffers().get(code);
        if (plan == null || o == null || !Boolean.TRUE.equals(o.get("on"))) {
            return null;
        }
        try {
            long price = ((Number) o.get("price")).longValue();
            if (price < 1 || price >= ((Number) plan.get("monthly")).longValue()) {
                return null;
            }
            String start = (String) o.get("startsOn"), end = (String) o.get("endsOn");
            if (start != null && !start.isBlank() && today.isBefore(java.time.LocalDate.parse(start))) {
                return null;
            }
            if (end != null && !end.isBlank() && today.isAfter(java.time.LocalDate.parse(end))) {
                return null;
            }
            return o;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Save the offers (one entry per plan code; a plan left out has none). Returns what was saved. */
    public Map<String, Map<String, Object>> setPlanOffers(Map<String, Map<String, Object>> offers, String by) {
        Map<String, Map<String, Object>> clean = new LinkedHashMap<>();
        java.time.LocalDate today = java.time.LocalDate.now(NAIROBI);
        for (Map.Entry<String, Map<String, Object>> e : (offers == null ? Map.<String, Map<String, Object>>of() : offers).entrySet()) {
            Map<String, Object> plan = plan(e.getKey());
            Map<String, Object> o = e.getValue();
            if (plan == null) {
                throw ApiException.bad("Plans are LITE, GROWTH and PRO");
            }
            if (o == null || o.isEmpty()) {
                continue;
            }
            String name = String.valueOf(plan.get("name"));
            int normal = ((Number) plan.get("monthly")).intValue();
            boolean on = Boolean.TRUE.equals(o.get("on")) || "true".equals(String.valueOf(o.get("on")));
            Object rawPrice = o.get("price");
            if (rawPrice == null || String.valueOf(rawPrice).isBlank()) {
                if (!on) {
                    continue; // nothing typed and switched off: no offer
                }
                throw ApiException.bad("Type the offer price for " + name);
            }
            int price = num(rawPrice, "the offer price for " + name);
            if (price < 1) {
                throw ApiException.bad("The offer price for " + name + " must be at least KES 1. For free months, use an offer of extra free days");
            }
            if (price >= normal) {
                throw ApiException.bad("The offer price for " + name + " must be lower than its normal price of KES " + String.format("%,d", normal));
            }
            String label = String.valueOf(o.getOrDefault("label", "")).trim();
            if (label.length() > 40) {
                throw ApiException.bad("Keep the offer label for " + name + " to 40 letters or fewer");
            }
            java.time.LocalDate start = day(o.get("startsOn"), "the start date for " + name);
            java.time.LocalDate end = day(o.get("endsOn"), "the end date for " + name);
            if (start != null && end != null && end.isBefore(start)) {
                throw ApiException.bad("The offer for " + name + " can't end before it starts");
            }
            if (on && end != null && end.isBefore(today)) {
                throw ApiException.bad("The end date for " + name + " has already passed");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("price", price);
            m.put("label", label);
            m.put("startsOn", start == null ? null : start.toString());
            m.put("endsOn", end == null ? null : end.toString());
            m.put("on", on);
            clean.put(e.getKey(), m);
        }
        try {
            put("planOffers", json.writeValueAsString(clean), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        return clean;
    }

    private static java.time.LocalDate day(Object v, String what) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw ApiException.bad("Choose " + what + " again");
        }
    }

    /** One plan as customers see it: normal price, price now, and the offer's label and last day (null when no offer is live). */
    public Map<String, Object> withPriceNow(Map<String, Object> plan) {
        Map<String, Object> m = new LinkedHashMap<>(plan);
        Map<String, Object> o = liveOffer((String) plan.get("code"));
        long normal = ((Number) plan.get("monthly")).longValue();
        m.put("price", normal);
        m.put("priceNow", o != null ? ((Number) o.get("price")).longValue() : normal);
        m.put("offerLabel", o == null ? null : (String.valueOf(o.getOrDefault("label", "")).isBlank() ? "Offer" : o.get("label")));
        String end = o == null ? null : (String) o.get("endsOn");
        m.put("offerEnds", end == null || end.isBlank() ? null : end);
        return m;
    }

    /** The tools a plan includes: LeziTech's choice, or the defaults (each tool from its starting plan upwards). */
    public Set<String> planFeatures(String code) {
        String v = all().get("planFeatures");
        if (v != null) {
            try {
                Map<String, List<String>> m = json.readValue(v, new TypeReference<>() { });
                if (m.containsKey(code)) {
                    Set<String> out = new java.util.LinkedHashSet<>(m.get(code));
                    out.retainAll(PlanFeatures.KEYS);
                    return out;
                }
            } catch (Exception e) {
                // unreadable: defaults
            }
        }
        return defaultFeatures(code);
    }

    public static Set<String> defaultFeatures(String code) {
        int rank = PLAN_ORDER.indexOf(code);
        Set<String> out = new java.util.LinkedHashSet<>();
        for (PlanFeatures.Feature f : PlanFeatures.CATALOG) {
            if (rank >= PLAN_ORDER.indexOf(f.plan())) {
                out.add(f.key());
            }
        }
        return out;
    }

    /** Which tools each plan includes, from the control panel's tick grid. */
    public void setPlanFeatures(Map<String, List<String>> byPlan, String by) {
        Map<String, List<String>> clean = new LinkedHashMap<>();
        for (String code : PLAN_ORDER) {
            List<String> keys = byPlan == null ? null : byPlan.get(code);
            if (keys == null) {
                throw ApiException.bad("Send the tools for Lite, Growth and Pro");
            }
            for (String k : keys) {
                if (!PlanFeatures.KEYS.contains(k)) {
                    throw ApiException.bad("Unknown tool: " + k);
                }
            }
            clean.put(code, PlanFeatures.CATALOG.stream().map(PlanFeatures.Feature::key).filter(keys::contains).toList());
        }
        try {
            put("planFeatures", json.writeValueAsString(clean), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final java.time.format.DateTimeFormatter STAMP = java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT);

    /** True only for exactly 14 digits that are a real date and time, as yyyyMMddHHmmss (UTC). */
    public static boolean isStamp(String s) {
        if (s == null || s.length() != 14) {
            return false;
        }
        for (int i = 0; i < 14; i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        try {
            java.time.LocalDateTime.parse(s, STAMP);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private String unquoted(String key) {
        String v = all().get(key);
        if (v == null || v.equals("null")) {
            return null;
        }
        try {
            Object o = json.readValue(v, Object.class);
            return o == null ? null : String.valueOf(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** The oldest app version still allowed to run; anything older is told to reload. Null when unset. */
    public String minClientRelease() {
        return unquoted("minClientRelease");
    }

    /** The newest app version known to the platform. Null when unset. */
    public String latestClientRelease() {
        return unquoted("latestClientRelease");
    }

    /** The plain-words note shown with a reload request. Null when unset. */
    public String reloadMessage() {
        return unquoted("reloadMessage");
    }

    /** Operator path. A null argument leaves that setting alone; "clear" removes it. */
    public void setClientRelease(String latest, String min, String message, String by) {
        if (latest != null && !"clear".equals(latest) && !isStamp(latest)) {
            throw ApiException.bad("The newest version must be 14 digits like 20261121093000");
        }
        if (min != null && !"clear".equals(min) && !isStamp(min)) {
            throw ApiException.bad("The oldest allowed version must be 14 digits like 20261121093000");
        }
        if (message != null && message.length() > 200) {
            throw ApiException.bad("Keep the reload note to 200 characters or fewer");
        }
        putOrClear("latestClientRelease", latest, by);
        putOrClear("minClientRelease", min, by);
        putOrClear("reloadMessage", message, by);
    }

    private void putOrClear(String key, String value, String by) {
        if (value == null) {
            return;
        }
        try {
            put(key, "clear".equals(value) ? "null" : json.writeValueAsString(value), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int num(Object v, String what) {
        try {
            return new java.math.BigDecimal(String.valueOf(v)).intValueExact();
        } catch (RuntimeException e) {
            throw ApiException.bad("Type a whole number for " + what);
        }
    }

    private void put(String key, String value, String by) {
        jdbc.update("""
                INSERT INTO platform_settings (key, value, updated_at) VALUES (?, cast(? as jsonb), now())
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""", key, value);
        cache = null;
    }
}
