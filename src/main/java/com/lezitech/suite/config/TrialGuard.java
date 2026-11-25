package com.lezitech.suite.config;

import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * Once a trial (or paid period) runs out the workspace turns read-only: people can still
 * look at and export their data, but every change answers 402 until they subscribe.
 */
@Configuration
public class TrialGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final JdbcTemplate jdbc;

    public TrialGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/billing/**", "/api/public/**", "/api/platform/**", "/api/contact");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("GET".equals(req.getMethod()) || "OPTIONS".equals(req.getMethod())) {
            return true;
        }
        TenantContext.Caller caller = TenantContext.callerOrNull();
        if (caller == null) {
            return true;
        }
        // Activity heartbeat for the control panel, written at most every five minutes per workspace.
        jdbc.update("UPDATE tenants SET last_active_at = now() WHERE id = ? AND (last_active_at IS NULL OR last_active_at < now() - interval '5 minutes')",
                caller.tenantId());
        Map<String, Object> t = jdbc.queryForMap(
                "SELECT status, trial_ends_at, paid_until FROM tenants WHERE id = ?", caller.tenantId());
        if (isLocked((String) t.get("status"), (Timestamp) t.get("trial_ends_at"), (Timestamp) t.get("paid_until"))) {
            res.setStatus(402);
            res.setContentType("application/json");
            String msg = "ACTIVE".equals(t.get("status"))
                    ? "Your bill was not paid and the " + GRACE_DAYS + " extra days are over. Pay it to keep working. You can still look at everything and download your reports; your data is safe."
                    : "Your free trial has ended. Choose a plan to keep working; your data is safe.";
            res.getWriter().write("{\"code\":\"TRIAL_EXPIRED\",\"message\":\"" + msg + "\"}");
            return false;
        }
        return true;
    }

    /** Days a paid plan keeps working after its bill date before the workspace turns read-only. */
    public static final int GRACE_DAYS = 3;

    public static boolean isLocked(String status, Timestamp trialEnds, Timestamp paidUntil) {
        Instant now = Instant.now();
        return switch (status) {
            case "TRIAL" -> trialEnds.toInstant().isBefore(now);
            case "ACTIVE" -> paidUntil != null && paidUntil.toInstant().plus(GRACE_DAYS, java.time.temporal.ChronoUnit.DAYS).isBefore(now);
            default -> true;
        };
    }
}
