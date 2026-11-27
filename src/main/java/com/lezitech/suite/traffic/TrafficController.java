package com.lezitech.suite.traffic;

import com.lezitech.suite.admin.AdminContext;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.ClientIp;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.sector.SectorCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** "Where visitors come from": a small page-view beacon anyone can send, and the control-panel report built from it. */
@RestController
public class TrafficController {

    private static final Logger log = LoggerFactory.getLogger(TrafficController.class);

    /** Page views from one address: 300 an hour (a busy shop wifi shares one address). */
    public static final RateLimit PER_CLIENT = new RateLimit(300, 3_600_000);

    private static final Set<String> SITES = Set.of("website", "app", "business-page");
    private static final Set<String> DEVICES = Set.of("phone", "tablet", "desktop");
    private static final Pattern VISITOR = Pattern.compile("[A-Za-z0-9-]{8,40}");
    private static final int KEEP_DAYS = 180;
    private static final String ZONE = "Africa/Nairobi";

    /** The website's page address for each sector, as the marketing site uses it. */
    private static final Map<String, String> PAGE_ID_TO_KEY = Map.of(
            "restaurants", "restaurant", "hotels", "hotel", "shops", "retail", "salons", "beauty", "wholesale", "wholesale",
            "pharmacies", "pharmacy", "services", "services", "safaris", "tours", "property", "property");
    private static final Pattern BUSINESS_PAGE = Pattern.compile("^/t/([^/]+)(/.*)?$");

    private final JdbcTemplate jdbc;
    private final SectorCatalog sectors;

    public TrafficController(JdbcTemplate jdbc, SectorCatalog sectors) {
        this.jdbc = jdbc;
        this.sectors = sectors;
    }

    /** A sector key from a website page address or a key; null when it is neither (never stored raw). */
    private String sectorKey(String text) {
        String t = cut(text, 30);
        if (t == null) {
            return null;
        }
        t = t.toLowerCase(java.util.Locale.ROOT);
        String key = PAGE_ID_TO_KEY.getOrDefault(t, t);
        return sectors.hasSector(key) ? key : null;
    }

