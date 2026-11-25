package com.lezitech.suite.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Server-side idle limit for the control panel. Each person's last-seen time is kept in the database (written at most
 * once a minute), so it holds across restarts and several servers. After 60 minutes with no request the token is
 * refused and the person signs in again; the web signs out on its own too, but nothing relies on the web.
 */
@Component
public class AdminActivity {

    public static final int IDLE_MINUTES = 60;
    public static final String IDLE_CODE = "IDLE_SIGNED_OUT";
    public static final String IDLE_MESSAGE = "Signed out after an hour without activity";

    private final JdbcTemplate jdbc;

    public AdminActivity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when this person is still within the limit (and records the visit); false when they have been idle too long. */
    public boolean touch(UUID adminId) {
        Boolean fresh = jdbc.query("""
                SELECT last_seen_at IS NULL OR last_seen_at > now() - make_interval(mins => ?) FROM platform_admins WHERE id = ?""",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, IDLE_MINUTES, adminId);
        if (!Boolean.TRUE.equals(fresh)) {
            return false;
        }
        jdbc.update("UPDATE platform_admins SET last_seen_at = now() WHERE id = ? AND (last_seen_at IS NULL OR last_seen_at < now() - interval '1 minute')", adminId);
        return true;
    }
}
