package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ClientIp;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.mpesa.MpesaService;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** R7: cake orders (staff list and actions, the customer's order page, deposits, reminders) and the bakery's day. */
@RestController
public class CakeController {

    private static final Logger log = LoggerFactory.getLogger(CakeController.class);
    /** Orders from one network: 6 an hour. Deposit prompts per order: 4 per 10 minutes. Photos: 5 per order an hour. */
    public static final RateLimit ORDERS = new RateLimit(6, 3_600_000);
    public static final RateLimit DEPOSIT = new RateLimit(4, 600_000);
    public static final RateLimit PHOTOS = new RateLimit(5, 3_600_000);

    private final Cakes cakes;
    private final Bakery bakery;
    private final Delivery delivery;
    private final MpesaService mpesa;
    private final FileService files;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CakeController(Cakes cakes, Bakery bakery, Delivery delivery, MpesaService mpesa, FileService files, JdbcTemplate jdbc,
                          PlatformTransactionManager txm) {
        this.cakes = cakes;
        this.bakery = bakery;
        this.delivery = delivery;
        this.mpesa = mpesa;
        this.files = files;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    // ---------------- the bakery's day ----------------

    @GetMapping("/api/bakery")
    @Transactional
    public Map<String, Object> day(@RequestParam(required = false) LocalDate day) {
        return bakery.day(day);
    }

    @PostMapping("/api/bakery/items/{id}")
    @Transactional
    public Map<String, Object> setItem(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return bakery.setItem(id, new Body(raw));
    }

    @PostMapping("/api/bakery/baked")
    @Transactional
    public Map<String, Object> baked(@RequestBody Map<String, Object> raw) {
        return bakery.baked(new Body(raw));
    }

    @PostMapping("/api/bakery/count")
    @Transactional
    public Map<String, Object> count(@RequestBody Map<String, Object> raw) {
        return bakery.count(new Body(raw));
    }

    // ---------------- cake orders, staff ----------------

    @GetMapping("/api/cakes")
    @Transactional
    public Map<String, Object> list(@RequestParam(required = false) LocalDate from) {
        Map<String, Object> out = new LinkedHashMap<>(cakes.list(from));
        out.put("unseen", cakes.unseen());
        return out;
    }

    /** Staff opened the list: new online orders are seen. */
    @PostMapping("/api/cakes/seen")
    @Transactional
    public Map<String, Object> seen() {
        return Map.of("seen", cakes.seen());
    }

    @PostMapping("/api/cakes")
    @Transactional
    public Map<String, Object> place(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "CASHIER", "WAITER", "KITCHEN");
        return cakes.place(new Body(raw), "STAFF");
    }

    @PostMapping("/api/cakes/{id}/status")
    @Transactional
    public Map<String, Object> status(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return cakes.setStatus(id, new Body(raw).str("status", ""));
    }

    @PostMapping("/api/cakes/{id}/price")
    @Transactional
    public Map<String, Object> price(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return cakes.setPrice(id, new Body(raw).num("price", null));
    }

    @PostMapping("/api/cakes/{id}/collect")
    @Transactional
    public Map<String, Object> collect(@PathVariable UUID id) {
        return cakes.collect(id);
    }

    @PostMapping("/api/cakes/{id}/cancel")
    @Transactional
    public Map<String, Object> cancel(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER", "CASHIER");
        return cakes.cancel(id, raw == null ? null : new Body(raw).str("reason", null), false);
    }

    @GetMapping("/api/cakes/settings")
    @Transactional
    public Map<String, Object> settings() {
        return cakes.rulesView(cakes.rules(TenantContext.tenantId()));
    }

    @PostMapping("/api/cakes/settings")
    @Transactional
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> raw) {
        cakes.saveRules(new Body(raw));
        return settings();
    }

    // ---------------- the customer's side ----------------

    private Map<String, Object> business(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, phone, brand_color, logo_url, status FROM tenants WHERE slug = ?", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Bakery");
        }
        return rows.get(0);
    }

    private <T> T as(Map<String, Object> b, Supplier<T> work) {
        return TenantContext.runAs((UUID) b.get("id"), null, () -> tx.execute(x -> work.get()));
    }

    private static Map<String, Object> brand(Map<String, Object> b) {
        return Map.of("name", b.get("name"), "phone", b.get("phone") == null ? "" : b.get("phone"),
                "brandColor", b.get("brand_color") == null ? "" : b.get("brand_color"), "logoUrl", b.get("logo_url") == null ? "" : b.get("logo_url"));
    }

    /** The order page: flavours and prices, sizes, the deposit, how soon, and delivery areas. */
    @GetMapping("/api/public/cakes/{slug}")
    public Map<String, Object> page(@PathVariable String slug) {
        Map<String, Object> b = business(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", brand(b));
        as(b, () -> {
            Cakes.Rules r = cakes.rules((UUID) b.get("id"));
            out.put("rules", cakes.rulesView(r));
            Map<String, Object> d = delivery.publicView();
            out.put("areas", r.delivery() ? d.get("areas") : List.of());
            return null;
        });
        return out;
    }

    @PostMapping("/api/public/cakes/{slug}")
    public Map<String, Object> order(@PathVariable String slug, @RequestBody Map<String, Object> raw, HttpServletRequest req) {
        String client = ClientIp.of(req);
        Map<String, Object> b = business(slug);
        if (!ORDERS.allow(b.get("id") + ":" + client)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY", "Lots of orders from here in the last hour. Please call the bakery.");
        }
        Map<String, Object> c = as(b, () -> cakes.place(new Body(raw), "ONLINE"));
        Map<String, Object> out = new LinkedHashMap<>(as(b, () -> cakes.publicView(UUID.fromString(c.get("id").toString()))));
        // Start the deposit prompt straight away when one is needed and a number was given.
        if ("WAITING_DEPOSIT".equals(c.get("status"))) {
            try {
                UUID id = UUID.fromString(c.get("id").toString());
                TenantContext.runAs((UUID) b.get("id"), null, () -> mpesa.startCakeDeposit(id, (String) c.get("phone")));
                out.put("depositPrompt", "SENT");
            } catch (ApiException e) {
                out.put("depositPrompt", e.getMessage());
            }
        }
        return out;
    }

    @GetMapping("/api/public/cakes/{slug}/o/{token}")
    public Map<String, Object> mine(@PathVariable String slug, @PathVariable String token) {
        Map<String, Object> b = business(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", brand(b));
        out.put("order", as(b, () -> cakes.publicView(cakes.idOfToken(token))));
        return out;
    }

    @PostMapping("/api/public/cakes/{slug}/o/{token}/pay")
    public Map<String, Object> pay(@PathVariable String slug, @PathVariable String token, @RequestBody Map<String, Object> raw) {
        if (!DEPOSIT.allow(token)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Please wait a little before trying again.");
        }
        Map<String, Object> b = business(slug);
        UUID id = as(b, () -> cakes.idOfToken(token));
        Map<String, Object> pay = TenantContext.runAs((UUID) b.get("id"), null, () -> mpesa.startCakeDeposit(id, new Body(raw).str("phone", "")));
        return Map.of("status", pay.get("status"));
    }

    /** A photo of the design they want (one per order; a new one replaces it). */
    @PostMapping("/api/public/cakes/{slug}/o/{token}/photo")
    public Map<String, Object> photo(@PathVariable String slug, @PathVariable String token, @RequestParam("file") MultipartFile upload) throws IOException {
        if (!PHOTOS.allow(token)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Please wait a little before trying again.");
        }
        byte[] bytes = upload.getBytes();
        Map<String, Object> b = business(slug);
        return as(b, () -> {
            UUID id = cakes.idOfToken(token);
            UUID file = files.save(FileService.Purpose.CAKE_DESIGN, bytes);
            jdbc.update("UPDATE cake_orders SET design_file_id = ? WHERE id = ?", file, id);
            return Map.of("ok", true);
        });
    }

    @PostMapping("/api/public/cakes/{slug}/o/{token}/cancel")
    public Map<String, Object> cancelMine(@PathVariable String slug, @PathVariable String token) {
        Map<String, Object> b = business(slug);
        return as(b, () -> cakes.publicView(UUID.fromString(cakes.cancel(cakes.idOfToken(token), null, true).get("id").toString())));
    }

    // ---------------- after a deposit, and reminders ----------------

    @EventListener
    public void onDeposit(MpesaService.CakeDepositSettled e) {
        try {
            TenantContext.runAs(e.tenant(), null, () -> tx.execute(s -> {
                cakes.depositPaid(e.cakeOrderId());
                return null;
            }));
        } catch (RuntimeException ex) {
            log.warn("Confirming cake order {}: {}", e.cakeOrderId(), ex.toString());
        }
    }

    /** Every 10 minutes: day-before reminders, and orders whose deposit was never paid lapse after a day. */
    @Scheduled(fixedDelayString = "${suite.cakes.every-ms:600000}", initialDelay = 90_000)
    public void jobs() {
        List<UUID> tenants = jdbc.queryForList("""
                SELECT id FROM tenants WHERE status <> 'SUSPENDED' AND sector IN ('restaurant', 'hotel')""", UUID.class);
        for (UUID t : tenants) {
            try {
                TenantContext.runAs(t, null, () -> tx.execute(s -> {
                    cakes.remind();
                    return null;
                }));
            } catch (RuntimeException e) {
                log.warn("Cake jobs for {}: {}", t, e.toString());
            }
        }
    }
}
