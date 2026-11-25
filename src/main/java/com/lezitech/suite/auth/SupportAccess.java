package com.lezitech.suite.auth;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * "Let LeziTech help": LeziTech can sign in as a business only while its owner has granted access (5 hours, which the
 * owner can stop any time). The sign-in carries the grant: every request checks it is still active, so stopping it
 * ends the session at once, and every change made during it is written to the business's activity log, marked
 * "LeziTech support". Demo businesses need no grant. Without a grant LeziTech can only look (support_looks): the
 * server refuses every change.
 *
 * support_grants has no row-level security (the control panel reads it before signing in): always filter by tenant.
 */
@Service
public class SupportAccess {

    static final int HOURS = 5;

    /** The grant behind the current request, when it comes from a LeziTech support sign-in. */
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    /** True while serving a look-only opening: every database change is refused (see TenantTransactionManager). */
    private static final ThreadLocal<Boolean> LOOK_ONLY = new ThreadLocal<>();

    /** A look-only opening lasts at most this long. */
    public static final int LOOK_HOURS = 2;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.activity.ActivityLog activity;

    public SupportAccess(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void set(UUID grant) {
        CURRENT.set(grant);
    }

    static void clear() {
        CURRENT.remove();
        LOOK_ONLY.remove();
    }

    static void setLookOnly(boolean on) {
        LOOK_ONLY.set(on);
    }

    /** True when the current request is LeziTech support looking at a business with no grant. */
    public static boolean lookOnly() {
        return Boolean.TRUE.equals(LOOK_ONLY.get());
    }

    /** The grant now in force for a business, if any. */
    public Optional<Map<String, Object>> active(UUID tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT g.id, g.granted_at, g.expires_at, g.note, u.name AS granted_by_name
                FROM support_grants g LEFT JOIN users u ON u.id = g.granted_by
                WHERE g.tenant_id = ? AND g.stopped_at IS NULL AND g.expires_at > now()
                ORDER BY g.expires_at DESC LIMIT 1""", tenant);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    boolean stillActive(UUID grant, UUID tenant) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM support_grants WHERE id = ? AND tenant_id = ? AND stopped_at IS NULL AND expires_at > now()""",
                Integer.class, grant, tenant);
        return n != null && n > 0;
    }

    /** The owner lets LeziTech in for 5 hours (or extends an active grant to 5 hours from now). */
    public Map<String, Object> grant(String note) {
        requireOwnerInPerson();
        UUID tenant = TenantContext.tenantId();
        String n = note == null || note.isBlank() ? null : note.trim().length() > 200 ? note.trim().substring(0, 200) : note.trim();
        Optional<Map<String, Object>> now = active(tenant);
        if (now.isPresent()) {
            jdbc.update("UPDATE support_grants SET expires_at = now() + make_interval(hours => ?), note = coalesce(?, note) WHERE id = ?",
                    HOURS, n, now.get().get("id"));
        } else {
            jdbc.update("INSERT INTO support_grants (tenant_id, granted_by, expires_at, note) VALUES (?, ?, now() + make_interval(hours => ?), ?)",
                    tenant, TenantContext.userId(), HOURS, n);
        }
        jdbc.update("UPDATE support_requests SET resolved_at = now(), outcome = 'ALLOWED' WHERE tenant_id = ? AND resolved_at IS NULL", tenant);
        activity.record("SUPPORT_GRANTED", com.lezitech.suite.activity.ActivityLog.NOTICE,
                "Let LeziTech help for " + HOURS + " hours" + (n == null ? "" : ": " + n));
        return status();
    }

    /** The owner stops it now; any LeziTech sign-in ends on its next request. */
    public Map<String, Object> stop() {
        requireOwnerInPerson();
        int n = jdbc.update("UPDATE support_grants SET stopped_at = now(), stopped_by = ? WHERE tenant_id = ? AND stopped_at IS NULL AND expires_at > now()",
                TenantContext.userId(), TenantContext.tenantId());
        int looks = jdbc.update("UPDATE support_looks SET ended_at = now() WHERE tenant_id = ? AND ended_at IS NULL AND expires_at > now()", TenantContext.tenantId());
        if (n > 0 || looks > 0) {
            activity.record("SUPPORT_STOPPED", com.lezitech.suite.activity.ActivityLog.INFO, "Stopped LeziTech's access");
        }
        return status();
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<Map<String, Object>> g = active(TenantContext.tenantId());
        out.put("active", g.isPresent());
        out.put("expiresAt", g.map(x -> x.get("expires_at")).orElse(null));
        out.put("grantedAt", g.map(x -> x.get("granted_at")).orElse(null));
        out.put("grantedBy", g.map(x -> x.get("granted_by_name")).orElse(null));
        out.put("hours", HOURS);
        out.put("inSupportSession", current().isPresent());
        out.put("lookOnly", lookOnly());
        // Who is looking at the business right now (look only), so the owner's staff can see it.
        out.put("looking", looking(TenantContext.tenantId()));
        // LeziTech support asking for help access: the owner answers Allow or Not now.
        out.put("request", current().isPresent() ? null : pendingRequest(TenantContext.tenantId()).orElse(null));
        return out;
    }

    /** Support looking at the business now: name, since, until. */
    public List<Map<String, Object>> looking(UUID tenant) {
        return jdbc.queryForList("""
                SELECT l.admin_name AS name, l.started_at AS since, l.expires_at AS until
                FROM support_looks l JOIN platform_admins a ON a.id = l.admin_id
                WHERE l.tenant_id = ? AND l.ended_at IS NULL AND l.expires_at > now() AND a.active AND a.blocked_at IS NULL
                ORDER BY l.started_at""", tenant);
    }

