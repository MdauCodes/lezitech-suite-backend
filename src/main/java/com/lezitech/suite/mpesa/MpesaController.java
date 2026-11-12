package com.lezitech.suite.mpesa;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.settings.SettingsController;
import com.lezitech.suite.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class MpesaController {

    private static final Logger log = LoggerFactory.getLogger(MpesaController.class);

    private final MpesaService mpesa;
    private final SettingsController settings;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.sales.TillRules tills;

    public MpesaController(MpesaService mpesa, SettingsController settings) {
        this.mpesa = mpesa;
        this.settings = settings;
    }

    @GetMapping("/api/mpesa/config")
    public Map<String, Object> config() {
        TenantContext.caller();
        return mpesa.publicConfig();
    }

    /** Send the "enter your M-Pesa PIN" prompt for an open sale. */
    @PostMapping("/api/mpesa/stk")
    @Transactional
    public Map<String, Object> stk(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        tills.requireOpenTill();
        return mpesa.startOrderPayment(UUID.fromString(b.str("orderId")), b.str("phone"), b.num("amount", null));
    }

    @GetMapping("/api/mpesa/requests/{id}")
    public Map<String, Object> status(@PathVariable UUID id) {
        return mpesa.status(id);
    }

    @PostMapping("/api/mpesa/requests/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) {
        return mpesa.cancel(id);
    }

    /** Payments made straight to the till number (not from a prompt). */
    @GetMapping("/api/mpesa/till-payments")
    public List<Map<String, Object>> tillPayments(@RequestParam(defaultValue = "UNMATCHED") String status) {
        TenantContext.caller();
        return mpesa.c2bList(status.toUpperCase());
    }

    @PostMapping("/api/mpesa/till-payments/{id}/apply")
    public Map<String, Object> apply(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return mpesa.apply(id, UUID.fromString(new Body(raw).str("orderId")));
    }

    @PostMapping("/api/mpesa/till-payments/{id}/ignore")
    public Map<String, Object> ignore(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        mpesa.ignore(id);
        return Map.of("ok", true);
    }

    /** Owner pays for a plan by M-Pesa prompt; it switches on when Safaricom confirms. */
    @PostMapping("/api/billing/mpesa")
    @Transactional
    public Map<String, Object> payPlan(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("OWNER");
        String phone = new Body(raw).str("phone");
        Map<String, Object> req = settings.subscribe(Map.of("plan", raw.get("plan"), "cycle", raw.getOrDefault("cycle", "MONTHLY"), "mpesaPhone", phone));
        return mpesa.startSubscriptionPayment((UUID) req.get("requestId"), phone);
    }

    // ---------- Safaricom calls these (public; no login) ----------

    @PostMapping("/api/public/mpesa/stk/{token}")
    public Map<String, Object> stkCallback(@PathVariable String token, @RequestBody JsonNode payload) {
        mpesa.handleStkCallback(token, payload);
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }

    /** PayHero calls this when a prompt finishes. Unsigned, so it only triggers a status check with PayHero. */
    @PostMapping("/api/public/payhero/{token}")
    public Map<String, Object> payHeroCallback(@PathVariable String token) {
        try {
            mpesa.handlePayHeroCallback(token);
        } catch (RuntimeException e) {
            log.warn("PayHero callback check failed: {}", e.toString());
        }
        return Map.of("status", true);
    }

    @PostMapping("/api/public/mpesa/c2b/{secret}/validation")
    public Map<String, Object> c2bValidation(@PathVariable String secret, @RequestBody JsonNode payload) {
        guard(secret);
        return Map.of("ResultCode", "0", "ResultDesc", "Accepted");
    }

    @PostMapping("/api/public/mpesa/c2b/{secret}/confirmation")
    public Map<String, Object> c2bConfirmation(@PathVariable String secret, @RequestBody JsonNode payload) {
        guard(secret);
        try {
            mpesa.handleC2bConfirmation(payload);
        } catch (RuntimeException e) {
            log.warn("C2B confirmation not stored: {}", e.toString());
        }
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }

    private void guard(String secret) {
        if (!mpesa.c2bSecretOk(secret)) {
            throw ApiException.notFound("Page");
        }
    }
}
