package com.lezitech.suite.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.platform.PlanFeatures;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/**
 * Closes the tools a business's plan doesn't include (PlanFeatures), for everyone in the business, owner too.
 * Runs on the same paths as RoleGuard; tools checked inside the code (quotes, owner powers, phone ordering) are not here.
 */
@Configuration
public class PlanGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final PlanFeatures features;
    private final ObjectMapper json;

    public PlanGuard(PlanFeatures features, ObjectMapper json) {
        this.features = features;
        this.json = json;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/public/**", "/api/platform/**", "/api/admin/**", "/api/portal/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        TenantContext.Caller caller = TenantContext.callerOrNull();
        if (caller == null) {
            return true;
        }
        String missing = features.missingFor(caller.tenantId(), req.getMethod(), req.getRequestURI());
        if (missing == null) {
            return true;
        }
        ApiException e = features.refusal(caller.tenantId(), missing);
        res.setStatus(403);
        res.setContentType("application/json");
        res.getWriter().write(json.writeValueAsString(Map.of("code", "PLAN_FEATURE", "message", e.getMessage(), "feature", missing)));
        return false;
    }
}
