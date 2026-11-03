package com.lezitech.suite.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Control-panel plumbing: first superadmin, demo staff logins, audit trail, per-workspace usage. */
@Service
@Order(1)
public class AdminService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    /** Local-only staff accounts behind the one-click buttons on the control-panel sign-in. */
    public static final List<String[]> DEMO_STAFF = List.of(
            new String[]{"SUPERADMIN", "Superadmin (you)", "Mdau (superadmin)"},
            new String[]{"ADMIN", "Operations admin", "Ops admin"},
            new String[]{"SUPPORT", "Customer support", "Support agent"},
            new String[]{"SALES", "Sales & onboarding", "Sales lead"},
            new String[]{"DEVELOPER", "Developer", "Developer"});

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final ObjectMapper json;
    private final String bootstrapEmail;
    private final String bootstrapPassword;
    private final boolean demoLogins;

    public AdminService(JdbcTemplate jdbc, PasswordEncoder encoder, ObjectMapper json,
                        @Value("${suite.admin.email:}") String bootstrapEmail,
                        @Value("${suite.admin.password:}") String bootstrapPassword,
                        @Value("${suite.admin.demo-logins:false}") boolean demoLogins) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.json = json;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
        // Never on against a real database, whatever the flag says.
        this.demoLogins = demoLogins && System.getenv("DATABASE_URL") == null;
    }

    public boolean demoLogins() {
        return demoLogins;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!bootstrapEmail.isBlank() && !bootstrapPassword.isBlank()) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM platform_admins WHERE email = ?", Integer.class, bootstrapEmail.toLowerCase());
            if (n != null && n == 0) {
                jdbc.update("INSERT INTO platform_admins (email, name, password_hash, role) VALUES (?, ?, ?, 'SUPERADMIN')",
                        bootstrapEmail.toLowerCase(), "Superadmin", encoder.encode(bootstrapPassword));
                log.info("Created superadmin {}", bootstrapEmail);
            }
        }
        if (demoLogins) {
            for (String[] s : DEMO_STAFF) {
                String email = s[0].toLowerCase() + "@lezitech.test";
                jdbc.update("""
                        INSERT INTO platform_admins (email, name, password_hash, role) VALUES (?, ?, ?, ?)
                        ON CONFLICT (email) DO NOTHING""", email, s[2], encoder.encode(UUID.randomUUID().toString()), s[0]);
            }
        }
    }

    public void audit(String action, UUID tenantId, Map<String, Object> detail) {
        AdminContext.Admin a = AdminContext.admin();
        try {
            jdbc.update("INSERT INTO platform_audit (admin_id, admin_name, action, tenant_id, detail) VALUES (?, ?, ?, ?, cast(? as jsonb))",
                    a.id(), a.name(), action, tenantId, json.writeValueAsString(detail == null ? Map.of() : detail));
        } catch (Exception e) {
            log.warn("Audit write failed for {}: {}", action, e.getMessage());
        }
    }

    /**
     * Usage inside one workspace. Business tables are fenced by RLS, so this runs as that tenant
     * (callers wrap it in TenantContext.runAs) and only ever reads counts.
     */
    @Service
    public static class UsageReader {

        private final Db db;

        public UsageReader(Db db) {
            this.db = db;
        }

        @Transactional(readOnly = true)
        public Map<String, Object> usage() {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("orders", db.value("SELECT count(*) FROM orders WHERE status <> 'VOID'", Long.class));
            u.put("ordersLast7Days", db.value("SELECT count(*) FROM orders WHERE status <> 'VOID' AND created_at > now() - interval '7 days'", Long.class));
            u.put("sales30Days", db.value("""
                    SELECT coalesce(sum(total), 0) FROM orders WHERE status IN ('PAID','CREDIT') AND kind <> 'QUOTE'
                    AND closed_at > now() - interval '30 days'""", java.math.BigDecimal.class));
            u.put("lastOrderAt", db.value("SELECT max(created_at)::text FROM orders", String.class));
            u.put("items", db.value("SELECT count(*) FROM items WHERE active", Long.class));
            u.put("customers", db.value("SELECT count(*) FROM customers", Long.class));
            u.put("deals", db.value("SELECT count(*) FROM deals", Long.class));
            u.put("stays", db.value("SELECT count(*) FROM stays", Long.class));
            u.put("trips", db.value("SELECT count(*) FROM trips", Long.class));
            u.put("leases", db.value("SELECT count(*) FROM leases WHERE status = 'ACTIVE'", Long.class));
            u.put("locations", db.value("SELECT count(*) FROM locations", Long.class));
            // Did they replace the sample data with their own? A strong sign the trial will convert.
            u.put("ownItems", db.value("""
                    SELECT count(*) FROM items i WHERE i.created_at > (SELECT min(created_at) + interval '2 minutes' FROM items)""", Long.class));
            u.put("dailyOrders", db.list("""
                    SELECT to_char(d, 'MM-DD') AS day, count(o.id) AS orders
                    FROM generate_series(current_date - 13, current_date, interval '1 day') d
                    LEFT JOIN orders o ON o.created_at::date = d::date AND o.status <> 'VOID'
                    GROUP BY d ORDER BY d"""));
            return u;
        }
    }
}