    /** A look-only opening is still open and the person is still on the team. */
    boolean lookActive(UUID look, UUID tenant) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM support_looks l JOIN platform_admins a ON a.id = l.admin_id
                WHERE l.id = ? AND l.tenant_id = ? AND l.ended_at IS NULL AND l.expires_at > now() AND a.active AND a.blocked_at IS NULL""",
                Integer.class, look, tenant);
        return n != null && n > 0;
    }

    /** Opens a look-only session for this person (at most 2 hours). Returns {id, expires}. */
    public Map<String, Object> openLook(UUID tenant, UUID adminId, String adminName, String reason) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO support_looks (tenant_id, admin_id, admin_name, reason, expires_at)
                VALUES (?, ?, ?, ?, now() + make_interval(hours => ?)) RETURNING id""", UUID.class, tenant, adminId, adminName, reason, LOOK_HOURS);
        Timestamp ends = jdbc.queryForObject("SELECT expires_at FROM support_looks WHERE id = ?", Timestamp.class, id);
        return Map.of("id", id, "expires", ends.toInstant());
    }

    public Optional<Map<String, Object>> pendingRequest(UUID tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, admin_name AS name, reason, created_at AS at FROM support_requests
                WHERE tenant_id = ? AND resolved_at IS NULL AND created_at > now() - interval '24 hours'
                ORDER BY created_at DESC LIMIT 1""", tenant);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** LeziTech support asks the owner for help access. One open ask per business: asking again returns it. */
    public Map<String, Object> requestHelp(UUID tenant, UUID adminId, String adminName, String reason) {
        Optional<Map<String, Object>> open = pendingRequest(tenant);
        if (open.isPresent()) {
            return open.get();
        }
        jdbc.update("INSERT INTO support_requests (tenant_id, admin_id, admin_name, reason) VALUES (?, ?, ?, ?)", tenant, adminId, adminName, reason);
        return pendingRequest(tenant).orElseThrow();
    }

    /** The owner says Not now. */
    public Map<String, Object> declineRequest() {
        requireOwnerInPerson();
        jdbc.update("UPDATE support_requests SET resolved_at = now(), outcome = 'DECLINED' WHERE tenant_id = ? AND resolved_at IS NULL", TenantContext.tenantId());
        return status();
    }

    /** The opening of a business by LeziTech support goes in the business's activity log (as that support person). */
    public void logOpening(UUID tenant, UUID userId, String adminName, String mode, String reason) {
        TenantContext.Caller previous = TenantContext.callerOrNull();
        TenantContext.set(new TenantContext.Caller(userId, tenant, "OWNER", "LeziTech support (" + adminName + ")"));
        try {
            tx.executeWithoutResult(s -> activity.record("SUPPORT_OPENED", com.lezitech.suite.activity.ActivityLog.NOTICE,
                    "LeziTech support (" + adminName + ") opened your business, " + ("LOOK".equals(mode) ? "looking only" : "able to help")
                            + ": " + reason, null, null, null, null, Map.of("mode", mode, "reason", reason)));
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    /** Only the owner, signed in themselves (not LeziTech signed in as them), decides who gets in. */
    private static void requireOwnerInPerson() {
        TenantContext.requireRole();
        if (current().isPresent()) {
            throw ApiException.forbidden("Only the owner can give or stop LeziTech's access");
        }
    }

    /** A change made during a support sign-in, for the business's activity log. */
    void logChange(String method, String path, int status) {
        try {
            tx.executeWithoutResult(s -> activity.record("SUPPORT_ACTION", com.lezitech.suite.activity.ActivityLog.NOTICE,
                    "LeziTech support: " + describe(method, path) + (status >= 400 ? " (refused)" : ""),
                    null, null, null, null, Map.of("method", method, "path", path, "status", status)));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(SupportAccess.class).warn("Couldn't log support action {} {}: {}", method, path, e.toString());
        }
    }

    /** "PUT /api/items/…" → "changed items". Plain words for the owner; the exact address is kept in the detail. */
    static String describe(String method, String path) {
        String[] parts = path.replaceFirst("^/api/", "").split("/");
        String what = parts.length > 0 ? parts[0].replace('-', ' ') : path;
        String verb = switch (method) {
            case "POST" -> "added or did something in";
            case "PUT", "PATCH" -> "changed";
            case "DELETE" -> "removed from";
            default -> method.toLowerCase();
        };
        return verb + " " + what;
    }

    /** For the control panel: the grant in force, or why signing in isn't allowed. */
    public Map<String, Object> forAdmin(UUID tenant) {
        List<Map<String, Object>> found = jdbc.queryForList("SELECT is_demo FROM tenants WHERE id = ?", tenant);
        if (found.isEmpty()) {
            throw ApiException.notFound("Workspace");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // Only demo businesses are open to everyone. A business LeziTech is still setting up needs the code and a reason like any other.
        boolean exempt = Boolean.TRUE.equals(found.get(0).get("is_demo"));
        Optional<Map<String, Object>> g = active(tenant);
        out.put("exempt", exempt);
        out.put("active", g.isPresent());
        out.put("expiresAt", g.map(x -> x.get("expires_at")).orElse(null));
        out.put("grantedBy", g.map(x -> x.get("granted_by_name")).orElse(null));
        out.put("requested", pendingRequest(tenant).isPresent());
        out.put("looking", looking(tenant).size());
        return out;
    }

    public static Timestamp expiry(Map<String, Object> grant) {
        return (Timestamp) grant.get("expires_at");
    }
}
