package com.lezitech.suite.property.portal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Is a tenant's portal token still good? (Account active, and no password change since it was issued.) */
@Component
public class RenterSessions {

    private final JdbcTemplate jdbc;

    public RenterSessions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean valid(UUID accountId, UUID tenantId, int tokenVersion) {
        List<Integer> tv = jdbc.queryForList("SELECT token_version FROM renter_accounts WHERE id = ? AND tenant_id = ? AND active",
                Integer.class, accountId, tenantId);
        return tv.size() == 1 && tv.get(0) == tokenVersion;
    }
}
