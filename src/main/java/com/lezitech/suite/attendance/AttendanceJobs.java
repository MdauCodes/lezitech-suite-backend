package com.lezitech.suite.attendance;

import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** Every 10 minutes: close forgotten clock-outs. Every hour: delete selfies older than 60 days. */
@Service
public class AttendanceJobs {

    private static final Logger log = LoggerFactory.getLogger(AttendanceJobs.class);

    private final AttendanceService service;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AttendanceJobs(AttendanceService service, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.service = service;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    private List<UUID> tenants() {
        return jdbc.queryForList("SELECT id FROM tenants WHERE status <> 'SUSPENDED' AND settings->>'attendance' = 'true'", UUID.class);
    }

    @Scheduled(fixedDelayString = "${suite.attendance.close-every-ms:600000}", initialDelay = 180_000)
    public void closeForgotten() {
        closeAll();
    }

    @Scheduled(fixedDelayString = "${suite.attendance.purge-every-ms:3600000}", initialDelay = 240_000)
    public void purge() {
        purgeAll();
    }

    public int closeAll() {
        int n = 0;
        for (UUID t : tenants()) {
            try {
                n += TenantContext.runAs(t, null, () -> tx.execute(s -> service.closeForgotten()));
            } catch (RuntimeException e) {
                log.warn("Closing forgotten clock-outs for {}: {}", t, e.toString());
            }
        }
        return n;
    }

    public int purgeAll() {
        int n = 0;
        for (UUID t : tenants()) {
            try {
                n += TenantContext.runAs(t, null, () -> tx.execute(s -> service.purgeSelfies()));
            } catch (RuntimeException e) {
                log.warn("Deleting old selfies for {}: {}", t, e.toString());
            }
        }
        return n;
    }
}
