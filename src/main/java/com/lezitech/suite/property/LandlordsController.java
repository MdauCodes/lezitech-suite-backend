package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Landlords an agent collects for, their statements and payouts; rental tax; withholding-tax certificates. */
@RestController
public class LandlordsController {

    public static final RateLimit PUBLIC = new RateLimit(60, 3_600_000);

    private final Landlords landlords;
    private final LeaseLedger ledger;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public LandlordsController(Landlords landlords, LeaseLedger ledger, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.landlords = landlords;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    private static YearMonth month(String p) {
        return p == null || p.isBlank() ? YearMonth.now(ZoneId.of("Africa/Nairobi")) : YearMonth.parse(p);
    }

    @GetMapping("/api/property/landlords")
    @Transactional
    public List<Map<String, Object>> list(@RequestParam(required = false) String period) {
        return landlords.list(month(period));
    }

    @PostMapping("/api/property/landlords")
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return landlords.save(null, new Body(raw));
    }

    @PostMapping("/api/property/landlords/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return landlords.save(id, new Body(raw));
    }

    /** {spaceIds: [...]} owned by this landlord; landlord "none" gives them back to the business. */
    @PostMapping("/api/property/landlords/{id}/units")
    @Transactional
    public Map<String, Object> units(@PathVariable String id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Object ids = raw == null ? null : raw.get("spaceIds");
        List<String> spaces = ids instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        landlords.assignUnits("none".equals(id) ? null : UUID.fromString(id), spaces);
        return Map.of("saved", true);
    }

    @GetMapping("/api/property/landlords/{id}/statement")
    @Transactional
    public Map<String, Object> statement(@PathVariable UUID id, @RequestParam(required = false) String period) {
        return landlords.statement(id, month(period));
    }

    @PostMapping("/api/property/landlords/{id}/send")
    @Transactional
    public Map<String, Object> send(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return landlords.send(id, month(new Body(raw).str("period", null)));
    }

    @PostMapping("/api/property/landlords/{id}/expenses")
    @Transactional
    public Map<String, Object> expense(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return landlords.addExpense(id, new Body(raw));
    }

    @PostMapping("/api/property/landlords/{id}/payouts")
    @Transactional
    public Map<String, Object> payout(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return landlords.addPayout(id, new Body(raw));
    }

    @GetMapping("/api/property/rental-tax")
    @Transactional
    public Map<String, Object> rentalTax(@RequestParam(required = false) String period) {
        return landlords.rentalTax(month(period));
    }

    /** A company tenant paid rent less withholding tax: {certificateNo, amount, issuedOn}. Counts as paid. */
    @PostMapping("/api/property/leases/{id}/wht")
    @Transactional
    public Map<String, Object> wht(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        return ledger.receive(id, b.num("amount", null), "WHT", b.str("certificateNo", null), "MANUAL", "Withholding tax certificate",
                b.has("issuedOn") ? b.date("issuedOn") : null);
    }

    /** The landlord's own page from their private link (no password): their statement for a month. */
    @GetMapping("/api/public/owner/{slug}/{token}")
    public Map<String, Object> owner(@PathVariable String slug, @PathVariable String token, @RequestParam(required = false) String period,
                                     HttpServletRequest req) {
        if (!PUBLIC.allow(req.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Too many tries. Please wait a while and open the link again.");
        }
        if (token == null || !token.matches("[0-9a-f]{32,64}")) {
            throw ApiException.notFound("Statement");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, phone, brand_color, logo_url, status FROM tenants WHERE slug = ?", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Statement");
        }
        Map<String, Object> t = rows.get(0);
        UUID tenant = (UUID) t.get("id");
        Map<String, Object> s = TenantContext.runAs(tenant, null, () -> tx.execute(x -> {
            List<UUID> l = jdbc.queryForList("SELECT id FROM landlords WHERE tenant_id = ? AND statement_token = ? AND active", UUID.class, tenant, token);
            if (l.isEmpty()) {
                throw ApiException.notFound("Statement");
            }
            return landlords.statement(l.get(0), month(period));
        }));
        Map<String, Object> out = new LinkedHashMap<>(s);
        out.remove("link");
        ((Map<?, ?>) out.get("landlord")).remove("id");
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("name", t.get("name"));
        business.put("phone", t.get("phone"));
        business.put("brandColor", t.get("brand_color"));
        business.put("logoUrl", t.get("logo_url"));
        out.put("business", business);
        return out;
    }
}
