package com.lezitech.suite.config;

import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One log line per API request (method, path, status, time) and a request id on every log line
 * written while handling it, so a customer's complaint can be traced. The workspace id is added
 * once the login has been read.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("http");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader("X-Request-Id");
        if (id == null || id.isBlank() || id.length() > 64) {
            id = UUID.randomUUID().toString().substring(0, 12);
        }
        MDC.put("requestId", id);
        res.setHeader("X-Request-Id", id);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.Caller c = TenantContext.callerOrNull();
            if (c != null) {
                MDC.put("tenantId", c.tenantId().toString());
            }
            if (req.getRequestURI().startsWith("/api/")) {
                log.info("{} {} {} {}ms", req.getMethod(), maskSecrets(req.getRequestURI()), res.getStatus(), (System.nanoTime() - start) / 1_000_000);
            }
            MDC.clear();
        }
    }

    /** Payment callback URLs carry a secret as a path segment; it must never reach the logs. */
    private static final Pattern CALLBACK_SECRET = Pattern.compile(
            "^(/api/public/(?:mpesa/stk|payhero|mpesa/c2b))/[^/]+", Pattern.CASE_INSENSITIVE);

    /**
     * Any long opaque segment: the links we email and text to customers carry one, and it is the whole key to
     * that stay, booking, statement or order. Matching by shape rather than by path means a token added later
     * is covered without anyone remembering this file. Slugs, numbers and UUID-free words are left alone.
     */
    private static final Pattern LONG_TOKEN = Pattern.compile("/(?=[^/]{24,})(?=[^/]*[A-Za-z])[A-Za-z0-9_-]{24,}(?=/|$)");

    /**
     * Replaces the secret segment of a callback path with *** and leaves every other path unchanged.
     * Public because anything else that records a request path must mask it too: error alerts are emailed,
     * so a secret reaching one leaves the server entirely.
     */
    public static String maskSecrets(String uri) {
        if (uri == null) {
            return null;
        }
        return LONG_TOKEN.matcher(CALLBACK_SECRET.matcher(uri).replaceFirst("$1/***")).replaceAll("/***");
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }
}
