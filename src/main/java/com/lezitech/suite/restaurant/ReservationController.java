package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ClientIp;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Table bookings: the staff list and actions, the ring, the guest's booking page, deposits, reminders. */
@RestController
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);
    /** Bookings from one network: 10 an hour. Deposit prompts per booking: 4 per 10 minutes. */
    public static final RateLimit BOOKINGS = new RateLimit(10, 3_600_000);
    public static final RateLimit DEPOSIT = new RateLimit(4, 600_000);

    private final Reservations res;
    private final MpesaService mpesa;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReservationController(Reservations res, MpesaService mpesa, JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.res = res;
        this.mpesa = mpesa;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
    }

    // ---------------- staff ----------------

    @GetMapping("/api/reservations")
    @Transactional
    public Map<String, Object> day(@RequestParam(required = false) LocalDate date) {
        return res.day(date == null ? LocalDate.now(Reservations.NAIROBI) : date);
    }

    /** A booking taken by phone or at the counter. */
    @PostMapping("/api/reservations")
    @Transactional
    public Map<String, Object> book(@RequestBody Map<String, Object> raw) {
        return res.book(new Body(raw), "STAFF");
    }

    @PostMapping("/api/reservations/waitlist")
    @Transactional
    public Map<String, Object> waitlist(@RequestBody Map<String, Object> raw) {
        return res.waitlist(new Body(raw));
    }

    @PostMapping("/api/reservations/{id}/seat")
    @Transactional
    public Map<String, Object> seat(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        return res.seat(id, new Body(raw == null ? Map.of() : raw));
    }

    @PostMapping("/api/reservations/{id}/ready")
    @Transactional
    public Map<String, Object> ready(@PathVariable UUID id) {
        return res.ready(id);
    }

    /** {status: NO_SHOW | CANCELLED, reason}. */
    @PostMapping("/api/reservations/{id}/close")
    @Transactional
    public Map<String, Object> close(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        return res.close(id, b.str("status", "").toUpperCase(), b.str("reason", null));
    }

    @PostMapping("/api/reservations/{id}/move")
    @Transactional
    public Map<String, Object> move(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return res.move(id, new Body(raw));
    }

    @GetMapping("/api/reservations/settings")
    @Transactional
    public Map<String, Object> rules() {
        return res.rulesView(res.rules(TenantContext.tenantId()));
    }

    @PostMapping("/api/reservations/settings")
    @Transactional
    public Map<String, Object> saveRules(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        res.saveRules(new Body(raw));
        return res.rulesView(res.rules(TenantContext.tenantId()));
    }

    /** Online bookings nobody has acknowledged: they ring on staff screens. */
    @GetMapping("/api/reservations/alerts")
    @Transactional
    public Map<String, Object> alerts() {
        return res.alerts();
    }

    @PostMapping("/api/reservations/alerts/stop")
    @Transactional
    public Map<String, Object> stop(@RequestBody(required = false) Map<String, Object> raw) {
        return Map.of("stopped", res.acknowledge(raw == null ? null : new Body(raw).str("upTo", null)));
    }

    // ---------------- the guest ----------------

    private Map<String, Object> business(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, phone, brand_color, logo_url, status, sector FROM tenants WHERE slug = ?", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Restaurant");
        }
        return rows.get(0);
    }

    private <T> T as(Map<String, Object> b, Supplier<T> work) {
        return TenantContext.runAs((UUID) b.get("id"), null, () -> tx.execute(x -> work.get()));
    }

    /** The booking page: the restaurant, its rules (party size, deposit), and free times for a day and party. */
    @GetMapping("/api/public/book/{slug}")
    public Map<String, Object> page(@PathVariable String slug, @RequestParam(required = false) LocalDate date,
                                    @RequestParam(defaultValue = "2") int party) {
        Map<String, Object> b = business(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", Map.of("name", b.get("name"), "phone", b.get("phone") == null ? "" : b.get("phone"),
                "brandColor", b.get("brand_color") == null ? "" : b.get("brand_color"), "logoUrl", b.get("logo_url") == null ? "" : b.get("logo_url")));
        as(b, () -> {
            Reservations.Rules r = res.rules((UUID) b.get("id"));
            out.put("rules", res.rulesView(r));
            LocalDate d = date == null ? LocalDate.now(Reservations.NAIROBI) : date;
            out.put("date", d.toString());
            out.put("times", r.online() ? res.times(d, Math.max(1, Math.min(party, r.maxParty()))) : List.of());
            out.put("deposit", r.depositFor(Math.max(1, party), d));
            return null;
        });
        return out;
    }

    /** {name, phone, email, party, date, time, note}: books a table; asks for the deposit by M-Pesa if the rule applies. */
    @PostMapping("/api/public/book/{slug}")
    public Map<String, Object> bookOnline(@PathVariable String slug, @RequestBody Map<String, Object> raw, HttpServletRequest req) {
        String client = ClientIp.of(req);
        Map<String, Object> b = business(slug);
        if (!BOOKINGS.allow(b.get("id") + ":" + client)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Several bookings from here just now. Please call the restaurant.");
        }
        Body body = new Body(raw);
        Map<String, Object> v = as(b, () -> res.book(body, "ONLINE"));
        Map<String, Object> out = new LinkedHashMap<>(v);
        if ("HELD".equals(v.get("status"))) {
            try {
                Map<String, Object> pay = TenantContext.runAs((UUID) b.get("id"), null,
                        () -> mpesa.startReservationDeposit(UUID.fromString(v.get("id").toString()), body.str("phone", "")));
                out.put("payment", Map.of("status", pay.get("status")));
            } catch (ApiException e) {
                out.put("payment", Map.of("status", "FAILED", "message", e.getMessage()));
            }
        }
        out.remove("id");
        return out;
    }

    /** The guest's booking page: status, deposit, place in the queue. */
    @GetMapping("/api/public/book/{slug}/r/{token}")
    public Map<String, Object> mine(@PathVariable String slug, @PathVariable String token) {
        Map<String, Object> b = business(slug);
        Map<String, Object> v = as(b, () -> {
            res.sweep();
            UUID id = res.idOfToken(token);
            if (id == null) {
                throw ApiException.notFound("Booking");
            }
            return res.view(id);
        });
        List<Map<String, Object>> p = jdbc.queryForList("""
                SELECT status, result_desc FROM mpesa_requests WHERE reservation_id = cast(? as uuid) ORDER BY created_at DESC LIMIT 1""", v.get("id").toString());
        Map<String, Object> out = new LinkedHashMap<>(v);
        out.remove("id");
        if (!p.isEmpty()) {
            out.put("payment", Map.of("status", p.get(0).get("status"), "message", p.get(0).get("result_desc") == null ? "" : p.get(0).get("result_desc")));
        }
        out.put("business", Map.of("name", b.get("name"), "phone", b.get("phone") == null ? "" : b.get("phone"),
                "brandColor", b.get("brand_color") == null ? "" : b.get("brand_color")));
        return out;
    }

    @PostMapping("/api/public/book/{slug}/r/{token}/cancel")
    public Map<String, Object> cancel(@PathVariable String slug, @PathVariable String token) {
        Map<String, Object> b = business(slug);
        return as(b, () -> {
            UUID id = res.idOfToken(token);
            if (id == null) {
                throw ApiException.notFound("Booking");
            }
            Map<String, Object> v = res.close(id, "CANCELLED", "Cancelled by the guest");
            v.remove("id");
            return v;
        });
    }

    /** Try the deposit again (cancelled prompt, or another phone): {phone}. */
    @PostMapping("/api/public/book/{slug}/r/{token}/pay")
    public Map<String, Object> pay(@PathVariable String slug, @PathVariable String token, @RequestBody Map<String, Object> raw) {
        if (!DEPOSIT.allow(token)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Please wait a little before trying again.");
        }
        Map<String, Object> b = business(slug);
        UUID id = as(b, () -> res.idOfToken(token));
        if (id == null) {
            throw ApiException.notFound("Booking");
        }
        Map<String, Object> pay = TenantContext.runAs((UUID) b.get("id"), null, () -> mpesa.startReservationDeposit(id, new Body(raw).str("phone", "")));
        return Map.of("status", pay.get("status"));
    }

    // ---------------- after a deposit, and reminders ----------------

    /** The deposit is paid: the booking is confirmed; email the guest. */
    @EventListener
    public void onDeposit(MpesaService.ReservationDepositSettled e) {
        try {
            TenantContext.runAs(e.tenant(), null, () -> tx.execute(s -> {
                res.confirmEmail(e.reservationId());
                return null;
            }));
        } catch (RuntimeException ex) {
            log.warn("Confirming booking {}: {}", e.reservationId(), ex.toString());
        }
    }

    /** Every 5 minutes: free tables nobody came for, and email reminders a few hours before. */
    @Scheduled(fixedDelayString = "${suite.bookings.every-ms:300000}", initialDelay = 60_000)
    public void jobs() {
        List<UUID> tenants = jdbc.queryForList("""
                SELECT id FROM tenants WHERE status <> 'SUSPENDED' AND sector IN ('restaurant', 'hotel')""", UUID.class);
        for (UUID t : tenants) {
            try {
                TenantContext.runAs(t, null, () -> tx.execute(s -> {
                    res.sweep();
                    int hours = res.rules(t).reminderHours();
                    if (hours <= 0) {
                        return null;
                    }
                    List<UUID> due = jdbc.queryForList("""
                            UPDATE reservations SET reminded_at = now()
                            WHERE tenant_id = ? AND status = 'BOOKED' AND reminded_at IS NULL AND email IS NOT NULL
                              AND starts_at > now() AND starts_at <= now() + make_interval(hours => ?)
                              AND created_at < starts_at - interval '1 hour'
                            RETURNING id""", UUID.class, t, hours);
                    for (UUID id : due) {
                        res.reminderEmail(id);
                    }
                    return null;
                }));
            } catch (RuntimeException e) {
                log.warn("Booking jobs for {}: {}", t, e.toString());
            }
        }
    }
}
