package com.lezitech.suite.traffic;

import java.util.Locale;
import java.util.Set;

/** Works out where a visit came from: Google search, Google Ads, Facebook or Instagram, WhatsApp, TikTok, X, email, direct or another site. */
public final class VisitSource {

    public record Source(String key, String detail) {
    }

    /** Our own sites: a visit that came from one of them is not a new source. */
    private static final Set<String> OWN = Set.of("busynez.site", "lezitech.site", "localhost");
    private static final Set<String> PAID = Set.of("cpc", "ppc", "paid", "paidsearch", "paid_search", "paid-search", "display", "sem");
    private static final Set<String> EMAIL_SOURCES = Set.of("email", "e-mail", "newsletter", "mail");

    private VisitSource() {
    }

    /** The host of a referrer, lowercase, no "www.", no path: accepts a bare host or a full address. Null when empty. */
    public static String host(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        for (char c : new char[]{'/', '?', '#'}) {
            int i = s.indexOf(c);
            if (i >= 0) {
                s = s.substring(0, i);
            }
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        if (s.startsWith("www.")) {
            s = s.substring(4);
        }
        return s.isBlank() ? null : s;
    }

    private static boolean is(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean own(String host) {
        return OWN.stream().anyMatch(d -> is(host, d));
    }

    public static Source classify(String referrerHost, String utmSource, String utmMedium, boolean gclid, boolean fbclid) {
        String src = utmSource == null ? "" : utmSource.trim().toLowerCase(Locale.ROOT);
        String med = utmMedium == null ? "" : utmMedium.trim().toLowerCase(Locale.ROOT);
        String host = host(referrerHost);
        if (host != null && own(host)) {
            host = null;
        }
        boolean paid = PAID.contains(med);

        // What the link itself says wins over where the browser says it came from.
        if (gclid || (src.equals("google") && paid) || src.equals("google_ads") || src.equals("googleads") || src.equals("adwords")) {
            return new Source("google_ads", null);
        }
        if (fbclid || src.equals("facebook") || src.equals("fb") || src.equals("instagram") || src.equals("ig") || src.equals("meta")) {
            return new Source("facebook", null);
        }
        if (src.equals("whatsapp") || src.equals("wa")) {
            return new Source("whatsapp", null);
        }
        if (src.equals("tiktok")) {
            return new Source("tiktok", null);
        }
        if (src.equals("x") || src.equals("twitter")) {
            return new Source("x", null);
        }
        if (med.equals("email") || med.equals("e-mail") || EMAIL_SOURCES.contains(src)) {
            return new Source("email", null);
        }
        if (src.equals("google")) {
            return new Source("google_search", null);
        }
        if (host == null) {
            return new Source("direct", null);
        }
        if (host.startsWith("google.") || host.contains(".google.")) {
            return new Source("google_search", null);
        }
        if (is(host, "facebook.com") || is(host, "fb.com") || is(host, "instagram.com") || is(host, "fb.me")) {
            return new Source("facebook", null);
        }
        if (is(host, "wa.me") || is(host, "whatsapp.com") || is(host, "whatsapp.net")) {
            return new Source("whatsapp", null);
        }
        if (is(host, "tiktok.com")) {
            return new Source("tiktok", null);
        }
        if (is(host, "x.com") || is(host, "twitter.com") || is(host, "t.co")) {
            return new Source("x", null);
        }
        if (host.equals("mail.google.com") || is(host, "outlook.live.com") || is(host, "outlook.office.com") || is(host, "mail.yahoo.com")) {
            return new Source("email", null);
        }
        return new Source("other_site", host.length() > 100 ? host.substring(0, 100) : host);
    }

    /** Simple bot check on the browser text (which is never stored). Empty text counts as a bot. */
    public static boolean bot(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        for (String b : new String[]{"bot", "crawl", "spider", "slurp", "headless", "lighthouse", "preview", "curl/", "wget", "python-", "httpclient",
                "okhttp", "java/", "java-http-client", "go-http", "axios", "node-fetch", "facebookexternalhit", "pingdom", "uptime", "monitor", "scrape"}) {
            if (ua.contains(b)) {
                return true;
            }
        }
        return false;
    }
}
