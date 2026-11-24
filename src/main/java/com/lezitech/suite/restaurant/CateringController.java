package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Catering orders: the staff list and actions, the customer's read-only summary link, and the event-day kitchen job. */
@RestController
public class CateringController {

    private static final Logger log = LoggerFactory.getLogger(CateringController.class);

    private final Catering catering;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CateringController(Catering catering, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.catering = catering;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    @GetMapping("/api/catering")
    @Transactional
    public Map<String, Object> list() {
        TenantContext.requireRole("MANAGER", "CASHIER", "KITCHEN", "ACCOUNTANT");
        return catering.list();
    }

    @PostMapping("/api/catering")
    @Transactional
    public Map<String, Object> place(@RequestBody Map<String, Object> raw) {
        return catering.place(new Body(raw));
    }

    @PostMapping("/api/catering/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return catering.update(id, new Body(raw));
    }

    @PostMapping("/api/catering/{id}/status")
    @Transactional
    public Map<String, Object> status(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return catering.setStatus(id, new Body(raw).str("status", ""));
    }

    @PostMapping("/api/catering/{id}/pay")
    @Transactional
    public Map<String, Object> pay(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return catering.pay(id, new Body(raw));
    }

    @PostMapping("/api/catering/{id}/cancel")
    @Transactional
    public Map<String, Object> cancel(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        return catering.cancel(id, raw == null ? null : new Body(raw).str("reason", null));
    }

    /** The order summary a customer opens from the link they were sent. */
    @GetMapping("/api/public/catering/{slug}/o/{token}")
    public Map<String, Object> mine(@PathVariable String slug, @PathVariable String token) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, phone, brand_color, logo_url, status FROM tenants WHERE slug = ?", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Business");
        }
        Map<String, Object> b = rows.get(0);
        Map<String, Object> biz = Map.of("name", b.get("name"), "phone", b.get("phone") == null ? "" : b.get("phone"),
                "brandColor", b.get("brand_color") == null ? "" : b.get("brand_color"), "logoUrl", b.get("logo_url") == null ? "" : b.get("logo_url"));
        Map<String, Object> order = TenantContext.runAs((UUID) b.get("id"), null,
                () -> tx.execute(x -> catering.publicView(catering.idOfToken(token))));
        return Map.of("business", biz, "order", order);
    }

    /** Every 10 minutes: the dishes of today's catering orders reach the kitchen screen. */
    @Scheduled(fixedDelayString = "${suite.catering.every-ms:600000}", initialDelay = 120_000)
    public void jobs() {
        List<UUID> tenants = jdbc.queryForList("""
                SELECT id FROM tenants WHERE status <> 'SUSPENDED' AND sector IN ('restaurant', 'hotel')
                  AND coalesce(settings->'features'->>'catering', 'false') = 'true'
                  AND coalesce(settings->'features'->>'kitchen', 'false') = 'true'""", UUID.class);
        for (UUID t : tenants) {
            try {
                TenantContext.runAs(t, null, () -> tx.execute(s -> {
                    catering.releaseToday();
                    return null;
                }));
            } catch (RuntimeException e) {
                log.warn("Catering kitchen job for {}: {}", t, e.toString());
            }
        }
    }
}
