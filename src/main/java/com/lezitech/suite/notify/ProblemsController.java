package com.lezitech.suite.notify;

import com.lezitech.suite.admin.AdminContext;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Things that went wrong in the background and need a person: the business sees its own (Problems page, a message
 * on screen); LeziTech sees everyone's in the control panel. Also: how a queued email is getting on.
 */
@RestController
public class ProblemsController {

    private final JdbcTemplate jdbc;
    private final EmailJobs jobs;

    public ProblemsController(JdbcTemplate jdbc, EmailJobs jobs) {
        this.jdbc = jdbc;
        this.jobs = jobs;
    }

    /** The till asks how its email is going, to show "sent" or the problem. */
    @GetMapping("/api/email-jobs/{id}")
    public Map<String, Object> job(@PathVariable UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, to_email AS "to", status, attempts FROM email_jobs WHERE id = ? AND tenant_id = ?""", id, TenantContext.tenantId());
        if (rows.isEmpty()) {
            throw ApiException.notFound("Email");
        }
        Map<String, Object> j = new java.util.LinkedHashMap<>(rows.get(0));
        if ("FAILED".equals(j.get("status"))) {
            j.put("message", jdbc.queryForList("SELECT detail FROM problems WHERE ref_id = ? AND tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                    String.class, id, TenantContext.tenantId()).stream().findFirst().orElse("The email couldn't be sent."));
        }
        return j;
    }

    @GetMapping("/api/problems")
    public Map<String, Object> list(@RequestParam(defaultValue = "OPEN") String status) {
        UUID t = TenantContext.tenantId();
        return Map.of(
                "open", jdbc.queryForObject("SELECT count(*) FROM problems WHERE tenant_id = ? AND status = 'OPEN'", Long.class, t),
                "problems", jdbc.queryForList("""
                        SELECT id, kind, title, detail, status, created_at AS "createdAt", resolved_at AS "resolvedAt", resolved_by AS "resolvedBy"
                        FROM problems WHERE tenant_id = ? AND (? = 'ALL' OR status = ?) ORDER BY created_at DESC LIMIT 100""", t, status, status));
    }

    @PostMapping("/api/problems/{id}/retry")
    public Map<String, Object> retry(@PathVariable UUID id) {
        Map<String, Object> p = one(id);
        if ("EMAIL_FAILED".equals(p.get("kind")) && p.get("ref_id") != null) {
            jobs.retry((UUID) p.get("ref_id"), TenantContext.tenantId());
        }
        resolve(id, "Tried again");
        return Map.of("ok", true, "jobId", String.valueOf(p.get("ref_id")));
    }

    @PostMapping("/api/problems/{id}/resolve")
    public Map<String, Object> resolveEndpoint(@PathVariable UUID id) {
        one(id);
        resolve(id, null);
        return Map.of("ok", true);
    }

    private Map<String, Object> one(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM problems WHERE id = ? AND tenant_id = ?", id, TenantContext.tenantId());
        if (rows.isEmpty()) {
            throw ApiException.notFound("Problem");
        }
        return rows.get(0);
    }

    private void resolve(UUID id, String how) {
        jdbc.update("UPDATE problems SET status = 'RESOLVED', resolved_at = now(), resolved_by = ? WHERE id = ? AND tenant_id = ?",
                TenantContext.caller().name() + (how == null ? "" : " (" + how.toLowerCase() + ")"), id, TenantContext.tenantId());
    }

    // ---------- LeziTech control panel ----------

    @GetMapping("/api/admin/problems")
    public List<Map<String, Object>> all(@RequestParam(defaultValue = "OPEN") String status) {
        AdminContext.require("read");
        return jdbc.queryForList("""
                SELECT p.id, p.tenant_id AS "tenantId", t.name AS "tenantName", p.kind, p.title, p.detail, p.status,
                       p.created_at AS "createdAt", p.resolved_by AS "resolvedBy", j.last_error AS "technical", j.attempts
                FROM problems p JOIN tenants t ON t.id = p.tenant_id LEFT JOIN email_jobs j ON j.id = p.ref_id
                WHERE (? = 'ALL' OR p.status = ?) ORDER BY p.created_at DESC LIMIT 300""", status, status);
    }

    @PostMapping("/api/admin/problems/{id}/resolve")
    public Map<String, Object> adminResolve(@PathVariable UUID id) {
        AdminContext.require("trials");
        jdbc.update("UPDATE problems SET status = 'RESOLVED', resolved_at = now(), resolved_by = ? WHERE id = ?",
                "LeziTech (" + AdminContext.admin().name() + ")", id);
        return Map.of("ok", true);
    }
}
