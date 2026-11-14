package com.lezitech.suite.property;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.mpesa.MpesaService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** The food court: the mall's setup and stall payments, the diner's ordering page, each stall's screen and the TV. */
@RestController
public class FoodCourtController {

    /** Orders from one phone or network: 12 an hour. Payment prompts per order: 4 per 10 minutes. */
    public static final RateLimit ORDERS = new RateLimit(12, 3_600_000);
    public static final RateLimit PAY = new RateLimit(4, 600_000);

    private final FoodCourt court;
    private final MpesaService mpesa;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public FoodCourtController(FoodCourt court, MpesaService mpesa, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.court = court;
        this.mpesa = mpesa;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    // ---------------- the mall ----------------

    @GetMapping("/api/property/foodcourt")
    @Transactional
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stalls", court.stalls());
        out.put("orders", court.todaysOrders());
        out.put("links", court.links());
        return out;
    }

    @PostMapping("/api/property/foodcourt/stalls")
    @Transactional
    public Map<String, Object> addStall(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return court.saveStall(null, new Body(raw));
    }

    @PostMapping("/api/property/foodcourt/stalls/{id}")
    @Transactional
    public Map<String, Object> saveStall(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return court.saveStall(id, new Body(raw));
    }

    @PostMapping("/api/property/foodcourt/stalls/{id}/items")
    @Transactional
    public Map<String, Object> addItem(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return court.saveItem(id, null, new Body(raw));
    }

    @PostMapping("/api/property/foodcourt/stalls/{id}/items/{itemId}")
    @Transactional
    public Map<String, Object> saveItem(@PathVariable UUID id, @PathVariable UUID itemId, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return court.saveItem(id, itemId, new Body(raw));
    }

    @PostMapping("/api/property/foodcourt/items/{itemId}/delete")
    @Transactional
    public Map<String, Object> removeItem(@PathVariable UUID itemId) {
        TenantContext.requireRole("MANAGER");
        court.removeItem(itemId);
        return Map.of("removed", true);
    }

    @PostMapping("/api/property/foodcourt/stalls/{id}/new-link")
    @Transactional
    public Map<String, Object> newLink(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        court.newScreenLink(id);
        return court.links();
    }

    @GetMapping("/api/property/foodcourt/stalls/{id}/days")
    @Transactional
    public List<Map<String, Object>> days(@PathVariable UUID id) {
        return court.days(id);
    }

    @PostMapping("/api/property/foodcourt/stalls/{id}/payouts")
    @Transactional
    public Map<String, Object> payout(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        return court.payout(id, new Body(raw));
    }

    // ---------------- public ----------------

