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

    public String issue(UUID userId, UUID tenantId, String role, String name, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tv", tokenVersion)
                .claim("tid", tenantId.toString())
                .claim("role", role)
                .claim("name", name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /**
     * LeziTech support signed in as a business, under the owner's grant: carries the grant (checked on every request)
     * and never outlives it.
     */
    public String issueSupport(UUID userId, UUID tenantId, String role, String name, int tokenVersion, UUID grant, Instant grantEnds) {
        Instant now = Instant.now();
        Instant end = grantEnds.isBefore(now.plus(ttlHours, ChronoUnit.HOURS)) ? grantEnds : now.plus(ttlHours, ChronoUnit.HOURS);
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tv", tokenVersion)
                .claim("tid", tenantId.toString())
                .claim("role", role)
                .claim("name", name)
                .claim("sg", grant.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(end))
                .signWith(key)
                .compact();
    }

    /** The support grant a token was issued under, if it is a LeziTech support sign-in. */
    public Optional<UUID> supportGrantOf(String token) {
        try {
            String g = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().get("sg", String.class);
            return g == null ? Optional.empty() : Optional.of(UUID.fromString(g));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Control-panel token for LeziTech staff; carries no tenant. */
    public String issueAdmin(UUID adminId, String role, String name, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(adminId.toString())
                .claim("tv", tokenVersion)
                .claim("scope", "platform")
                .claim("role", role)
                .claim("name", name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(12, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    public Optional<com.lezitech.suite.admin.AdminContext.Admin> parseAdmin(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!"platform".equals(c.get("scope", String.class))) {
                return Optional.empty();
            }
            return Optional.of(new com.lezitech.suite.admin.AdminContext.Admin(
                    UUID.fromString(c.getSubject()), c.get("role", String.class), c.get("name", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The token_version a token was issued with (0 for tokens from before versions existed). */
    public int versionOf(String token) {
        try {
            Integer v = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().get("tv", Integer.class);
            return v == null ? 0 : v;
        } catch (JwtException | IllegalArgumentException e) {
            return -1;
        }
    }

    public Optional<TenantContext.Caller> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (c.get("tid", String.class) == null) {
                return Optional.empty();
            }
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
