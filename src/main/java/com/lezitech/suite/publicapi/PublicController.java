package com.lezitech.suite.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Screens customers see without logging in, all in the business's own branding:
 * the staff sign-in page, the order-status board, an order tracking page and the QR menu.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final JdbcTemplate jdbc;
    private final PublicService service;
    private final SectorCatalog sectors;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.restaurant.TableOrderService tableOrders;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.restaurant.KitchenService kitchen;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.restaurant.TablePayService pay;

    /** Payment prompts from one tracking link: 3 per 10 minutes. */
    public static final RateLimit PER_ORDER_PAY = new RateLimit(3, 600_000);

    /** Orders from one table, and from one phone or network, per hour. */
    public static final RateLimit PER_TABLE = new RateLimit(5, 3_600_000);
    public static final RateLimit PER_CLIENT = new RateLimit(12, 3_600_000);

    public PublicController(JdbcTemplate jdbc, PublicService service, SectorCatalog sectors, ObjectMapper json) {
        this.jdbc = jdbc;
        this.service = service;
        this.sectors = sectors;
        this.json = json;
    }

    @GetMapping("/workspace/{slug}")
    public Map<String, Object> workspace(@PathVariable String slug) {
        return brand(tenant(slug));
    }

    @GetMapping("/board/{slug}")
    public Map<String, Object> board(@PathVariable String slug, @RequestParam("k") String key) {
        Map<String, Object> t = tenant(slug);
        if (!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), ((String) t.get("display_key")).getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.notFound("Screen");
        }
        Map<String, Object> out = brand(t);
        out.putAll(as(t, service::board));
        return out;
    }

    @GetMapping("/track/{slug}/{token}")
    public Map<String, Object> track(@PathVariable String slug, @PathVariable UUID token) {
        Map<String, Object> t = tenant(slug);
        Map<String, Object> out = brand(t);
        out.put("order", as(t, () -> service.track(token)));
        out.put("payment", as(t, () -> pay.progress(service.orderOfToken(token))));
        out.put("tipPresets", ordering(t).get("tipPresets"));
        return out;
    }

    /** The diner pays everything from their phone (with an optional tip). */
    @PostMapping("/track/{slug}/{token}/pay")
    public Map<String, Object> payFromPhone(@PathVariable String slug, @PathVariable UUID token, @RequestBody Map<String, Object> raw) {
        Map<String, Object> t = tenant(slug);
        limitPay(token);
        var b = new com.lezitech.suite.common.Body(raw);
        return as(t, () -> {
            UUID order = orderOr404(token);
            Map<String, Object> req = pay.payAll(order, b.str("phone", ""), b.num("tip", java.math.BigDecimal.ZERO));
            return Map.of("ok", true, "status", req.get("status"));
        });
    }

    /** Split between phones; prompts go out one at a time. */
    @PostMapping("/track/{slug}/{token}/split")
    public Map<String, Object> splitFromPhone(@PathVariable String slug, @PathVariable UUID token, @RequestBody Map<String, Object> raw) {
        Map<String, Object> t = tenant(slug);
        limitPay(token);
        var b = new com.lezitech.suite.common.Body(raw);
        return as(t, () -> pay.split(orderOr404(token), b.list("payers"), b.num("tip", java.math.BigDecimal.ZERO)));
    }

    @PostMapping("/track/{slug}/{token}/rating")
    public Map<String, Object> rate(@PathVariable String slug, @PathVariable UUID token, @RequestBody Map<String, Object> raw) {
        Map<String, Object> t = tenant(slug);
        var b = new com.lezitech.suite.common.Body(raw);
        as(t, () -> {
            pay.rate(orderOr404(token), b.integer("stars", 0), b.str("comment", null));
            return null;
        });
        return Map.of("ok", true);
    }

    private UUID orderOr404(UUID token) {
        UUID id = service.orderOfToken(token);
        if (id == null) {
            throw ApiException.notFound("Order");
        }
        return id;
    }

    private static void limitPay(UUID token) {
        if (!PER_ORDER_PAY.allow(token.toString())) {
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY",
                    "Several payment prompts were sent just now. Wait a few minutes, or ask your waiter to help.");
        }
    }

    /** The diner answers "would X do instead?" from their tracking page. */
    @PostMapping("/track/{slug}/{token}/substitute/{lineId}")
    public Map<String, Object> answerSubstitute(@PathVariable String slug, @PathVariable UUID token, @PathVariable UUID lineId,
                                                @RequestBody Map<String, Object> raw) {
        Map<String, Object> t = tenant(slug);
        boolean accept = Boolean.TRUE.equals(raw.get("accept"));
        as(t, () -> {
            if (service.orderOfLine(token, lineId) == null) {
                throw ApiException.notFound("That dish");
            }
            kitchen.answerSubstitute(lineId, accept);
            return null;
        });
        return Map.of("ok", true);
    }

    @GetMapping("/menu/{slug}")
    public Map<String, Object> menu(@PathVariable String slug, @RequestParam(required = false) UUID table) {
        Map<String, Object> t = tenant(slug);
        Map<String, Object> out = brand(t);
        out.putAll(as(t, service::menu));
        Map<String, Object> ordering = ordering(t);
        boolean open = Boolean.TRUE.equals(ordering.get("selfOrder")) && !locked(t);
        out.put("ordering", Map.of("open", open, "payWhen", ordering.get("payWhen")));
        if (table != null) {
            out.put("table", as(t, () -> service.table(table)));
        }
        return out;
    }

    /**
     * A diner's order from the table QR (tableId) or the menu link (takeaway, gets a pickup code). Prices come from
     * the menu, never from the phone. Pay-first restaurants hold it until it's paid; others send it to the kitchen.
     */
    @PostMapping("/orders/{slug}")
    public Map<String, Object> placeOrder(@PathVariable String slug, @RequestBody Map<String, Object> raw,
                                          jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> t = tenant(slug);
        Map<String, Object> ordering = ordering(t);
        if (!Boolean.TRUE.equals(ordering.get("selfOrder")) || locked(t)) {
            throw ApiException.forbidden("This restaurant isn't taking orders by phone right now. Please ask a waiter.");
        }
        String client = req.getHeader("X-Forwarded-For") != null ? req.getHeader("X-Forwarded-For").split(",")[0].trim() : req.getRemoteAddr();
        Object table = raw.get("tableId");
        if (!PER_CLIENT.allow(t.get("id") + ":" + client) || (table != null && !PER_TABLE.allow(t.get("id") + ":" + table))) {
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY",
                    "Lots of orders from here in the last hour. Please ask a waiter to help.");
        }
        var placed = as(t, () -> tableOrders.place(raw, (String) ordering.get("payWhen")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", placed.number());
        out.put("token", placed.token());
        out.put("total", placed.total());
        out.put("table", placed.tableLabel());
        out.put("pickupCode", placed.pickupCode());
        out.put("heldForPayment", placed.heldForPayment());
        out.put("addedToOpenBill", placed.addedToOpenBill());
        out.put("trackPath", "/track/" + slug + "/" + placed.token());
        return out;
    }

    private Map<String, Object> ordering(Map<String, Object> t) {
        try {
            Object saved = json.readTree((String) t.get("settings")).path("ordering");
            return com.lezitech.suite.restaurant.OrderingSettings.withDefaults(
                    json.convertValue(saved, Map.class), (String) t.get("sector"));
        } catch (Exception e) {
            return com.lezitech.suite.restaurant.OrderingSettings.withDefaults(null, (String) t.get("sector"));
        }
    }

    private static boolean locked(Map<String, Object> t) {
        return com.lezitech.suite.config.TrialGuard.isLocked((String) t.get("status"),
                (java.sql.Timestamp) t.get("trial_ends_at"), (java.sql.Timestamp) t.get("paid_until"));
    }

    private Map<String, Object> tenant(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, slug, name, sector, sub_sector, brand_color, logo_url, phone, currency, display_key, status,
                       trial_ends_at, paid_until, settings::text AS settings FROM tenants WHERE slug = ?""", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Business");
        }
        return rows.get(0);
    }

    private Map<String, Object> brand(Map<String, Object> t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", t.get("slug"));
        out.put("name", t.get("name"));
        out.put("sector", t.get("sector"));
        out.put("sectorName", sectors.get((String) t.get("sector")).get("name").asText());
        out.put("subSector", t.get("sub_sector"));
        out.put("brandColor", t.get("brand_color"));
        out.put("logoUrl", t.get("logo_url"));
        out.put("phone", t.get("phone"));
        out.put("currency", t.get("currency"));
        try {
            var s = json.readTree((String) t.get("settings"));
            out.put("accentColor", s.path("accentColor").asText(null));
            out.put("tagline", s.path("tagline").asText(null));
            out.put("address", s.path("address").asText(null));
        } catch (Exception ignored) {
            // settings are always valid JSON; branding is best effort
        }
        return out;
    }

    private <T> T as(Map<String, Object> t, Supplier<T> work) {
        return TenantContext.runAs((UUID) t.get("id"), null, work);
    }
}
