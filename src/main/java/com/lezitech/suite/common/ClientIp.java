package com.lezitech.suite.common;

import jakarta.servlet.http.HttpServletRequest;

/** The caller's address, for rate limits. One place, so no page can be talked past its limit with a made-up header. */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * Behind Coolify's proxy the real client address is the last X-Forwarded-For entry (the one the proxy
     * added); earlier entries can be faked by the client. Without the header (direct calls, tests) it is the
     * connection's own address. A blank or odd header never throws.
     */
    public static String of(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String p = parts[i].trim();
                if (!p.isEmpty()) {
                    return p;
                }
            }
        }
        String remote = req.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