    /** The sector and sub-sector key a visit belongs to: a business page takes them from the business itself, other pages from the client (checked). */
    private String[] kindOf(String path, Body b) {
        var m = path == null ? null : BUSINESS_PAGE.matcher(path);
        if (m != null && m.matches()) {
            List<Map<String, Object>> t = jdbc.queryForList("SELECT sector, sub_sector FROM tenants WHERE slug = ?", m.group(1));
            if (t.isEmpty()) {
                return new String[]{null, null};
            }
            String sector = sectorKey((String) t.get(0).get("sector"));
            return new String[]{sector, sector == null ? null : sectors.subKey(sector, (String) t.get(0).get("sub_sector"))};
        }
        String sector = sectorKey(b.str("sector", ""));
        return new String[]{sector, sector == null ? null : sectors.subKey(sector, cut(b.str("sub", ""), 60))};
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** One page view. A dropped view (bot, odd values) also answers 204, so nothing is learned from the answer. */
    @PostMapping("/api/public/visit")
    public ResponseEntity<Void> visit(@RequestBody Map<String, Object> raw, HttpServletRequest req) {
        if (!PER_CLIENT.allow(ClientIp.of(req))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Too many tries. Please wait a while and try again.");
        }
        if (VisitSource.bot(req.getHeader("User-Agent"))) {
            return ResponseEntity.noContent().build();
        }
        Body b = new Body(raw);
        String site = cut(b.str("site", ""), 16);
        String device = cut(b.str("device", ""), 10);
        if (site == null || !SITES.contains(site) || device == null || !DEVICES.contains(device)) {
            return ResponseEntity.noContent().build();
        }
        String path = cut(b.str("path", ""), 200);
        if (path != null) {
            path = path.split("[?#]", 2)[0];
        }
        String referrer = cut(VisitSource.host(cut(b.str("referrer", ""), 300)), 100);
        String utmSource = cut(b.str("utm_source", ""), 100);
        String utmMedium = cut(b.str("utm_medium", ""), 100);
        String utmCampaign = cut(b.str("utm_campaign", ""), 100);
        boolean gclid = b.bool("gclid", false);
        boolean fbclid = b.bool("fbclid", false);
        String visitor = cut(b.str("visitor", ""), 40);
        if (visitor != null && !VISITOR.matcher(visitor).matches()) {
            visitor = null;
        }
        var src = VisitSource.classify(referrer, utmSource, utmMedium, gclid, fbclid);
        String[] kind = kindOf(path, b);
        jdbc.update("""
                INSERT INTO visits (site, path, referrer_host, utm_source, utm_medium, utm_campaign, has_gclid, has_fbclid, device,
                                    visitor_id, first_visit, source, source_detail, sector, sub_sector)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                site, path, referrer, utmSource, utmMedium, utmCampaign, gclid, fbclid, device, visitor,
                b.bool("first", false), src.key(), src.detail(), kind[0], kind[1]);
        return ResponseEntity.noContent().build();
    }

    /** Every day: raw page views older than 180 days are deleted. */
    @Scheduled(cron = "${suite.visits.cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Visit clean-up failed: {}", e.toString());
        }
    }

    /** Deletes page views older than the keep time; returns how many went. */
    public int purge() {
        return jdbc.update("DELETE FROM visits WHERE created_at < now() - make_interval(days => ?)", KEEP_DAYS);
    }

    /** The report. range: today, 7, 30 or 90 (days). */
    @GetMapping("/api/admin/traffic")
    public Map<String, Object> report(@RequestParam(defaultValue = "30") String range) {
        AdminContext.require("traffic");
        // Day 0 is today in Kenya. "today" is one day, "7" is today and the 6 before it.
        int days = switch (range) {
            case "today", "1" -> 1;
            case "7" -> 7;
            case "90" -> 90;
            default -> 30;
        };
        java.time.ZoneId zone = java.time.ZoneId.of(ZONE);
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        java.sql.Timestamp since = java.sql.Timestamp.from(today.minusDays(days - 1L).atStartOfDay(zone).toInstant());

        // One pass over visits for the totals and the per-source numbers (ROLLUP adds the grand-total row).
        long visits = 0;
        long visitors = 0;
        Map<String, Map<String, Object>> bySource = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT source, count(*) AS visits, count(DISTINCT visitor_id) AS visitors, GROUPING(source) AS total
                FROM visits WHERE created_at >= ? GROUP BY ROLLUP(source)""", since)) {
            long v = ((Number) r.get("visits")).longValue();
            long u = ((Number) r.get("visitors")).longValue();
            if (((Number) r.get("total")).intValue() == 1) {
                visits = v;
                visitors = u;
            } else {
                bySource.put((String) r.get("source"), row((String) r.get("source"), v, u));
            }
        }
        // One pass over tenants for the sign-up total and its sources.
        long signups = 0;
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT coalesce(signup_source->>'source', 'unknown') AS source, count(*) AS n
                FROM tenants WHERE NOT is_demo AND created_at >= ? GROUP BY 1""", since)) {
            long n = ((Number) r.get("n")).longValue();
            signups += n;
            String key = (String) r.get("source");
            bySource.computeIfAbsent(key, k -> row(k, 0, 0)).put("signups", n);
        }
        List<Map<String, Object>> sources = new ArrayList<>(bySource.values());
        for (Map<String, Object> s : sources) {
            s.put("rate", rate(((Number) s.get("signups")).longValue(), ((Number) s.get("visitors")).longValue()));
        }
        sources.sort((a, b) -> Long.compare(((Number) b.get("visits")).longValue(), ((Number) a.get("visits")).longValue()));

        // One more pass for pages, referrers, devices and the per-day bars (GROUPING SETS).
        List<Map<String, Object>> pages = new ArrayList<>();
        List<Map<String, Object>> referrers = new ArrayList<>();
        List<Map<String, Object>> devices = new ArrayList<>();
        Map<String, Long> dayVisits = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT site, path, referrer_host, device, day,
                       GROUPING(site) AS g_page, GROUPING(referrer_host) AS g_ref, GROUPING(device) AS g_dev, GROUPING(day) AS g_day,
                       count(*) AS visits, count(DISTINCT visitor_id) AS visitors,
                       count(*) FILTER (WHERE first_visit) AS first_visits,
                       count(DISTINCT visitor_id) FILTER (WHERE first_visit) AS first_visitors
                FROM (SELECT site, coalesce(path, '/') AS path, referrer_host, device, visitor_id, first_visit,
                             to_char(created_at AT TIME ZONE ?, 'YYYY-MM-DD') AS day
                      FROM visits WHERE created_at >= ?) v
                GROUP BY GROUPING SETS ((site, path), (referrer_host), (device), (day))""", ZONE, since)) {
            long v = ((Number) r.get("visits")).longValue();
            long u = ((Number) r.get("visitors")).longValue();
            if (((Number) r.get("g_page")).intValue() == 0) {
                long fv = ((Number) r.get("first_visits")).longValue();
                if (fv > 0) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("site", r.get("site"));
                    m.put("path", r.get("path"));
                    m.put("visits", fv);
                    m.put("visitors", ((Number) r.get("first_visitors")).longValue());
                    pages.add(m);
                }
            } else if (((Number) r.get("g_ref")).intValue() == 0) {
                if (r.get("referrer_host") != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("host", r.get("referrer_host"));
                    m.put("visits", v);
                    m.put("visitors", u);
                    referrers.add(m);
                }
            } else if (((Number) r.get("g_dev")).intValue() == 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("device", r.get("device"));
                m.put("visits", v);
                devices.add(m);
            } else if (((Number) r.get("g_day")).intValue() == 0) {
                dayVisits.put((String) r.get("day"), v);
            }
        }
        java.util.Comparator<Map<String, Object>> byVisits = (a, b) -> Long.compare(((Number) b.get("visits")).longValue(), ((Number) a.get("visits")).longValue());
        pages.sort(byVisits.thenComparing(m -> (String) m.get("path")).thenComparing(m -> (String) m.get("site")));
        referrers.sort(byVisits.thenComparing(m -> (String) m.get("host")));
        devices.sort(byVisits.thenComparing(m -> String.valueOf(m.get("device"))));
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", day);
            m.put("visits", dayVisits.getOrDefault(day, 0L));
            perDay.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", days == 1 ? "today" : String.valueOf(days));
        out.put("days", days);
        out.put("visits", visits);
        out.put("visitors", visitors);
        out.put("signups", signups);
        out.put("rate", rate(signups, visitors));
        out.put("sources", sources);
        out.put("pages", pages.subList(0, Math.min(10, pages.size())));
        out.put("referrers", referrers.subList(0, Math.min(10, referrers.size())));
        out.put("devices", devices);
        out.put("perDay", perDay);
        out.put("byKind", byKind(since));
        out.put("businessPages", businessPages(since));
        return out;
    }

    private static long n(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private Map<String, Object> kindRow(String key, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("websiteVisits", 0L);
        m.put("websiteVisitors", 0L);
        m.put("signupPageOpens", 0L);
        m.put("signups", 0L);
        m.put("payingNow", 0L);
        m.put("rate", 0.0);
        return m;
    }

    /** Per business type (and its sub-types): website visits, sign-up page opens, sign-ups in the range and paying now. */
    private List<Map<String, Object>> byKind(java.sql.Timestamp since) {
        Map<String, Map<String, Object>> top = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> subs = new LinkedHashMap<>();
        for (String k : sectors.keys()) {
            top.put(k, kindRow(k, sectors.get(k).path("name").asText(k)));
            subs.put(k, new LinkedHashMap<>());
            for (var s : sectors.subList(k)) {
                subs.get(k).put(s.get("key").asText(), kindRow(s.get("key").asText(), s.get("name").asText()));
            }
        }
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT sector, sub_sector, GROUPING(sub_sector) AS g_sub,
                       count(*) FILTER (WHERE site = 'website') AS web_visits,
                       count(DISTINCT visitor_id) FILTER (WHERE site = 'website') AS web_visitors,
                       count(*) FILTER (WHERE site = 'app') AS opens
                FROM visits
                WHERE created_at >= ? AND sector IS NOT NULL AND (site = 'website' OR (site = 'app' AND path = '/start'))
                GROUP BY GROUPING SETS ((sector), (sector, sub_sector))""", since)) {
            Map<String, Object> m = ((Number) r.get("g_sub")).intValue() == 1 ? top.get((String) r.get("sector"))
                    : subs.getOrDefault((String) r.get("sector"), Map.of()).get((String) r.get("sub_sector"));
            if (m != null) {
                m.put("websiteVisits", n(r.get("web_visits")));
                m.put("websiteVisitors", n(r.get("web_visitors")));
                m.put("signupPageOpens", n(r.get("opens")));
            }
        }
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT sector, sub_sector, count(*) FILTER (WHERE created_at >= ?) AS signups,
                       count(*) FILTER (WHERE status = 'ACTIVE' AND (paid_until IS NULL OR paid_until > now())) AS paying -- same rule as the Overview's money a month
                FROM tenants WHERE NOT is_demo GROUP BY sector, sub_sector""", since)) {
            String sector = (String) r.get("sector");
            Map<String, Object> m = top.get(sector);
            if (m == null) {
                continue;
            }
            for (String f : new String[]{"signups", "payingNow"}) {
                long add = n(r.get(f.equals("signups") ? "signups" : "paying"));
                m.put(f, n(m.get(f)) + add);
                String subKey = sectors.subKey(sector, (String) r.get("sub_sector"));
                if (subKey != null && subs.get(sector).containsKey(subKey)) {
                    Map<String, Object> sm = subs.get(sector).get(subKey);
                    sm.put(f, n(sm.get(f)) + add);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : top.keySet()) {
            Map<String, Object> m = top.get(k);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> s : subs.get(k).values()) {
                if (n(s.get("websiteVisits")) + n(s.get("signupPageOpens")) + n(s.get("signups")) + n(s.get("payingNow")) > 0) {
                    s.put("rate", rate(n(s.get("signups")), n(s.get("websiteVisitors"))));
                    list.add(s);
                }
            }
            m.put("rate", rate(n(m.get("signups")), n(m.get("websiteVisitors"))));
            list.sort((a, c) -> Long.compare(n(c.get("websiteVisits")), n(a.get("websiteVisits"))));
            m.put("subs", list);
            out.add(m);
        }
        out.sort((a, c) -> Long.compare(n(c.get("websiteVisits")), n(a.get("websiteVisits"))));
        return out;
    }

    /** Visits to businesses' own pages (/t/...), per business type, and the ten businesses with the most. */
    private Map<String, Object> businessPages(java.sql.Timestamp since) {
        List<Map<String, Object>> bySector = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT v.sector, count(*) AS visits, count(DISTINCT v.visitor_id) AS visitors
                FROM visits v JOIN tenants t ON t.slug = split_part(v.path, '/', 3)
                WHERE v.created_at >= ? AND v.site = 'business-page' AND v.path LIKE '/t/%' AND v.sector IS NOT NULL AND NOT t.is_demo
                GROUP BY v.sector ORDER BY count(*) DESC, v.sector""", since)) {
            String k = (String) r.get("sector");
            if (!sectors.hasSector(k)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            m.put("name", sectors.get(k).path("name").asText(k));
            m.put("visits", n(r.get("visits")));
            m.put("visitors", n(r.get("visitors")));
            bySector.add(m);
        }
        List<Map<String, Object>> topBusinesses = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT t.name, t.sector, count(*) AS visits, count(DISTINCT v.visitor_id) AS visitors
                FROM visits v JOIN tenants t ON t.slug = split_part(v.path, '/', 3)
                WHERE v.created_at >= ? AND v.site = 'business-page' AND v.path LIKE '/t/%' AND NOT t.is_demo
                GROUP BY t.id, t.name, t.sector ORDER BY count(*) DESC, t.name LIMIT 10""", since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.get("name"));
            String k = (String) r.get("sector");
            m.put("sector", sectors.hasSector(k) ? sectors.get(k).path("name").asText(k) : k);
            m.put("visits", n(r.get("visits")));
            m.put("visitors", n(r.get("visitors")));
            topBusinesses.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bySector", bySector);
        out.put("top", topBusinesses);
        return out;
    }

    private static Map<String, Object> row(String source, long visits, long visitors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("visits", visits);
        m.put("visitors", visitors);
        m.put("signups", 0L);
        return m;
    }

    /** Sign-ups as a share of unique visitors, in percent with one decimal; 0 when nobody visited. */
    static double rate(long signups, long visitors) {
        return visitors <= 0 ? 0 : Math.round(signups * 1000.0 / visitors) / 10.0;
    }
}
