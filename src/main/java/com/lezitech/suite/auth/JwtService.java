package com.lezitech.suite.auth;

import com.lezitech.suite.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlHours;

    public JwtService(@Value("${suite.jwt.secret}") String secret, @Value("${suite.jwt.ttl-hours}") long ttlHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlHours = ttlHours;
    }

    public String issue(UUID userId, UUID tenantId, String role, String name) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tid", tenantId.toString())
                .claim("role", role)
                .claim("name", name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    public Optional<TenantContext.Caller> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new TenantContext.Caller(
                    UUID.fromString(c.getSubject()),
                    UUID.fromString(c.get("tid", String.class)),
                    c.get("role", String.class),
                    c.get("name", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
