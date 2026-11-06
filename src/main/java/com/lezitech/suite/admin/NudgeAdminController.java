package com.lezitech.suite.admin;

import com.lezitech.suite.notify.TrialNudgeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Control panel: trial reminders, with a ready WhatsApp message for each one. */
@RestController
@RequestMapping("/api/admin/nudges")
public class NudgeAdminController {

    private final JdbcTemplate jdbc;
    private final AdminService admin;
    private final TrialNudgeService nudges;

    public NudgeAdminController(JdbcTemplate jdbc, AdminService admin, TrialNudgeService nudges) {
        this.jdbc = jdbc;
        this.admin = admin;
        this.nudges = nudges;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "TODO") String show) {
        AdminContext.require("read");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT n.id, n.tenant_id AS "tenantId", t.name AS "tenantName", t.sector, n.kind, n.email_to AS "emailTo",
                       n.email_status AS "emailStatus", n.email_error AS "emailError", n.whatsapp_to AS "whatsappTo",
                       n.whatsapp_text AS "whatsappText", n.whatsapp_done_at AS "whatsappDoneAt", n.whatsapp_done_by AS "whatsappDoneBy",
                       n.created_at AS "createdAt", t.status AS "tenantStatus", t.trial_ends_at AS "trialEndsAt"
                FROM trial_nudges n JOIN tenants t ON t.id = n.tenant_id
                WHERE n.email_status <> 'SKIPPED'
                  AND (? = 'ALL' OR (n.whatsapp_done_at IS NULL AND n.whatsapp_to IS NOT NULL AND t.status = 'TRIAL'))
                ORDER BY n.created_at DESC LIMIT 200""", show);
        return Map.of("nudges", rows, "email", nudges.sender() == null ? "off" : nudges.sender().name());
    }

    /** Someone at Lezitech sent the WhatsApp message (by hand, from their phone). */
    @PostMapping("/{id}/whatsapp-sent")
    public Map<String, Object> whatsappSent(@PathVariable UUID id) {
        AdminContext.require("trials");
        UUID tenant = jdbc.queryForObject("SELECT tenant_id FROM trial_nudges WHERE id = ?", UUID.class, id);
        jdbc.update("UPDATE trial_nudges SET whatsapp_done_at = now(), whatsapp_done_by = ? WHERE id = ? AND whatsapp_done_at IS NULL",
                AdminContext.admin().name(), id);
        admin.audit("trial.whatsapp_reminder", tenant, Map.of("nudge", id.toString()));
        return Map.of("ok", true);
    }

    /** Run the reminder check now instead of waiting for the hourly run. */
    @PostMapping("/run")
    public Map<String, Object> runNow() {
        AdminContext.require("trials");
        return Map.of("emailed", nudges.run());
    }
}
