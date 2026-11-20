package com.lezitech.suite.publicapi;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small in-memory limit for pages anyone can use without signing in (ordering from a table, payment prompts).
 * One server runs BizNez, so memory is enough; a restart only forgets the last hour.
 */
public final class RateLimit {

    /** Most addresses remembered at once, so a flood of different callers cannot grow memory without limit. */
    private static final int DEFAULT_MAX_KEYS = 50_000;

    private final int max;
    private final long windowMs;
    private final int maxKeys;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimit(int max, long windowMs) {
        this(max, windowMs, DEFAULT_MAX_KEYS);
    }

    /** maxKeys is how many callers are remembered at once; a test passes a small one to check the clean-up. */
    public RateLimit(int max, long windowMs, int maxKeys) {
        this.max = max;
        this.windowMs = windowMs;
        this.maxKeys = maxKeys;
    }

    /** True if this key may go ahead now (and counts it). */
    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
                q.pollFirst();
            }
            if (q.size() >= max) {
                return false;
            }
            q.addLast(now);
        }
        if (hits.size() > maxKeys) {
            trim(now);
        }
        return true;
    }

    /** Drops callers whose hits have all expired; if still over the cap, drops arbitrary entries until under it. */
    private void trim(long now) {
        hits.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            synchronized (d) {
                return d.isEmpty() || now - d.peekLast() > windowMs;
            }
        });
        if (hits.size() > maxKeys) {
            var it = hits.keySet().iterator();
            while (hits.size() > maxKeys && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** For tests. */
    public void clear() {
        hits.clear();
    }

    /** For tests: how many callers are remembered right now. */
    public int size() {
        return hits.size();
    }
}
