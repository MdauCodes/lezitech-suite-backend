package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.Body;
import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** R8: the owner's night summary (page, PDF, resend, settings), the menu report, and the job that sends it. */
@RestController
public class NightSummaryController {

    private static final Logger log = LoggerFactory.getLogger(NightSummaryController.class);

    private final NightSummary night;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public NightSummaryController(NightSummary night, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.night = night;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    private static void owners() {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
    }

    /** Past summaries, today's business day, and the settings. */
    @GetMapping("/api/night")
    @Transactional
    public Map<String, Object> list() {
        owners();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", NightSummary.businessDay(Instant.now()).toString());
        out.put("recent", night.recent());
        out.put("settings", night.rulesView(night.rules(TenantContext.tenantId())));
        return out;
    }

    /** One day: as sent, or worked out now for a day not sent yet (e.g. today so far). */
    @GetMapping("/api/night/{day}")
    @Transactional
    public Map<String, Object> day(@PathVariable LocalDate day) {
        owners();
        Map<String, Object> s = night.stored(day);
        if (s == null) {
            s = new LinkedHashMap<>(night.build(day));
            s.put("notSentYet", true);
        }
        return s;
    }

    @GetMapping("/api/night/{day}/pdf")
    @Transactional
    public ResponseEntity<byte[]> pdf(@PathVariable LocalDate day) {
        owners();
        Map<String, Object> s = night.stored(day);
        byte[] bytes = NightPdf.render(s != null ? s : night.build(day));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"day-" + day + ".pdf\"").body(bytes);
    }

    /** Send (or send again) a day's summary now. */
    @PostMapping("/api/night/{day}/send")
    @Transactional
    public Map<String, Object> send(@PathVariable LocalDate day) {
        TenantContext.requireRole("MANAGER");
        return night.send(day, "ON_REQUEST");
    }

    @PostMapping("/api/night/settings")
    @Transactional
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> raw) {
        night.saveRules(new Body(raw));
        return night.rulesView(night.rules(TenantContext.tenantId()));
    }

    /** How each dish is doing over any dates (default the last 4 weeks). */
    @GetMapping("/api/menu-report")
    @Transactional
    public Map<String, Object> menuReport(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        owners();
        LocalDate t = to == null ? NightSummary.businessDay(Instant.now()) : to;
        return night.menuReport(from == null ? t.minusDays(27) : from, t);
    }

    /** Every 5 minutes: send each restaurant's summary when its last till closes, or at its latest time. */
    @Scheduled(fixedDelayString = "${suite.night.every-ms:300000}", initialDelay = 120_000)
    public void job() {
        List<UUID> tenants = jdbc.queryForList("SELECT id FROM tenants WHERE status <> 'SUSPENDED' AND sector IN ('restaurant', 'hotel')", UUID.class);
        Instant now = Instant.now();
        for (UUID t : tenants) {
            try {
                TenantContext.runAs(t, null, () -> tx.execute(s -> night.maybeSend(now)));
            } catch (RuntimeException e) {
                log.warn("Night summary for {}: {}", t, e.toString());
            }
        }
    }
}
