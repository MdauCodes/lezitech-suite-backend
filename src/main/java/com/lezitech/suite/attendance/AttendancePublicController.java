package com.lezitech.suite.attendance;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The shop screen link: no sign-in, and all it can ever show is the branch's current code. A manager can revoke it. */
@RestController
public class AttendancePublicController {

    private final AttendanceService service;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AttendancePublicController(AttendanceService service, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.service = service;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    @GetMapping("/api/public/attendance/screen/{token}")
    public ResponseEntity<Map<String, Object>> screen(@PathVariable String token) {
        int dot = token.lastIndexOf('.');
        if (dot < 1 || token.length() > 120) {
            throw ApiException.notFound("Screen link");
        }
        List<UUID> tenant = jdbc.queryForList("SELECT id FROM tenants WHERE slug = ? AND status <> 'SUSPENDED'", UUID.class, token.substring(0, dot));
        if (tenant.isEmpty()) {
            throw ApiException.notFound("Screen link");
        }
        Map<String, Object> out = TenantContext.runAs(tenant.get(0), null, () -> tx.execute(s -> service.screenFor(token)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
