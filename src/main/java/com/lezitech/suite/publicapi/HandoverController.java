package com.lezitech.suite.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signup filled in from the website. The visitor gave their name, phone and business in the website's sheet; when they
 * tap "Start free" the website posts those here and gets a random one-time code, the only thing that goes in the signup
 * link (no personal details in addresses). Signup reads it once; it's deleted on reading and expires after 30 minutes.
 */
@RestController
@RequestMapping("/api/public/handover")
public class HandoverController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> FIELDS = List.of("businessName", "contactName", "phone", "town");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public HandoverController(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @PostMapping
    public Map<String, Object> give(@RequestBody Map<String, Object> raw, HttpServletRequest req) throws Exception {
        limit(req);
        Body b = new Body(raw);
        Map<String, Object> data = new LinkedHashMap<>();
        for (String f : FIELDS) {
            String v = b.str(f, "").trim();
            if (!v.isEmpty()) {
                data.put(f, v.length() > 200 ? v.substring(0, 200) : v);
            }
        }
        if (data.isEmpty()) {
            throw ApiException.bad("Nothing to hand over");
        }
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("DELETE FROM signup_handovers WHERE expires_at < now()");
        jdbc.update("INSERT INTO signup_handovers (code, data, expires_at) VALUES (?, cast(? as jsonb), now() + interval '30 minutes')",
                code, json.writeValueAsString(data));
        return Map.of("code", code);
    }

    /** Read once: the details come back and the code stops working. */
    @GetMapping("/{code}")
    @Transactional
    public Map<String, Object> take(@PathVariable String code) throws Exception {
        List<String> rows = jdbc.queryForList(
                "DELETE FROM signup_handovers WHERE code = ? AND expires_at > now() RETURNING data::text", String.class, code);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "That link has expired. Fill in your details below.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = json.readValue(rows.get(0), Map.class);
        return data;
    }

    /** At most 20 hand-overs an hour from one address. */
    private void limit(HttpServletRequest req) {
        Deque<Instant> d = recent.computeIfAbsent(req.getRemoteAddr(), k -> new ArrayDeque<>());
        synchronized (d) {
            Instant hourAgo = Instant.now().minusSeconds(3600);
            while (!d.isEmpty() && d.peekFirst().isBefore(hourAgo)) {
                d.pollFirst();
            }
            if (d.size() >= 20) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY", "Too many tries. Please try again later.");
            }
            d.addLast(Instant.now());
        }
    }
}
