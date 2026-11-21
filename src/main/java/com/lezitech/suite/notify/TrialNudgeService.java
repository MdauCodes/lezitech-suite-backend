package com.lezitech.suite.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reminds trial owners before the free days end: 3 days before (LEFT3), the day before (LEFT1) and on the last day
 * (DAY7, also sent if the trial has just ended), in plain words, with what their own setup costs.
 * Each reminder goes out once per workspace; a later one marks the earlier ones it replaces SKIPPED. Email when a provider is set up; a WhatsApp message is
 * always prepared for the LeziTech team to send from the control panel (Trial reminders).
 */
@Service
public class TrialNudgeService {

    private static final Logger log = LoggerFactory.getLogger(TrialNudgeService.class);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM").withZone(NAIROBI);

    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final String appUrl;
    private final String support;

    private final com.lezitech.suite.platform.PlanFeatures planFeatures;

    public TrialNudgeService(JdbcTemplate jdbc, Mailer mailer, com.lezitech.suite.platform.PlanFeatures planFeatures) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.planFeatures = planFeatures;
        this.appUrl = mailer.appUrl();
        this.support = mailer.support();
    }

    /** For tests: swap the email service. */
    public void useSender(EmailSender sender) {
        mailer.useSender(sender);
    }

    public EmailSender sender() {
        return mailer.sender();
    }

    @Scheduled(fixedDelayString = "${suite.nudges.every-ms:3600000}", initialDelay = 60000)
    public int run() {
        int sent = 0;
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT t.id, t.name, t.phone, t.trial_ends_at,
                       (SELECT u.email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS email,
                       (SELECT u.name FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS owner
                FROM tenants t
                WHERE t.status = 'TRIAL' AND NOT t.is_demo AND NOT t.in_setup
                  AND t.trial_ends_at <= now() + interval '4 days' AND t.trial_ends_at > now() - interval '1 day'""");
        java.time.LocalDate today = java.time.LocalDate.now(NAIROBI);
        for (Map<String, Object> t : due) {
            Instant ends = ((Timestamp) t.get("trial_ends_at")).toInstant();
            // Whole days left by the calendar in Kenya: 2-3 -> "in 3 days", 1 -> "tomorrow", 0 or ended -> "last day".
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, ends.atZone(NAIROBI).toLocalDate());
            String kind = daysLeft <= 0 ? "DAY7" : daysLeft == 1 ? "LEFT1" : daysLeft <= 3 ? "LEFT3" : null;
            if (kind != null && nudge(t, kind, ends, daysLeft)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean nudge(Map<String, Object> t, String kind, Instant ends, long daysLeft) {
        UUID tenant = (UUID) t.get("id");
        String owner = first((String) t.get("owner"));
        String business = (String) t.get("name");
        boolean last = "DAY7".equals(kind);
        boolean ended = ends.isBefore(Instant.now());
        String when = ended ? "have ended" : last ? "end today" : daysLeft == 1 ? "end tomorrow" : "end on " + DAY.format(ends);
        String subject = ended ? "Your Busynez free days have ended: your records are safe"
                : last ? "Your Busynez free days end today" : daysLeft == 1 ? "Your Busynez free days end tomorrow"
                : "Your Busynez free days end in " + daysLeft + " days";
        String link = appUrl + "/billing";
        // What keeps their own setup running: the plan that has the tools they use, and its price.
        String cost = "";
        try {
            Map<String, Object> plan = planFeatures.fittingPlan(tenant);
            long monthly = ((Number) plan.get("monthly")).longValue();
            cost = "Everything you use is on the " + plan.get("name") + " plan: KSh " + String.format("%,d", monthly) + " a month, or KSh "
                    + String.format("%,d", monthly * 10) + " for a year (2 months free).";
        } catch (RuntimeException e) {
            log.debug("No plan price for the reminder to {}: {}", tenant, e.toString());
        }
        String safe = "Everything you have entered stays safe" + (ended ? ", and you can still see it." : ".");
        String text = "Hi " + owner + ",\n\nThe free days for " + business + " " + when + ". " + safe + "\n\n"
                + (cost.isEmpty() ? "" : cost + "\n\n")
                + "To keep selling without a break, open " + link + " and pay by M-Pesa: a prompt comes to your phone and Busynez carries on straight away.\n\n"
                + (support.isBlank() ? "" : "Questions? WhatsApp us on " + support + ".\n\n") + "Thank you,\nLeziTech";
        String html = "<p>Hi " + esc(owner) + ",</p><p>The free days for <b>" + esc(business) + "</b> " + when + ". " + safe + "</p>"
                + (cost.isEmpty() ? "" : "<p>" + esc(cost) + "</p>")
                + "<p>To keep selling without a break, <a href=\"" + link + "\">pay by M-Pesa</a>: a prompt comes to your phone and Busynez carries on straight away.</p>"
                + (support.isBlank() ? "" : "<p>Questions? WhatsApp us on " + esc(support) + ".</p>") + "<p>Thank you,<br>LeziTech</p>";
        String wa = "Hi " + owner + ", this is LeziTech. The free days for " + business + " " + when
                + ". Your records are safe. " + (cost.isEmpty() ? "" : cost + " ") + "To keep using it, choose a plan here: " + link
                + " (you can pay by M-Pesa). Any questions, just reply here.";
        String to = (String) t.get("email");
        // Claim the reminder first: if two servers run the job, only one sends it.
        int claimed = jdbc.update("""
                INSERT INTO trial_nudges (tenant_id, kind, email_to, email_status, whatsapp_to, whatsapp_text)
                VALUES (?, ?, ?, 'PENDING', ?, ?) ON CONFLICT (tenant_id, kind) DO NOTHING""",
                tenant, kind, to, t.get("phone"), wa);
        if (claimed == 0) {
            return false;
        }
        // A later reminder replaces earlier ones not sent yet (joined late, or the job was down): no late "3 days left".
        for (String earlier : "DAY7".equals(kind) ? new String[]{"LEFT3", "LEFT1"} : "LEFT1".equals(kind) ? new String[]{"LEFT3"} : new String[0]) {
            jdbc.update("INSERT INTO trial_nudges (tenant_id, kind, email_status) VALUES (?, ?, 'SKIPPED') ON CONFLICT DO NOTHING", tenant, earlier);
        }
        String status;
        String error = null;
        EmailSender email = mailer.sender();
        if (email == null) {
            status = "OFF";
        } else if (to == null || to.isBlank()) {
            status = "NO_EMAIL";
        } else {
            try {
                email.send(new EmailSender.Email(to, owner, subject, text, mailer.layout(html)));
                status = "SENT";
            } catch (RuntimeException e) {
                status = "FAILED";
                error = e.getMessage();
                log.warn("Trial reminder email to {} failed: {}", to, e.getMessage());
            }
        }
        jdbc.update("UPDATE trial_nudges SET email_status = ?, email_error = ? WHERE tenant_id = ? AND kind = ?", status, error, tenant, kind);
        return "SENT".equals(status);
    }

    private static String first(String name) {
        if (name == null || name.isBlank()) {
            return "there";
        }
        return name.trim().split("\\s+")[0];
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
