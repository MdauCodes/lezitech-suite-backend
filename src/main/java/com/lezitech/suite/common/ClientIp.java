package com.lezitech.suite.common;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;

/** The caller's address, for rate limits. One place, so no page can be talked past its limit with a made-up header. */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * The caller's own address.
     *
     * <p>X-Forwarded-For is written by whoever sends the request, so it can only be believed when the request
     * reached us through our own proxy. We know that by who we are actually talking to: the proxy sits beside the
     * app on a private network, so a private or loopback peer is ours and the header is the proxy's record of the
     * real caller. A public peer reached the app directly, and anything it put in that header is its own invention
     * — believing it would hand every rate limit (public pages, signing in, signing up) to anyone who sent one.
     *
     * <p>Two things to know if the setup changes. If the proxy is ever moved to a public address, this stops
     * trusting it and every caller behind it shares one bucket, so this rule has to be revisited. And if a service
     * like Cloudflare is ever put in front, the last entry becomes Cloudflare's edge address, which lumps many
     * customers together: trust its own header (CF-Connecting-IP) instead. Anything else on our private network is
     * trusted by this, so that network must stay ours.
     */
    public static String of(HttpServletRequest req) {
        String peer = req.getRemoteAddr();
        if (peer == null || peer.isBlank()) {
            return "unknown";
        }
        if (!behindOurProxy(peer)) {
            return peer;
        }
        String forwarded = lastForwarded(req);
        return forwarded == null ? peer : forwarded;
    }

    /** True when the thing we are talking to is on our own machine or private network, so it is our proxy. */
    private static boolean behindOurProxy(String peer) {
        String ip = peer.startsWith("::ffff:") ? peer.substring(7) : peer;
        if (ip.equals("::1") || ip.startsWith("127.")) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            // 172.16.0.0 to 172.31.255.255 is private; 172.32+ and below 172.16 are not.
            int dot = ip.indexOf('.', 4);
            try {
                int second = Integer.parseInt(dot < 0 ? ip.substring(4) : ip.substring(4, dot));
                return second >= 16 && second <= 31;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // Private IPv6 (fc00::/7), as Docker networks use.
        String lower = ip.toLowerCase();
        return lower.startsWith("fc") || lower.startsWith("fd");
    }

    /**
     * The last address our proxy recorded. A proxy appends the caller it heard from, so the last entry is the one
     * it added and the earlier ones came from the caller. Read across every X-Forwarded-For line, not just the
     * first: a proxy may add a second line rather than append to the one already there, and reading only the first
     * line would take the caller's own value.
     */
    private static String lastForwarded(HttpServletRequest req) {
        Enumeration<String> lines = req.getHeaders("X-Forwarded-For");
        String last = null;
        while (lines != null && lines.hasMoreElements()) {
            String line = lines.nextElement();
            if (line == null) {
                continue;
            }
            for (String part : line.split(",")) {
                String one = part.trim();
                if (!one.isEmpty()) {
                    last = one;
                }
            }
        }
        return last;
    }
}
