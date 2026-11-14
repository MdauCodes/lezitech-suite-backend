package com.lezitech.suite.property;

import com.lezitech.suite.common.Body;
import com.lezitech.suite.mpesa.MpesaService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The market collector's round on their phone, and the office's count of each collector's cash. */
@RestController
public class MarketController {

    private final Market market;
    private final MpesaService mpesa;

    public MarketController(Market market, MpesaService mpesa) {
        this.market = market;
        this.mpesa = mpesa;
    }

    private static LocalDate day(String d) {
        return d == null || d.isBlank() ? LeaseLedger.today() : LocalDate.parse(d);
    }

    @GetMapping("/api/market/round")
    @Transactional
    public Map<String, Object> round(@RequestParam(required = false) String day) {
        return market.round(day(day));
    }

    /** {leaseId, amount, day} cash taken from a stall. */
    @PostMapping("/api/market/collect")
    @Transactional
    public Map<String, Object> collect(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        return market.collect(UUID.fromString(b.str("leaseId")), b.num("amount"), day(b.str("day", null)));
    }

    /** An M-Pesa prompt to the trader's phone (not one transaction: the prompt is saved before the phone answers). */
    @PostMapping("/api/market/leases/{id}/prompt")
    public Map<String, Object> prompt(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        Body b = new Body(raw);
        return mpesa.startLeasePayment(id, b.str("phone", null), b.has("amount") ? b.num("amount") : null);
    }

    @PostMapping("/api/market/excuse")
    @Transactional
    public Map<String, Object> excuse(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        return market.excuse(UUID.fromString(b.str("leaseId")), day(b.str("day", null)), b.str("reason", ""));
    }

    @PostMapping("/api/market/tickets")
    @Transactional
    public Map<String, Object> ticket(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        return market.ticket(b.str("name", null), b.str("phone", null), b.has("amount") ? b.num("amount") : null, day(b.str("day", null)));
    }

    /** The office: every collector's cash for a day. */
    @GetMapping("/api/market/days")
    @Transactional
    public List<Map<String, Object>> days(@RequestParam(required = false) String day) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        return market.days(day(day));
    }

    @PostMapping("/api/market/days/{collectorId}/count")
    @Transactional
    public Map<String, Object> count(@PathVariable UUID collectorId, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        Body b = new Body(raw);
        return market.count(collectorId, day(b.str("day", null)), b.num("counted", null), b.str("note", null));
    }
}
