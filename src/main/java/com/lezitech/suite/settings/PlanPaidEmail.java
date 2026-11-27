package com.lezitech.suite.settings;

import com.lezitech.suite.growth.Referrals;
import com.lezitech.suite.notify.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The receipt LeziTech emails when a business pays for its plan (phase E): what was paid, the M-Pesa code, until when,
 * and the business's own invite link (no reward is promised in the receipt). Sent once the
 * payment is saved, so a failed email never undoes a payment.
 */
@Component
public class PlanPaidEmail {

    private static final Logger log = LoggerFactory.getLogger(PlanPaidEmail.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneId.of("Africa/Nairobi"));

    /** A plan was paid for (published by {@link PlanActivation}). */
    public record PlanPaid(UUID tenant, UUID requestId) {
    }

    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final Referrals referrals;
    private final String siteUrl;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlatformSettings platform;

    public PlanPaidEmail(JdbcTemplate jdbc, Mailer mailer, Referrals referrals,
                         @Value("${suite.site-url:https://lezitech.site/suite}") String siteUrl) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.referrals = referrals;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPaid(PlanPaid e) {
        try {
            send(e);
        } catch (RuntimeException ex) {
            log.warn("Plan receipt email for {} not sent: {}", e.tenant(), ex.toString());
        }
    }

    /** Builds and sends the receipt; returns the plain text (tests), or null when there's no one to send it to. */
    public String send(PlanPaid e) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.name AS business, t.sector, t.sub_sector, t.paid_until, t.is_demo, r.plan, r.billing_cycle, r.amount, r.mpesa_receipt,
                       (SELECT u.email FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS email,
                       (SELECT u.name FROM users u WHERE u.tenant_id = t.id AND u.role = 'OWNER' AND u.active ORDER BY u.created_at LIMIT 1) AS owner
                FROM tenants t JOIN subscription_requests r ON r.tenant_id = t.id WHERE t.id = ? AND r.id = ?""", e.tenant(), e.requestId());
        if (rows.isEmpty() || Boolean.TRUE.equals(rows.get(0).get("is_demo"))) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        String email = (String) r.get("email");
        if (email == null || email.isBlank()) {
            return null;
        }
        String owner = first((String) r.get("owner"));
        String plan = platform.planName((String) r.get("sector"), (String) r.get("sub_sector"), (String) r.get("plan"));
        String cycle = "ANNUAL".equals(r.get("billing_cycle")) ? "a year" : "a month";
        String amount = String.format("%,d", ((BigDecimal) r.get("amount")).longValue());
        String until = r.get("paid_until") == null ? "" : DAY.format(((Timestamp) r.get("paid_until")).toInstant());
        String code = (String) r.get("mpesa_receipt");
        String invite = siteUrl + "?ref=" + referrals.codeFor(e.tenant());
        String business = (String) r.get("business");

        String text = "Hi " + owner + ",\n\nThank you. " + business + " is on the Busynez " + plan + " plan (" + cycle + ").\n\n"
                + "Paid: KSh " + amount + (code == null ? "" : "\nM-Pesa code: " + code) + (until.isEmpty() ? "" : "\nRunning until: " + until) + "\n\n"
                + "Know another business that could use Busynez? Share your invite link.\nYour link: " + invite + "\n\n"
                + (mailer.support().isBlank() ? "" : "Questions? WhatsApp us on " + mailer.support() + ".\n\n") + "Thank you,\nLeziTech";
        String html = "<p>Hi " + esc(owner) + ",</p><p>Thank you. <b>" + esc(business) + "</b> is on the Busynez <b>" + esc(plan) + "</b> plan (" + cycle + ").</p>"
                + "<table role=\"presentation\" cellpadding=\"6\" cellspacing=\"0\" border=\"0\" style=\"font:15px Arial,Helvetica,sans-serif\">"
                + "<tr><td style=\"color:#6b7280\">Paid</td><td><b>KSh " + amount + "</b></td></tr>"
                + (code == null ? "" : "<tr><td style=\"color:#6b7280\">M-Pesa code</td><td>" + esc(code) + "</td></tr>")
                + (until.isEmpty() ? "" : "<tr><td style=\"color:#6b7280\">Running until</td><td>" + until + "</td></tr>") + "</table>"
                + "<p style=\"margin-top:20px\"><b>Know another business that could use Busynez?</b> Share your invite link.</p>"
                + "<p><a href=\"" + invite + "\">" + esc(invite) + "</a></p>"
                + (mailer.support().isBlank() ? "" : "<p>Questions? WhatsApp us on " + esc(mailer.support()) + ".</p>") + "<p>Thank you,<br>LeziTech</p>";
        String status = mailer.send(email, owner, "Thank you: " + business + " is on Busynez " + plan, text, mailer.layout(html));
        if (!"SENT".equals(status) && !"OFF".equals(status)) {
            log.warn("Plan receipt email to {} {}", email, status);
        }
        return text;
    }

    private static String first(String name) {
        return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
