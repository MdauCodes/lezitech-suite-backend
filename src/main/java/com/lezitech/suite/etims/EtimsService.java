package com.lezitech.suite.etims;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gets a KRA-signed invoice for every completed sale (and a credit note for every refund).
 * It is tried straight away so the receipt can show the number; if KRA can't be reached the
 * sale still completes and the invoice waits in etims_queue, retried every minute.
 * Only workspaces with a KRA PIN in Settings are sent.
 */
@Service
public class EtimsService {

    private static final Logger log = LoggerFactory.getLogger(EtimsService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String mode;
    private EtimsClient client;

    public EtimsService(JdbcTemplate jdbc, PlatformTransactionManager txm, @Value("${suite.etims.mode:off}") String mode) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        if ("stub".equals(mode)) {
            this.client = new StubEtimsClient();
            this.mode = mode;
        } else {
            if (!"off".equals(mode)) {
                log.warn("ETIMS_MODE={} isn't available yet (only off or stub); eTIMS is off", mode);
            }
            this.mode = "off";
        }
    }

    public boolean enabled() {
        return client != null;
    }

    public String mode() {
        return mode;
    }

    /** For tests: swap in a client that fails or records calls. */
    public void useClient(EtimsClient c) {
        this.client = c;
    }

    /** Call inside the transaction that completes the sale or refund. */
    public void queue(UUID orderId, String kind) {
        if (!enabled() || kraPin(TenantContext.tenantId()) == null) {
            return;
        }
        if ("SALE".equals(kind)) {
            jdbc.update("UPDATE orders SET etims_status = 'PENDING' WHERE id = ?", orderId);
        }
        jdbc.update("INSERT INTO etims_queue (tenant_id, order_id, kind) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                TenantContext.tenantId(), orderId, kind);
        try {
            send(orderId, kind);
        } catch (RuntimeException e) {
            // The sale goes ahead; the invoice is retried in the background.
            note(orderId, kind, e);
        }
    }

    /** Retry whatever didn't reach KRA. Each row is handled as its own workspace. */
    @Scheduled(fixedDelayString = "${suite.etims.retry-ms:60000}", initialDelay = 30000)
    public int retryPending() {
        if (!enabled()) {
            return 0;
        }
        int done = 0;
        for (Map<String, Object> q : jdbc.queryForList("""
                SELECT tenant_id, order_id, kind FROM etims_queue WHERE next_try_at <= now() ORDER BY created_at LIMIT 50""")) {
            UUID tenant = (UUID) q.get("tenant_id");
            UUID order = (UUID) q.get("order_id");
            String kind = (String) q.get("kind");
            try {
                TenantContext.runAs(tenant, null, () -> tx.execute(s -> {
                    send(order, kind);
                    return null;
                }));
                done++;
            } catch (RuntimeException e) {
                note(order, kind, e);
            }
        }
        return done;
    }

    private void note(UUID orderId, String kind, RuntimeException e) {
        log.warn("eTIMS {} for order {} not signed yet: {}", kind, orderId, e.getMessage());
        jdbc.update("""
                UPDATE etims_queue SET attempts = attempts + 1, last_error = ?,
                       next_try_at = now() + least(interval '1 minute' * power(2, attempts), interval '1 hour')
                WHERE order_id = ? AND kind = ?""", e.getMessage(), orderId, kind);
    }

    private void send(UUID orderId, String kind) {
        UUID tenant = TenantContext.tenantId();
        String pin = kraPin(tenant);
        if (pin == null) {
            throw ApiException.bad("Add the KRA PIN in Settings to send invoices to KRA");
        }
        Map<String, Object> o = jdbc.queryForMap("""
                SELECT o.number, o.total, o.tax, o.closed_at, o.refunded_at, o.etims_invoice_no, c.name AS customer_name, c.kra_pin AS customer_pin,
                       (SELECT p.method FROM payments p WHERE p.order_id = o.id AND p.amount > 0 ORDER BY p.amount DESC LIMIT 1) AS method
                FROM orders o LEFT JOIN customers c ON c.id = o.customer_id WHERE o.id = ?""", orderId);
        boolean credit = "CREDIT_NOTE".equals(kind);
        if (credit && o.get("etims_invoice_no") == null) {
            throw new IllegalStateException("waiting for the sale's own invoice first");
        }
        List<EtimsClient.Line> lines = new ArrayList<>();
        for (Map<String, Object> l : jdbc.queryForList("SELECT name, qty, unit_price, tax_rate, line_total FROM order_lines WHERE order_id = ? ORDER BY created_at", orderId)) {
            BigDecimal rate = (BigDecimal) l.get("tax_rate");
            BigDecimal total = (BigDecimal) l.get("line_total");
            BigDecimal tax = total.multiply(rate).divide(rate.add(BigDecimal.valueOf(100)), 2, RoundingMode.HALF_UP);
            lines.add(new EtimsClient.Line((String) l.get("name"), (BigDecimal) l.get("qty"), (BigDecimal) l.get("unit_price"),
                    EtimsClient.taxBand(rate), total, tax));
        }
        Timestamp when = (Timestamp) (credit ? o.get("refunded_at") : o.get("closed_at"));
        EtimsClient.Invoice inv = new EtimsClient.Invoice(pin, "00", kind, (Integer) o.get("number"),
                credit ? (String) o.get("etims_invoice_no") : null, when == null ? java.time.Instant.now() : when.toInstant(),
                (String) o.get("customer_pin"), (String) o.get("customer_name"), (String) o.get("method"),
                lines, (BigDecimal) o.get("total"), (BigDecimal) o.get("tax"));
        EtimsClient.Signed s = client.sign(inv);
        if (credit) {
            jdbc.update("UPDATE orders SET etims_credit_no = ? WHERE id = ?", s.invoiceNo(), orderId);
        } else {
            jdbc.update("""
                    UPDATE orders SET etims_status = 'SIGNED', etims_invoice_no = ?, etims_signature = ?, etims_sdc_id = ?, etims_qr = ?,
                           etims_signed_at = now(), etims_test = ? WHERE id = ?""",
                    s.invoiceNo(), s.signature(), s.controlUnitId(), s.qr(), s.test(), orderId);
        }
        jdbc.update("DELETE FROM etims_queue WHERE order_id = ? AND kind = ?", orderId, kind);
    }

    private String kraPin(UUID tenant) {
        String pin = jdbc.queryForObject("SELECT settings->>'kraPin' FROM tenants WHERE id = ?", String.class, tenant);
        return pin == null || pin.isBlank() ? null : pin.trim().toUpperCase();
    }
}
