package com.lezitech.suite.booking;

import com.lezitech.suite.common.ClientIp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.mpesa.MpesaService;
import com.lezitech.suite.publicapi.RateLimit;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
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
 * The client's side of salon bookings: the booking page at /book/{slug} (for businesses that take appointments),
 * their own booking page at /book/{slug}/a/{token}, the deposit, and the reminder job.
 */
@RestController
public class AppointmentPublicController {

    private static final Logger log = LoggerFactory.getLogger(AppointmentPublicController.class);
    /** Bookings from one network: 10 an hour. Deposit prompts per booking: 4 per 10 minutes. */
    public static final RateLimit BOOKINGS = new RateLimit(10, 3_600_000);
    public static final RateLimit DEPOSIT = new RateLimit(4, 600_000);

    private final OnlineAppointments appts;
    private final MpesaService mpesa;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SectorCatalog sectors;
    private final ObjectMapper json;

    public AppointmentPublicController(OnlineAppointments appts, MpesaService mpesa, JdbcTemplate jdbc, PlatformTransactionManager txm,
                                       SectorCatalog sectors, ObjectMapper json) {
        this.appts = appts;
        this.mpesa = mpesa;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.sectors = sectors;
        this.json = json;
    }

    /** The business, only if it takes appointments (else the /book link is a restaurant's table booking). */
    private Map<String, Object> business(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, phone, brand_color, logo_url, status, sector, sub_sector, settings::text AS settings FROM tenants WHERE slug = ?""", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status")) || !takesAppointments(rows.get(0))) {
            throw ApiException.notFound("Business");
        }
        return rows.get(0);
    }

    boolean takesAppointments(Map<String, Object> t) {
        try {
            JsonNode own = json.readTree((String) t.get("settings")).path("features").path("appointments");
            if (own.isBoolean()) {
                return own.asBoolean();
            }
        } catch (Exception ignored) {
            // settings are always JSON
        }
        return sectors.get((String) t.get("sector"), (String) t.get("sub_sector")).path("features").path("appointments").asBoolean(false);
    }

    private <T> T as(Map<String, Object> b, Supplier<T> work) {
        return TenantContext.runAs((UUID) b.get("id"), null, () -> tx.execute(x -> work.get()));
    }

    private static Map<String, Object> brand(Map<String, Object> b) {
        return Map.of("name", b.get("name"), "phone", b.get("phone") == null ? "" : b.get("phone"),
                "brandColor", b.get("brand_color") == null ? "" : b.get("brand_color"), "logoUrl", b.get("logo_url") == null ? "" : b.get("logo_url"));
    }

    /** Services, stylists, the rules, and free times for a day once a service is chosen. */
    @GetMapping("/api/public/appointments/{slug}")
    public Map<String, Object> page(@PathVariable String slug, @RequestParam(required = false) LocalDate date,
                                    @RequestParam(required = false) UUID itemId, @RequestParam(required = false) UUID staffId) {
        Map<String, Object> b = business(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", brand(b));
        as(b, () -> {
            appts.sweep();
            OnlineAppointments.Rules r = appts.rules();
            out.put("rules", Map.of("online", r.online(), "deposit", r.deposit(), "daysAhead", r.daysAhead(), "cancelHours", r.cancelHours()));
            out.put("services", appts.services());
            out.put("staff", appts.bookableStaff().stream().map(s -> Map.of("id", s.get("id"), "name", s.get("name"))).toList());
            LocalDate d = date == null ? LocalDate.now(OnlineAppointments.NAIROBI) : date;
            out.put("date", d.toString());
            out.put("times", r.online() && itemId != null ? appts.times(d, itemId, staffId) : List.of());
            return null;
        });
        return out;
    }

    /** {itemId, staffId, date, time, name, phone, email, note}: books; asks for the deposit by M-Pesa if the business wants one. */
    @PostMapping("/api/public/appointments/{slug}")
    public Map<String, Object> book(@PathVariable String slug, @RequestBody Map<String, Object> raw, HttpServletRequest req) {
        String client = ClientIp.of(req);
        Map<String, Object> b = business(slug);
        if (!BOOKINGS.allow(b.get("id") + ":" + client)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Several bookings from here just now. Please call us.");
        }
        Body body = new Body(raw);
        Map<String, Object> v = as(b, () -> appts.book(body));
        Map<String, Object> out = new LinkedHashMap<>(v);
        if ("HELD".equals(v.get("status"))) {
            try {
                Map<String, Object> pay = TenantContext.runAs((UUID) b.get("id"), null,
                        () -> mpesa.startAppointmentDeposit(UUID.fromString(v.get("id").toString()), body.str("phone", "")));
                out.put("payment", Map.of("status", pay.get("status")));
            } catch (ApiException e) {
                out.put("payment", Map.of("status", "FAILED", "message", e.getMessage()));
            }
        }
        out.remove("id");
        return out;
    }

    @GetMapping("/api/public/appointments/{slug}/a/{token}")
    public Map<String, Object> mine(@PathVariable String slug, @PathVariable String token) {
        Map<String, Object> b = business(slug);
        Map<String, Object> v = as(b, () -> {
            appts.sweep();
            UUID id = appts.idOfToken(token);
            if (id == null) {
                throw ApiException.notFound("Booking");
            }
            return appts.view(id);
        });
        Map<String, Object> out = new LinkedHashMap<>(v);
        List<Map<String, Object>> p = jdbc.queryForList("""
                SELECT status, result_desc FROM mpesa_requests WHERE appointment_id = cast(? as uuid) ORDER BY created_at DESC LIMIT 1""", v.get("id").toString());
        if (!p.isEmpty()) {
            out.put("payment", Map.of("status", p.get(0).get("status"), "message", p.get(0).get("result_desc") == null ? "" : p.get(0).get("result_desc")));
        }
        out.remove("id");
        out.put("business", brand(b));
        return out;
    }

    @PostMapping("/api/public/appointments/{slug}/a/{token}/cancel")
    public Map<String, Object> cancel(@PathVariable String slug, @PathVariable String token) {
        Map<String, Object> b = business(slug);
        return as(b, () -> {
            UUID id = appts.idOfToken(token);
            if (id == null) {
                throw ApiException.notFound("Booking");
            }
            Map<String, Object> v = new LinkedHashMap<>(appts.cancelByClient(id));
            v.remove("id");
            return v;
        });
    }

    /** Try the deposit again (cancelled prompt, or another phone): {phone}. */
    @PostMapping("/api/public/appointments/{slug}/a/{token}/pay")
    public Map<String, Object> pay(@PathVariable String slug, @PathVariable String token, @RequestBody Map<String, Object> raw) {
        if (!DEPOSIT.allow(token)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Please wait a little before trying again.");
        }
        Map<String, Object> b = business(slug);
        UUID id = as(b, () -> appts.idOfToken(token));
        if (id == null) {
            throw ApiException.notFound("Booking");
        }
        Map<String, Object> pay = TenantContext.runAs((UUID) b.get("id"), null, () -> mpesa.startAppointmentDeposit(id, new Body(raw).str("phone", "")));
        return Map.of("status", pay.get("status"));
    }

    /** The deposit is paid: confirm to the client. */
    @EventListener
    public void onDeposit(MpesaService.AppointmentDepositSettled e) {
        try {
            TenantContext.runAs(e.tenant(), null, () -> tx.execute(s -> {
                appts.confirm(e.appointmentId());
                return null;
            }));
        } catch (RuntimeException ex) {
            log.warn("Confirming appointment {}: {}", e.appointmentId(), ex.toString());
        }
    }

    /** Every 5 minutes: free unpaid holds, and send due reminders, for businesses that have appointments. */
    @Scheduled(fixedDelayString = "${suite.appointments.every-ms:300000}", initialDelay = 90_000)
    public void jobs() {
        // tenants has no row-level security; appointments do, so pick businesses here and look inside each one.
        List<UUID> tenants = jdbc.queryForList("""
                SELECT id FROM tenants WHERE status <> 'SUSPENDED'
                  AND (sector IN ('beauty', 'services') OR settings->'features'->>'appointments' = 'true')""", UUID.class);
        for (UUID t : tenants) {
            try {
                TenantContext.runAs(t, null, () -> tx.execute(s -> {
                    appts.sweep();
                    appts.sendDueReminders();
                    return null;
                }));
            } catch (RuntimeException e) {
                log.warn("Appointment jobs for {}: {}", t, e.toString());
            }
        }
    }
}
