package com.lezitech.suite.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reminds trial owners before the trial ends: on day 5 ("2 days left") and day 7 ("ends today").
 * Each reminder goes out once per workspace. Email when a provider is set up; a WhatsApp message is
 * always prepared for the LeziTech team to send from the control panel (Trial reminders).
 */
@Service
public class TrialNudgeService {

    private static final Logger log = LoggerFactory.getLogger(TrialNudgeService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM").withZone(ZoneId.of("Africa/Nairobi"));

    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final String appUrl;
    private final String support;

    public TrialNudgeService(JdbcTemplate jdbc, Mailer mailer) {
        this.jdbc = jdbc;
        this.mailer = mailer;
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
                  AND t.trial_ends_at <= now() + interval '2 days' AND t.trial_ends_at > now() - interval '1 day'""");
        for (Map<String, Object> t : due) {
            Instant ends = ((Timestamp) t.get("trial_ends_at")).toInstant();
            String kind = Duration.between(Instant.now(), ends).compareTo(Duration.ofDays(1)) <= 0 ? "DAY7" : "DAY5";
            if (nudge(t, kind, ends)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean nudge(Map<String, Object> t, String kind, Instant ends) {
        UUID tenant = (UUID) t.get("id");
        String owner = first((String) t.get("owner"));
        String business = (String) t.get("name");
        boolean last = "DAY7".equals(kind);
        String when = ends.isBefore(Instant.now()) ? "has ended" : last ? "ends today" : "ends on " + DAY.format(ends);
        String subject = last ? "Your BizNez free trial " + (ends.isBefore(Instant.now()) ? "has ended" : "ends today")
                : "Your BizNez free trial ends in 2 days";
        String link = appUrl + "/billing";
        String text = "Hi " + owner + ",\n\nThe free trial for " + business + " " + when + ". "
                + "Everything you have entered stays safe.\n\n"
                + "To keep selling without a break, choose a plan here: " + link + "\nYou can pay by M-Pesa on that page and the plan switches on straight away.\n\n"
                + (support.isBlank() ? "" : "Questions? WhatsApp us on " + support + ".\n\n") + "Thank you,\nLeziTech";
        String html = "<p>Hi " + esc(owner) + ",</p><p>The free trial for <b>" + esc(business) + "</b> " + when
                + ". Everything you have entered stays safe.</p><p>To keep selling without a break, <a href=\"" + link
                + "\">choose a plan</a>. You can pay by M-Pesa on that page and the plan switches on straight away.</p>"
                + (support.isBlank() ? "" : "<p>Questions? WhatsApp us on " + esc(support) + ".</p>") + "<p>Thank you,<br>LeziTech</p>";
        String wa = "Hi " + owner + ", this is LeziTech. The free trial for " + business + " " + when
                + ". Your data is safe. To keep using it, choose a plan here: " + link + " (you can pay by M-Pesa). Any questions, just reply here.";
        String to = (String) t.get("email");
        // Claim the reminder first: if two servers run the job, only one sends it.
        int claimed = jdbc.update("""
                INSERT INTO trial_nudges (tenant_id, kind, email_to, email_status, whatsapp_to, whatsapp_text)
                VALUES (?, ?, ?, 'PENDING', ?, ?) ON CONFLICT (tenant_id, kind) DO NOTHING""",
                tenant, kind, to, t.get("phone"), wa);
        if (claimed == 0) {
            return false;
        }
        if (last) {
            // Day 7 says it all; don't send a late "2 days left" afterwards.
            jdbc.update("""
                    INSERT INTO trial_nudges (tenant_id, kind, email_status) VALUES (?, 'DAY5', 'SKIPPED') ON CONFLICT DO NOTHING""", tenant);
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
