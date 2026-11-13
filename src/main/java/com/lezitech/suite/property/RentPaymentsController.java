package com.lezitech.suite.property;

import com.lezitech.suite.common.Body;
import com.lezitech.suite.mpesa.MpesaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M-Pesa for landlords: a "pay your rent" prompt to the tenant's phone, and paybill payments that didn't carry a
 * unit's account number, for the landlord to say whose they are. Not one transaction: the prompt is saved before
 * Safaricom (or the simulator) answers. Progress of a prompt: {@code GET /api/mpesa/requests/{id}}.
 */
@RestController
@RequestMapping("/api/property")
public class RentPaymentsController {

    private final MpesaService mpesa;

    public RentPaymentsController(MpesaService mpesa) {
        this.mpesa = mpesa;
    }

    @PostMapping("/leases/{id}/prompt")
    public Map<String, Object> prompt(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        Body b = new Body(raw);
        return mpesa.startLeasePayment(id, b.str("phone", null), b.has("amount") ? b.num("amount") : null);
    }

    /** Paybill payments not yet put on a lease. */
    @GetMapping("/paybill")
    public List<Map<String, Object>> unmatched() {
        return mpesa.c2bList("UNMATCHED");
    }

    @PostMapping("/paybill/{id}/assign")
    public Map<String, Object> assign(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return mpesa.applyToLease(id, UUID.fromString(new Body(raw).str("leaseId")));
    }

    @PostMapping("/paybill/{id}/ignore")
    public Map<String, Object> ignore(@PathVariable UUID id) {
        mpesa.ignore(id);
        return Map.of("ignored", true);
    }
}
