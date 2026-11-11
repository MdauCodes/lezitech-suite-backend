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

    private final int max;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimit(int max, long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
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
        if (hits.size() > 50_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return true;
    }

    /** For tests. */
    public void clear() {
        hits.clear();
    }
}
