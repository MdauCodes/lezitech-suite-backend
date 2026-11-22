package com.lezitech.suite.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Money decisions a cashier can't make alone: changing a price, giving a discount, ringing up
 * a custom-priced line. Managers and owners can; anyone else needs a manager to type their PIN
 * on the till, sent as {"approval": {"userId": "...", "pin": "1234"}}.
 */
@Service
public class ApprovalService {

    public static final BigDecimal DEFAULT_MAX_DISCOUNT_PERCENT = new BigDecimal("20");
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lezitech.suite.config.AccessPolicy access;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final ObjectMapper json;
    private final com.lezitech.suite.auth.LoginThrottle throttle;

    public ApprovalService(JdbcTemplate jdbc, PasswordEncoder encoder, ObjectMapper json, com.lezitech.suite.auth.LoginThrottle throttle) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.json = json;
        this.throttle = throttle;
    }

    /** Throws 403 APPROVAL_NEEDED unless the caller, or the PIN-approving manager, may do this. */
    public String require(Body b, String what) {
        if (isManager(TenantContext.caller().role())
                || (access != null && access.mayDiscount(TenantContext.tenantId(), TenantContext.caller().role()))) {
            return null;
        }
        return pinApproval(b, what);
    }

    /**
     * Like {@link #require} but a job that may give discounts gets no free pass: only an owner or
     * manager, or a manager's PIN, will do. For cancelling bills and selling below cost.
     * Returns the approving manager's name when a PIN was used, otherwise null.
     */
    public String requireManager(Body b, String what) {
        if (isManager(TenantContext.caller().role())) {
            return null;
        }
        return pinApproval(b, what);
    }

    private String pinApproval(Body b, String what) {
        Map<String, Object> approval = b == null ? Map.of() : b.map("approval");
        Body a = new Body(approval);
        if (a.has("userId") && a.has("pin")) {
            UUID managerId;
            try {
                managerId = UUID.fromString(a.str("userId").trim());
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.FORBIDDEN, "APPROVAL_NEEDED", "That manager PIN is not right");
            }
            // Keyed on the parsed id, so every spelling of the same id (capital or small letters) shares one count.
            String account = "approve:" + managerId;
            throttle.check(account, null);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT role, pin_hash, name FROM users WHERE id = ? AND tenant_id = ? AND active",
                    managerId, TenantContext.tenantId());
            if (!rows.isEmpty() && isManager((String) rows.get(0).get("role")) && rows.get(0).get("pin_hash") != null
                    && encoder.matches(a.str("pin"), (String) rows.get(0).get("pin_hash"))) {
                throttle.succeeded(account);
                return (String) rows.get(0).get("name");
            }
            throttle.failed(account, null);
            throw new ApiException(HttpStatus.FORBIDDEN, "APPROVAL_NEEDED", "That manager PIN is not right");
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "APPROVAL_NEEDED", "A manager must approve " + what);
    }

    /** Discounts (line + bill) may not exceed the workspace's limit; only the owner can go past it. */
    public void checkDiscountLimit(BigDecimal discount, BigDecimal grossBeforeDiscount) {
        if (discount.signum() <= 0 || "OWNER".equals(TenantContext.caller().role())) {
            return;
        }
        if (grossBeforeDiscount.signum() <= 0) {
            throw ApiException.bad("There is nothing to discount");
        }
        BigDecimal max = maxDiscountPercent();
        BigDecimal pct = discount.multiply(new BigDecimal("100")).divide(grossBeforeDiscount, 2, RoundingMode.HALF_UP);
        if (pct.compareTo(max) > 0) {
            throw ApiException.bad("Discounts above " + max.stripTrailingZeros().toPlainString()
                    + "% are not allowed in this business (this one is " + pct.stripTrailingZeros().toPlainString() + "%). The owner can change the limit in Settings.");
        }
    }

    public BigDecimal maxDiscountPercent() {
        try {
            String s = jdbc.queryForObject("SELECT settings::text FROM tenants WHERE id = ?", String.class, TenantContext.tenantId());
            JsonNode v = json.readTree(s).path("maxDiscountPercent");
            return v.isMissingNode() || v.isNull() ? DEFAULT_MAX_DISCOUNT_PERCENT : new BigDecimal(v.asText());
        } catch (Exception e) {
            return DEFAULT_MAX_DISCOUNT_PERCENT;
        }
    }

    private static boolean isManager(String role) {
        return "OWNER".equals(role) || "MANAGER".equals(role);
    }
}
