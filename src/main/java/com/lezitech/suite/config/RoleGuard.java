package com.lezitech.suite.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side job permissions, matching what each role sees in the app (lib/roles.ts).
 * The first rule whose method and path match decides; the owner may do everything; paths with
 * no rule are open to any signed-in staff member. Finer checks (e.g. refunds, leases, prices)
 * stay in the services as well.
 */
@Configuration
public class RoleGuard implements WebMvcConfigurer, HandlerInterceptor {

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> READS = Set.of("GET");
    private static final Set<String> ANY = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    record Rule(Set<String> methods, Pattern path, Set<String> roles, String what) {
    }

    private static Rule rule(Set<String> methods, String regex, String what, String... roles) {
        return new Rule(methods, Pattern.compile(regex), Set.of(roles), what);
    }

    /** Order matters: specific paths before their general prefix. */
    static final List<Rule> RULES = List.of(
            // People and money settings
            rule(READS, "/api/team/(approvers|till-users|names)", "see who can approve", "MANAGER", "CASHIER", "WAITER", "KITCHEN", "SALES", "STYLIST", "RECEPTION", "ACCOUNTANT"),
            rule(ANY, "/api/team.*", "manage staff", "MANAGER"),
            rule(ANY, "/api/billing/.*", "see or change the plan", "MANAGER"),
            rule(ANY, "/api/settings/payments.*", "see or change payment and SMS keys"),
            rule(WRITES, "/api/settings.*", "change settings"),
            // Reports with profit and totals
            rule(READS, "/api/reports/sales.*", "see sales reports", "MANAGER", "ACCOUNTANT"),
            rule(READS, "/api/reports/tax.*", "see the tax summary", "MANAGER", "ACCOUNTANT"),
            rule(READS, "/api/reports/commissions.*", "see commissions", "MANAGER", "ACCOUNTANT"),
            rule(WRITES, "/api/reports/commissions.*", "record commission payouts", "MANAGER"),
            rule(READS, "/api/reports/dashboard", "see the home numbers", "MANAGER", "SALES", "ACCOUNTANT"),
            rule(READS, "/api/accounting/.*", "see the accounts", "MANAGER", "ACCOUNTANT"),
            // Catalogue, stock and buying
            rule(WRITES, "/api/(items|categories|locations|floors|tables|suppliers)(/.*)?", "change items, places or suppliers", "MANAGER"),
            rule(ANY, "/api/(stock|purchases)(/.*)?", "see or change stock and buying", "MANAGER", "ACCOUNTANT"),
            // Selling
            rule(WRITES, "/api/kitchen/.*", "update the kitchen screen", "MANAGER", "KITCHEN", "WAITER", "RECEPTION", "CASHIER"),
            rule(WRITES, "/api/orders(/.*)?", "take orders or payments", "MANAGER", "CASHIER", "WAITER", "SALES", "STYLIST", "RECEPTION", "ACCOUNTANT"),
            rule(WRITES, "/api/shifts(/.*)?", "open or close the till", "MANAGER", "CASHIER", "SALES", "RECEPTION", "ACCOUNTANT"),
            rule(WRITES, "/api/mpesa(/.*)?", "take M-Pesa payments", "MANAGER", "CASHIER", "WAITER", "SALES", "STYLIST", "RECEPTION", "ACCOUNTANT"),
            rule(WRITES, "/api/customers/[^/]+/settle", "record money owed being paid", "MANAGER", "CASHIER", "SALES", "RECEPTION", "ACCOUNTANT"),
            rule(WRITES, "/api/customers(/.*)?", "change customers", "MANAGER", "CASHIER", "WAITER", "SALES", "STYLIST", "RECEPTION", "ACCOUNTANT"),
            // Sales leads
            rule(ANY, "/api/(pipeline|deals|activities)(/.*)?", "use sales leads", "MANAGER", "SALES"),
            // Bookings, rooms, trips, rent
            rule(WRITES, "/api/appointments(/.*)?", "change bookings", "MANAGER", "STYLIST", "CASHIER", "RECEPTION", "SALES"),
            rule(WRITES, "/api/spaces/[^/]+/housekeeping", "update room cleaning", "MANAGER", "RECEPTION"),
            rule(WRITES, "/api/spaces(/.*)?", "change rooms or units", "MANAGER"),
            rule(WRITES, "/api/stays(/.*)?", "change room bookings", "MANAGER", "RECEPTION"),
            rule(WRITES, "/api/trips(/.*)?", "change trips", "MANAGER", "SALES"),
            rule(ANY, "/api/property(/.*)?", "manage rent and tenants", "MANAGER", "ACCOUNTANT"));

    private final ObjectMapper json;

    public RoleGuard(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/public/**", "/api/platform/**", "/api/admin/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        TenantContext.Caller caller = TenantContext.callerOrNull();
        if (caller == null || "OWNER".equals(caller.role())) {
            return true;
        }
        String path = req.getRequestURI();
        for (Rule r : RULES) {
            if (r.methods().contains(req.getMethod()) && r.path().matcher(path).matches()) {
                if (r.roles().contains(caller.role())) {
                    return true;
                }
                res.setStatus(403);
                res.setContentType("application/json");
                res.getWriter().write(json.writeValueAsString(Map.of("code", "FORBIDDEN",
                        "message", "Your job (" + caller.role().toLowerCase() + ") can't " + r.what() + ". Ask the owner or a manager.")));
                return false;
            }
        }
        return true;
    }
}
