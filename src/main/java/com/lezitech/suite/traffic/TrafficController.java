package com.lezitech.suite.traffic;

import com.lezitech.suite.admin.AdminContext;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.ClientIp;
import com.lezitech.suite.publicapi.RateLimit;
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

    private final JdbcTemplate jdbc;

    public TrafficController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        jdbc.update("""
                INSERT INTO visits (site, path, referrer_host, utm_source, utm_medium, utm_campaign, has_gclid, has_fbclid, device,
                                    visitor_id, first_visit, source, source_detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                site, path, referrer, utmSource, utmMedium, utmCampaign, gclid, fbclid, device, visitor,
                b.bool("first", false), src.key(), src.detail());
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
        String since = "(date_trunc('day', now() AT TIME ZONE '" + ZONE + "') - make_interval(days => " + (days - 1) + ")) AT TIME ZONE '" + ZONE + "'";

        Map<String, Object> totals = jdbc.queryForMap("SELECT count(*) AS visits, count(DISTINCT visitor_id) AS visitors FROM visits WHERE created_at >= " + since);
        long visits = ((Number) totals.get("visits")).longValue();
        long visitors = ((Number) totals.get("visitors")).longValue();
        long signups = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE NOT is_demo AND created_at >= " + since, Long.class);

        Map<String, Map<String, Object>> bySource = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT source, count(*) AS visits, count(DISTINCT visitor_id) AS visitors FROM visits WHERE created_at >= "
                + since + " GROUP BY source")) {
            bySource.put((String) r.get("source"), row((String) r.get("source"), ((Number) r.get("visits")).longValue(), ((Number) r.get("visitors")).longValue()));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT coalesce(signup_source->>'source', 'unknown') AS source, count(*) AS n FROM tenants WHERE NOT is_demo AND created_at >= "
                + since + " GROUP BY 1")) {
            String key = (String) r.get("source");
            bySource.computeIfAbsent(key, k -> row(k, 0, 0)).put("signups", ((Number) r.get("n")).longValue());
        }
        List<Map<String, Object>> sources = new ArrayList<>(bySource.values());
        for (Map<String, Object> s : sources) {
            s.put("rate", rate(((Number) s.get("signups")).longValue(), ((Number) s.get("visitors")).longValue()));
        }
        sources.sort((a, b) -> Long.compare(((Number) b.get("visits")).longValue(), ((Number) a.get("visits")).longValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", days == 1 ? "today" : String.valueOf(days));
        out.put("days", days);
        out.put("visits", visits);
        out.put("visitors", visitors);
        out.put("signups", signups);
        out.put("rate", rate(signups, visitors));
        out.put("sources", sources);
        out.put("pages", jdbc.queryForList("SELECT site, coalesce(path, '/') AS path, count(*) AS visits, count(DISTINCT visitor_id) AS visitors FROM visits WHERE created_at >= "
                + since + " AND first_visit GROUP BY 1, 2 ORDER BY 3 DESC, 2 LIMIT 10"));
        out.put("referrers", jdbc.queryForList("SELECT referrer_host AS host, count(*) AS visits, count(DISTINCT visitor_id) AS visitors FROM visits WHERE referrer_host IS NOT NULL AND created_at >= "
                + since + " GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 10"));
        out.put("devices", jdbc.queryForList("SELECT device, count(*) AS visits FROM visits WHERE created_at >= " + since + " GROUP BY 1 ORDER BY 2 DESC"));
        out.put("perDay", jdbc.queryForList("""
                SELECT to_char(d, 'YYYY-MM-DD') AS day, coalesce(v.visits, 0) AS visits
                FROM generate_series(date_trunc('day', now() AT TIME ZONE '%1$s') - make_interval(days => %2$d), date_trunc('day', now() AT TIME ZONE '%1$s'), interval '1 day') d
                LEFT JOIN (SELECT date_trunc('day', created_at AT TIME ZONE '%1$s') AS day, count(*) AS visits FROM visits WHERE created_at >= %3$s GROUP BY 1) v ON v.day = d
                ORDER BY d""".formatted(ZONE, days - 1, since)));
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
