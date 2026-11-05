package com.lezitech.suite.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token revocation. Every login token carries the person's token_version; switching someone
 * off, changing their password or their job bumps it, and older tokens stop working at once.
 * Looked up per request with a short cache so it doesn't cost a query every time.
 */
@Component
public class TokenVersions {

    private static final long TTL_MS = 10_000;

    private record Entry(int version, boolean active, long at) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public TokenVersions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int current(UUID userId, boolean admin) {
        Entry e = lookup(userId, admin);
        return e == null ? -1 : e.version();
    }

    /** True when the token's version still matches and the person is still allowed in. */
    public boolean valid(UUID userId, boolean admin, int tokenVersion) {
        Entry e = lookup(userId, admin);
        return e != null && e.active() && e.version() == tokenVersion;
    }

    public void bump(UUID userId, boolean admin) {
        jdbc.update("UPDATE " + (admin ? "platform_admins" : "users") + " SET token_version = token_version + 1 WHERE id = ?", userId);
        cache.remove(key(userId, admin));
    }

    private Entry lookup(UUID userId, boolean admin) {
        String k = key(userId, admin);
        Entry e = cache.get(k);
        long now = System.currentTimeMillis();
        if (e != null && now - e.at() < TTL_MS) {
            return e;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT token_version, active FROM " + (admin ? "platform_admins" : "users") + " WHERE id = ?", userId);
        if (rows.isEmpty()) {
            cache.remove(k);
            return null;
        }
        e = new Entry(((Number) rows.get(0).get("token_version")).intValue(), Boolean.TRUE.equals(rows.get(0).get("active")), now);
        cache.put(k, e);
        return e;
    }

    private static String key(UUID id, boolean admin) {
        return (admin ? "a:" : "u:") + id;
    }
}
