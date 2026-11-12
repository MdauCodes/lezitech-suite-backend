package com.lezitech.suite.loyalty;

import com.lezitech.suite.common.Body;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** A customer's points and history, hand adjustments, and using points on a bill at the till. */
@RestController
@RequestMapping("/api")
@Transactional
public class LoyaltyController {

    private final LoyaltyService loyalty;
    private final OrderService orders;

    public LoyaltyController(LoyaltyService loyalty, OrderService orders) {
        this.loyalty = loyalty;
        this.orders = orders;
    }

    @GetMapping("/customers/{id}/loyalty")
    public Map<String, Object> summary(@PathVariable UUID id) {
        return loyalty.summary(id);
    }

    @PostMapping("/customers/{id}/loyalty/adjust")
    public Map<String, Object> adjust(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        loyalty.adjust(id, b.num("points", null), b.str("note", null));
        return loyalty.summary(id);
    }

    /** Use the customer's points on this bill: recorded as a POINTS payment worth what the points are worth. */
    @PostMapping("/orders/{id}/loyalty/redeem")
    public Map<String, Object> redeem(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        BigDecimal points = new Body(raw).num("points", null);
        BigDecimal value = loyalty.checkSpend(id, points);
        Map<String, Object> result = orders.payWithPoints(id, value, points.stripTrailingZeros().toPlainString() + " points");
        loyalty.spent(id, points);
        return result;
    }
}
