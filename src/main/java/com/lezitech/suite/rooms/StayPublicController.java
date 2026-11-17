package com.lezitech.suite.rooms;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.mpesa.MpesaService;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The guest's side of rooms: the booking page at /stay/{slug}, their booking at /stay/{slug}/s/{token}, the deposit,
 * each room's calendar for Airbnb/Booking.com, and the jobs (calendar sync every 15 minutes, freeing unpaid holds).
 */
@RestController
public class StayPublicController {

    private static final Logger log = LoggerFactory.getLogger(StayPublicController.class);
    public static final RateLimit BOOKINGS = new RateLimit(8, 3_600_000);
    public static final RateLimit DEPOSIT = new RateLimit(4, 600_000);

    private final OnlineStays online;
    private final RoomCalendars calendars;
    private final MpesaService mpesa;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public StayPublicController(OnlineStays online, RoomCalendars calendars, MpesaService mpesa, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.online = online;
        this.calendars = calendars;
        this.mpesa = mpesa;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    /** Businesses with rooms: hotels, lodges, guest houses, restaurants with rooms. */
    private Map<String, Object> business(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, phone, brand_color, logo_url, status, sector, sub_sector FROM tenants WHERE slug = ?""", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Business");
        }
        Map<String, Object> b = rows.get(0);
        boolean rooms = "hotel".equals(b.get("sector")) || "restaurant-rooms".equals(b.get("sub_sector"));
        if (!rooms) {
            throw ApiException.notFound("Business");
        }
        return b;
    }

    private <T> T as(Map<String, Object> b, Supplier<T> work) {
        return TenantContext.runAs((UUID) b.get("id"), null, () -> tx.execute(x -> work.get()));
    }

    private static Map<String, Object> brand(Map<String, Object> b) {
        return Map.of("name", b.get("name"), "phone", b.get("phone") == null ? "" : b.get("phone"),
                "brandColor", b.get("brand_color") == null ? "" : b.get("brand_color"), "logoUrl", b.get("logo_url") == null ? "" : b.get("logo_url"));
    }

    @GetMapping("/api/public/stay/{slug}")
    public Map<String, Object> page(@PathVariable String slug, @RequestParam(required = false) LocalDate checkIn,
                                    @RequestParam(required = false) LocalDate checkOut, @RequestParam(defaultValue = "2") int guests) {
        Map<String, Object> b = business(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", brand(b));
        as(b, () -> {
            online.sweep();
            StayService.Rules r = online.rulesOf();
            out.put("rules", Map.of("online", r.online(), "depositPercent", r.depositPercent(), "cancelHours", r.cancelHours()));
            out.put("options", r.online() && checkIn != null && checkOut != null ? online.options(checkIn, checkOut, guests) : List.of());
            return null;
        });
        return out;
    }

    @PostMapping("/api/public/stay/{slug}")
    public Map<String, Object> book(@PathVariable String slug, @RequestBody Map<String, Object> raw, HttpServletRequest req) {
        String client = req.getHeader("X-Forwarded-For") != null ? req.getHeader("X-Forwarded-For").split(",")[0].trim() : req.getRemoteAddr();
        Map<String, Object> b = business(slug);
        if (!BOOKINGS.allow(b.get("id") + ":" + client)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Several bookings from here just now. Please call us.");
        }
        Body body = new Body(raw);
        Map<String, Object> v = as(b, () -> online.book(body));
        Map<String, Object> out = new LinkedHashMap<>(v);
        if ("HELD".equals(v.get("status")) && body.has("phone")) {
            try {
                Map<String, Object> pay = TenantContext.runAs((UUID) b.get("id"), null,
                        () -> mpesa.startStayDeposit(UUID.fromString(v.get("id").toString()), body.str("phone")));
                out.put("payment", Map.of("status", pay.get("status")));
            } catch (ApiException e) {
                out.put("payment", Map.of("status", "FAILED", "message", e.getMessage()));
            }
        }
        out.remove("id");
        return out;
    }

    @GetMapping("/api/public/stay/{slug}/s/{token}")
    public Map<String, Object> mine(@PathVariable String slug, @PathVariable String token) {
        Map<String, Object> b = business(slug);
        Map<String, Object> v = as(b, () -> {
            online.sweep();
            UUID id = online.idOfToken(token);
            if (id == null) {
                throw ApiException.notFound("Booking");
            }
            return online.view(id);
        });
        Map<String, Object> out = new LinkedHashMap<>(v);
        out.remove("id");
        out.put("business", brand(b));
        return out;
    }

    @PostMapping("/api/public/stay/{slug}/s/{token}/cancel")
    public Map<String, Object> cancel(@PathVariable String slug, @PathVariable String token) {
        Map<String, Object> b = business(slug);
        return as(b, () -> {
            UUID id = online.idOfToken(token);
            if (id == null) {
                throw ApiException.notFound("Booking");
            }
            Map<String, Object> v = new LinkedHashMap<>(online.cancelByGuest(id));
            v.remove("id");
            return v;
        });
    }

    @PostMapping("/api/public/stay/{slug}/s/{token}/pay")
    public Map<String, Object> pay(@PathVariable String slug, @PathVariable String token, @RequestBody Map<String, Object> raw) {
        if (!DEPOSIT.allow(token)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Please wait a little before trying again.");
        }
        Map<String, Object> b = business(slug);
        UUID id = as(b, () -> online.idOfToken(token));
        if (id == null) {
            throw ApiException.notFound("Booking");
        }
        Map<String, Object> pay = TenantContext.runAs((UUID) b.get("id"), null, () -> mpesa.startStayDeposit(id, new Body(raw).str("phone", "")));
        return Map.of("status", pay.get("status"));
    }

    /** A room's calendar for Airbnb, Booking.com and others to read. */
    @GetMapping(value = "/api/public/ical/{slug}/{spaceId}/{token}.ics")
    public ResponseEntity<String> ical(@PathVariable String slug, @PathVariable UUID spaceId, @PathVariable String token) {
        List<UUID> t = jdbc.queryForList("SELECT id FROM tenants WHERE slug = ? AND status <> 'SUSPENDED'", UUID.class, slug);
        if (t.isEmpty()) {
            throw ApiException.notFound("Calendar");
        }
        String body = TenantContext.runAs(t.get(0), null, () -> tx.execute(x -> calendars.export(spaceId, token)));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar; charset=utf-8")).body(body);
    }

    @EventListener
    public void onDeposit(MpesaService.StayDepositSettled e) {
        try {
            TenantContext.runAs(e.tenant(), null, () -> tx.execute(s -> {
                online.depositPaid(e.stayId(), e.receipt());
                return null;
            }));
        } catch (RuntimeException ex) {
            log.warn("Confirming stay {}: {}", e.stayId(), ex.toString());
        }
    }

    /** Every 15 minutes: read other sites' calendars, and free rooms held for deposits nobody paid. */
    @Scheduled(fixedDelayString = "${suite.rooms.every-ms:900000}", initialDelay = 120_000)
    public void jobs() {
        List<UUID> tenants = jdbc.queryForList("""
                SELECT id FROM tenants WHERE status <> 'SUSPENDED' AND (sector = 'hotel' OR sub_sector = 'restaurant-rooms')""", UUID.class);
        for (UUID t : tenants) {
            try {
                TenantContext.runAs(t, null, () -> tx.execute(s -> {
                    online.sweep();
                    calendars.syncAll();
                    return null;
                }));
            } catch (RuntimeException e) {
                log.warn("Room jobs for {}: {}", t, e.toString());
            }
        }
    }
}
