package com.lezitech.suite.finance;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Profit & loss for any dates (default: this month so far). Owner, manager and accountant; a plan tool ("profitLoss"). */
@RestController
public class ProfitLossController {

    private final ProfitLossService pnl;

    public ProfitLossController(ProfitLossService pnl) {
        this.pnl = pnl;
    }

    @GetMapping("/api/reports/profit-loss")
    public Map<String, Object> report(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Nairobi"));
        LocalDate f = from == null ? today.withDayOfMonth(1) : from;
        LocalDate t = to == null ? today : to;
        if (t.isBefore(f) || f.plusYears(2).isBefore(t)) {
            throw ApiException.bad("Choose dates within two years, the first before the second");
        }
        return pnl.report(f, t);
    }
}
