package com.lezitech.suite.auth;

import com.lezitech.suite.common.ClientIp;
import com.lezitech.suite.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password and PIN guessing. After too many wrong tries for one account (or many
 * from one address) further tries are refused for a while, right or wrong. Kept in memory: one
 * API instance is enough for this product's size; move to the database if it ever runs several.
 */
@Component
public class LoginThrottle {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final int maxPerAccount;
    private final int maxPerAddress;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginThrottle(@Value("${suite.login.max-failures:5}") int maxPerAccount,
                         @Value("${suite.login.max-failures-per-address:30}") int maxPerAddress) {
        this.maxPerAccount = maxPerAccount;
        this.maxPerAddress = maxPerAddress;
    }

    /** Call before checking the password. Throws 429 if this account or address is locked out. */
    public void check(String account, HttpServletRequest req) {
        if (count(account) >= maxPerAccount || addressCount(req) >= maxPerAddress) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many wrong tries. Wait 15 minutes, or ask the owner to set a new password or PIN.");
        }
    }

    /**
     * For the control panel's two sign-in steps: counts by address only, never by the person's email, so a stranger who
     * knows an email cannot lock that person out. Throws 429 when this address has failed too often.
     */
    public void checkAddress(HttpServletRequest req) {
        if (addressCount(req) >= maxPerAddress) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many wrong tries from here. Wait 15 minutes and try again.");
        }
    }

    public void failedAddress(HttpServletRequest req) {
        if (req != null) {
            record(address(req));
        }
    }

    public void failed(String account, HttpServletRequest req) {
        record(account);
        if (req != null) {
            record(address(req));
        }
    }

    public void succeeded(String account) {
        failures.remove(account);
    }

    private void record(String key) {
        Deque<Instant> d = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(Instant.now());
        }
    }

    private int count(String key) {
        Deque<Instant> d = failures.get(key);
        if (d == null) {
            return 0;
        }
        synchronized (d) {
            Instant cutoff = Instant.now().minus(WINDOW);
            while (!d.isEmpty() && d.peekFirst().isBefore(cutoff)) {
                d.pollFirst();
            }
            return d.size();
        }
    }

    private int addressCount(HttpServletRequest req) {
        return req == null ? 0 : count(address(req));
    }

    private static String address(HttpServletRequest req) {
        // The real client address (last X-Forwarded-For entry behind the proxy); see ClientIp.
        return "ip:" + ClientIp.of(req);
    }
}
