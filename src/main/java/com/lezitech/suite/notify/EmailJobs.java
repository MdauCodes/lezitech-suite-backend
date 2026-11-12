package com.lezitech.suite.notify;

import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Receipts, invoices and quotations are emailed in the background: pressing Email just queues the job, so the till
 * never waits on the email service. A job is tried three times; if it still fails it becomes a problem the business
 * sees (Problems page and a message on screen) and LeziTech sees in the control panel.
 */
@Service
public class EmailJobs {

    private static final Logger log = LoggerFactory.getLogger(EmailJobs.class);
    static final int TRIES = 3;

    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final ReceiptEmail receipts;
    private final TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Value("${suite.email.retry-seconds:20}")
    private int retrySeconds;

    public EmailJobs(JdbcTemplate jdbc, Mailer mailer, ReceiptEmail receipts, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.receipts = receipts;
        this.tx = new TransactionTemplate(txm);
    }

    public UUID queue(UUID tenant, UUID orderId, String to, UUID by) {
        return queue(tenant, orderId, to, by, "RECEIPT");
    }

    /** kind: RECEIPT (receipt, invoice or quotation) or ETR (the business's own KRA receipt, attached). */
    public UUID queue(UUID tenant, UUID orderId, String to, UUID by, String kind) {
        return jdbc.queryForObject("INSERT INTO email_jobs (tenant_id, order_id, to_email, created_by, kind) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, orderId, to, by, kind);
    }

    @Scheduled(fixedDelayString = "${suite.email.jobs-every-ms:2000}", initialDelay = 2000)
    public void run() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                UPDATE email_jobs SET status = 'SENDING', attempts = attempts + 1
                WHERE id IN (SELECT id FROM email_jobs WHERE status = 'QUEUED' AND next_try_at <= now()
                             ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED)
                RETURNING id, tenant_id, order_id, to_email, attempts, created_by, kind""");
        for (Map<String, Object> j : due) {
            send(j);
        }
    }

    private void send(Map<String, Object> j) {
        UUID id = (UUID) j.get("id");
        UUID tenant = (UUID) j.get("tenant_id");
        UUID by = (UUID) j.get("created_by");
        String to = (String) j.get("to_email");
        int attempts = (Integer) j.get("attempts");
        UUID orderId = (UUID) j.get("order_id");
        boolean etr = "ETR".equals(j.get("kind"));
        String error;
        ReceiptEmail.Built built = null;
        try {
            if (etr) {
                ReceiptEmail.Etr e = TenantContext.runAs(tenant, by, () -> tx.execute(s -> receipts.buildEtr(orderId)));
                built = e.email();
                error = mailer.trySend(to, null, built.subject(), built.text(), built.html(), java.util.List.of(e.file()));
            } else {
                built = TenantContext.runAs(tenant, by, () -> tx.execute(s -> receipts.build(orderId)));
                error = mailer.trySend(to, null, built.subject(), built.text(), built.html());
            }
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        if (error == null) {
            jdbc.update("UPDATE email_jobs SET status = 'SENT', sent_at = now(), last_error = NULL WHERE id = ?", id);
            if (etr) {
                // orders is fenced by row-level security: record it inside the business's own transaction.
                TenantContext.runAs(tenant, by, () -> tx.execute(s -> jdbc.update(
                        "UPDATE orders SET etr_emailed_to = ?, etr_emailed_at = now() WHERE id = ? AND tenant_id = ?", to, orderId, tenant)));
            }
            if (built.customerId() != null) {
                jdbc.update("UPDATE customers SET email = ? WHERE id = ? AND tenant_id = ? AND (email IS NULL OR email = '')", to, built.customerId(), tenant);
            }
            return;
        }
        log.warn("Email job {} to {} failed (try {}): {}", id, to, attempts, error);
        if (attempts < TRIES) {
            jdbc.update("UPDATE email_jobs SET status = 'QUEUED', last_error = ?, next_try_at = now() + make_interval(secs => ?) WHERE id = ?",
                    error, retrySeconds * attempts, id);
            return;
        }
        jdbc.update("UPDATE email_jobs SET status = 'FAILED', last_error = ? WHERE id = ?", error, id);
        String what = built == null ? (etr ? "A KRA receipt (ETR)" : "A receipt") : built.subject().replace(" from " + built.businessName(), "");
        jdbc.update("INSERT INTO problems (tenant_id, kind, title, detail, ref_id) VALUES (?, 'EMAIL_FAILED', ?, ?, ?)",
                tenant, what + " didn't reach " + to, plain(error), id);
    }

    /** Owner-friendly wording for the usual email failures; the raw message stays in the job for LeziTech. */
    static String plain(String error) {
        String e = error.toLowerCase();
        if (e.contains("invalid") || e.contains("not valid") || e.contains("550") || e.contains("does not exist")) {
            return "The email address looks wrong or doesn't exist. Check it with the customer and try again, or send it by WhatsApp.";
        }
        if (e.contains("timeout") || e.contains("couldn't reach") || e.contains("connect")) {
            return "Our email service couldn't be reached. Try again in a few minutes; LeziTech has been told.";
        }
        return "The email couldn't be sent (" + (error.length() > 160 ? error.substring(0, 160) + "…" : error) + "). Try again, or send it by WhatsApp.";
    }

    /** Try a failed job again (from the Problems page). */
    public void retry(UUID jobId, UUID tenant) {
        jdbc.update("UPDATE email_jobs SET status = 'QUEUED', attempts = 0, next_try_at = now() WHERE id = ? AND tenant_id = ? AND status = 'FAILED'", jobId, tenant);
    }
}
