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
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.Offers offers;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlatformSettings platform;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.settings.PlanBilling billing;

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
        int sent = process(dueNow());
        try {
            runBills();
        } catch (RuntimeException e) {
            log.warn("Bill reminders failed: {}", e.toString());
        }
        return sent;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING = new java.util.concurrent.atomic.AtomicBoolean();

    /** Start the reminder check in the background and return at once with how many workspaces are being looked at. */
    public int runInBackground() {
        List<Map<String, Object>> due = dueNow();
        if (!due.isEmpty() && RUNNING.compareAndSet(false, true)) {
            Thread t = new Thread(() -> {
                try {
                    process(due);
                } catch (RuntimeException e) {
                    log.warn("Background reminder run failed: {}", e.toString());
                } finally {
                    RUNNING.set(false);
                }
            }, "trial-nudges");
            t.setDaemon(true);
            t.start();
        }
        return due.size();
    }

    private List<Map<String, Object>> dueNow() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT t.id, t.name, t.phone, t.trial_ends_at,
                       (SELECT u.email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS email,
                       (SELECT u.name FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS owner
                FROM tenants t
                WHERE t.status = 'TRIAL' AND NOT t.is_demo AND NOT t.in_setup
                  AND t.trial_ends_at <= now() + interval '4 days' AND t.trial_ends_at > now() - interval '1 day'""");
        return due;
    }

    private int process(List<Map<String, Object>> due) {
        int sent = 0;
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
            // What this business would be charged now (any plan offer or its own offer taken off), the normal price shown as "was".
            var ctx = offers.context(tenant);
            var month = offers.price(ctx, (String) plan.get("code"), "MONTHLY");
            var year = offers.price(ctx, (String) plan.get("code"), "ANNUAL");
            cost = "Everything you use is on the " + platform.planName(tenant, (String) plan.get("code")) + " plan: "
                    + (month.amount() < monthly ? "now KSh " + String.format("%,d", month.amount()) + " a month, was KSh " + String.format("%,d", monthly)
                    : "KSh " + String.format("%,d", monthly) + " a month")
                    + ", or " + (year.amount() < monthly * 10 ? "now KSh " + String.format("%,d", year.amount()) + " for a year, was KSh " + String.format("%,d", monthly * 10)
                    : "KSh " + String.format("%,d", monthly * 10) + " for a year (2 months free)") + ".";
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

    // ---------- reminders for paid plans: 3 days before the bill date, and on it ----------

    /**
     * Paid plans: a reminder 3 days before the bill date (BEFORE3) and one on the day (DAY, also sent once if the date has
     * just passed and the business is in its days of grace). Each goes out once per bill date: paying moves the date on,
     * so the next bill gets its own. Plain words, the amount and what it is made of.
     *
     * @return how many emails went out
     */
    public int runBills() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.name, t.phone, t.paid_until,
                       (SELECT u.email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS email,
                       (SELECT u.name FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS owner
                FROM tenants t
                WHERE t.status = 'ACTIVE' AND NOT t.is_demo AND NOT t.in_setup AND t.paid_until IS NOT NULL
                  AND t.paid_until <= now() + interval '4 days' AND t.paid_until > now() - interval '3 days'""");
        int sent = 0;
        java.time.LocalDate today = java.time.LocalDate.now(NAIROBI);
        for (Map<String, Object> t : rows) {
            Instant date = ((Timestamp) t.get("paid_until")).toInstant();
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, date.atZone(NAIROBI).toLocalDate());
            String kind = daysLeft <= 0 ? "DAY" : daysLeft <= 3 ? "BEFORE3" : null;
            if (kind != null && billReminder(t, kind, date, daysLeft)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean billReminder(Map<String, Object> t, String kind, Instant date, long daysLeft) {
        UUID tenant = (UUID) t.get("id");
        java.time.LocalDate billDay = date.atZone(NAIROBI).toLocalDate();
        Map<String, Object> bill = billing.nextBill(tenant, "MONTHLY");
        long total = ((Number) bill.get("total")).longValue();
        String owner = first((String) t.get("owner"));
        String business = (String) t.get("name");
        String link = appUrl + "/billing";
        String dateText = com.lezitech.suite.settings.PlanBilling.day(date);
        String graceText = com.lezitech.suite.settings.PlanBilling.day(date.plus(com.lezitech.suite.config.TrialGuard.GRACE_DAYS, java.time.temporal.ChronoUnit.DAYS));
        String amount = com.lezitech.suite.settings.PlanBilling.kes(total);
        StringBuilder lines = new StringBuilder();
        StringBuilder htmlLines = new StringBuilder();
        for (Object o : (List<?>) bill.get("lines")) {
            Map<?, ?> l = (Map<?, ?>) o;
            long a = ((Number) l.get("amount")).longValue();
            String label = String.valueOf(l.get("label"));
            String value = (a < 0 ? "minus " : "") + com.lezitech.suite.settings.PlanBilling.kes(Math.abs(a));
            lines.append("- ").append(label).append(": ").append(value).append("\n");
            htmlLines.append("<li>").append(esc(label)).append(": ").append(esc(value)).append("</li>");
        }
        String when = daysLeft < 0 ? "was on " + dateText : daysLeft == 0 ? "is today" : daysLeft == 1 ? "is tomorrow" : "is on " + dateText;
        String subject = daysLeft < 0 ? "Your Busynez bill date was " + dateText + ": " + amount
                : daysLeft == 0 ? "Your Busynez bill is today: " + amount
                : "Your Busynez bill on " + dateText + ": " + amount;
        String grace = "Busynez keeps working for " + com.lezitech.suite.config.TrialGuard.GRACE_DAYS + " days after the bill date, until "
                + graceText + ". After that it is view-only until the bill is paid: you can still look at everything and download your reports.";
        String pay = "To pay, open " + link + " and pay by M-Pesa: a prompt comes to your phone.";
        String text = "Hi " + owner + ",\n\nYour next Busynez bill for " + business + " " + when + ": " + amount + ".\n\nWhat it is made of:\n" + lines
                + "\n" + pay + "\n" + grace + "\n\n" + (support.isBlank() ? "" : "Questions? WhatsApp us on " + support + ".\n\n") + "Thank you,\nLeziTech";
        String html = "<p>Hi " + esc(owner) + ",</p><p>Your next Busynez bill for <b>" + esc(business) + "</b> " + when + ": <b>" + esc(amount) + "</b>.</p>"
                + "<p>What it is made of:</p><ul>" + htmlLines + "</ul><p>To pay, <a href=\"" + link + "\">open Billing</a> and pay by M-Pesa: a prompt comes to your phone.</p>"
                + "<p>" + esc(grace) + "</p>" + (support.isBlank() ? "" : "<p>Questions? WhatsApp us on " + esc(support) + ".</p>") + "<p>Thank you,<br>LeziTech</p>";
        String wa = "Hi " + owner + ", this is LeziTech. Your next Busynez bill for " + business + " " + when + ": " + amount + ". "
                + lines.toString().replace("\n", "; ") + "Pay by M-Pesa here: " + link + ". It keeps working until " + graceText + " after the bill date.";
        String to = (String) t.get("email");
        // Claim this reminder for this bill date first: if two servers run the job, or the job runs again, only one sends it.
        int claimed = jdbc.update("""
                INSERT INTO bill_reminders (tenant_id, bill_date, kind, amount, email_to, email_status, whatsapp_to, whatsapp_text)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?) ON CONFLICT (tenant_id, bill_date, kind) DO NOTHING""",
                tenant, java.sql.Date.valueOf(billDay), kind, total, to, t.get("phone"), wa);
        if (claimed == 0) {
            return false;
        }
        if ("DAY".equals(kind)) {
            jdbc.update("INSERT INTO bill_reminders (tenant_id, bill_date, kind, email_status) VALUES (?, ?, 'BEFORE3', 'SKIPPED') ON CONFLICT DO NOTHING",
                    tenant, java.sql.Date.valueOf(billDay));
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
                log.warn("Bill reminder email to {} failed: {}", to, e.getMessage());
            }
        }
        jdbc.update("UPDATE bill_reminders SET email_status = ?, email_error = ? WHERE tenant_id = ? AND bill_date = ? AND kind = ?",
                status, error, tenant, java.sql.Date.valueOf(billDay), kind);
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