    private Map<String, Object> business(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, phone, brand_color, logo_url, status FROM tenants WHERE slug = ? AND sector = 'property'", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Food court");
        }
        return rows.get(0);
    }

    private <T> T as(Map<String, Object> b, Supplier<T> work) {
        return TenantContext.runAs((UUID) b.get("id"), null, () -> tx.execute(x -> work.get()));
    }

    @GetMapping("/api/public/fc/{slug}")
    public Map<String, Object> menu(@PathVariable String slug) {
        Map<String, Object> b = business(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", Map.of("name", b.get("name"), "brandColor", b.get("brand_color") == null ? "" : b.get("brand_color"),
                "logoUrl", b.get("logo_url") == null ? "" : b.get("logo_url")));
        out.put("stalls", as(b, court::menu));
        return out;
    }

    /** {lines: [{itemId, qty}], phone, table}: the order, then one M-Pesa prompt to the diner's phone. */
    @PostMapping("/api/public/fc/{slug}/orders")
    public Map<String, Object> order(@PathVariable String slug, @RequestBody Map<String, Object> raw, HttpServletRequest req) {
        if (!ORDERS.allow(req.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Too many orders from here. Please wait a little.");
        }
        Map<String, Object> b = business(slug);
        Body body = new Body(raw);
        Map<String, Object> o = as(b, () -> court.place(body));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", o.get("token"));
        out.put("number", o.get("number"));
        out.put("total", o.get("total"));
        try {
            Map<String, Object> pay = TenantContext.runAs((UUID) b.get("id"), null, () -> mpesa.startFoodCourtPayment(UUID.fromString(o.get("id").toString()), body.str("phone", "")));
            out.put("payment", Map.of("status", pay.get("status")));
        } catch (ApiException e) {
            out.put("payment", Map.of("status", "FAILED", "message", e.getMessage()));
        }
        return out;
    }

    /** The diner's order page: number, payment, each stall's part. */
    @GetMapping("/api/public/fc/{slug}/orders/{token}")
    public Map<String, Object> track(@PathVariable String slug, @PathVariable String token) {
        Map<String, Object> b = business(slug);
        Map<String, Object> o = as(b, () -> court.track(token));
        List<Map<String, Object>> req = jdbc.queryForList("""
                SELECT status, result_desc FROM mpesa_requests WHERE fc_order_id = cast(? as uuid) ORDER BY created_at DESC LIMIT 1""", o.get("orderId").toString());
        o.remove("orderId");
        if (!req.isEmpty()) {
            o.put("payment", Map.of("status", req.get(0).get("status"), "message", req.get(0).get("result_desc") == null ? "" : req.get(0).get("result_desc")));
        }
        return o;
    }

    /** Try paying again (the prompt was cancelled, or a different phone). */
    @PostMapping("/api/public/fc/{slug}/orders/{token}/pay")
    public Map<String, Object> pay(@PathVariable String slug, @PathVariable String token, @RequestBody Map<String, Object> raw) {
        if (!PAY.allow(token)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Please wait a little before trying again.");
        }
        Map<String, Object> b = business(slug);
        UUID order = as(b, () -> court.orderIdOfToken(token));
        if (order == null) {
            throw ApiException.notFound("Order");
        }
        Map<String, Object> pay = TenantContext.runAs((UUID) b.get("id"), null, () -> mpesa.startFoodCourtPayment(order, new Body(raw).str("phone", "")));
        return Map.of("status", pay.get("status"));
    }

    @GetMapping("/api/public/fc/{slug}/kitchen/{stallId}")
    public Map<String, Object> kitchen(@PathVariable String slug, @PathVariable UUID stallId, @RequestParam("k") String key) {
        Map<String, Object> b = business(slug);
        return as(b, () -> {
            Map<String, Object> s = court.stallByKey(stallId, key);
            Map<String, Object> out = new LinkedHashMap<>(court.kitchen(stallId));
            out.put("stall", s.get("name"));
            out.put("business", b.get("name"));
            return out;
        });
    }

    /** {state: READY | COLLECTED} for the stall's part of an order. */
    @PostMapping("/api/public/fc/{slug}/kitchen/{stallId}/orders/{orderId}")
    public Map<String, Object> mark(@PathVariable String slug, @PathVariable UUID stallId, @PathVariable UUID orderId, @RequestParam("k") String key,
                                    @RequestBody Map<String, Object> raw) {
        Map<String, Object> b = business(slug);
        return as(b, () -> {
            court.stallByKey(stallId, key);
            court.mark(stallId, orderId, new Body(raw).str("state", "").toUpperCase());
            return Map.of("saved", true);
        });
    }

    @PostMapping("/api/public/fc/{slug}/kitchen/{stallId}/items/{itemId}")
    public Map<String, Object> available(@PathVariable String slug, @PathVariable UUID stallId, @PathVariable UUID itemId, @RequestParam("k") String key,
                                         @RequestBody Map<String, Object> raw) {
        Map<String, Object> b = business(slug);
        return as(b, () -> {
            court.stallByKey(stallId, key);
            court.setAvailable(stallId, itemId, new Body(raw).bool("available", true));
            return Map.of("saved", true);
        });
    }

    @GetMapping("/api/public/fc/{slug}/board")
    public Map<String, Object> board(@PathVariable String slug, @RequestParam("k") String key) {
        Map<String, Object> b = business(slug);
        return as(b, () -> {
            if (!court.boardKeyOk(key)) {
                throw ApiException.notFound("Screen");
            }
            Map<String, Object> out = new LinkedHashMap<>(court.board());
            out.put("business", b.get("name"));
            return out;
        });
    }
}
