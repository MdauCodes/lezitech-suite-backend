package com.lezitech.suite.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.notify.Reminders;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Salons (and anyone with appointments) take bookings online: the client picks a service, a stylist or "anyone", a
 * day and a free time. Free times come from each stylist's working hours (else the business's opening hours) minus
 * what they're already booked for. With a deposit the time is held until the M-Pesa prompt is paid. Clients get a
 * confirmation and reminders (email now, SMS or WhatsApp later); online bookings ring on staff screens until seen.
 *
 * Settings live in {@code settings.appointments}; staff hours in {@code users.work_hours}.
 */
@Service
@Transactional
public class OnlineAppointments {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    /** A deposit prompt holds the time for this long. */
    static final int HOLD_MINUTES = 15;
    static final String BUSY = "(a.status IN ('BOOKED', 'ARRIVED') OR (a.status = 'HELD' AND a.hold_until > now()))";

    public record Rules(boolean online, int stepMinutes, int noticeMinutes, int daysAhead, BigDecimal deposit, int cancelHours,
                        List<Integer> reminderHours, Map<String, List<String>> hours) {
    }

    private final Db db;
    private final ObjectMapper json;
    private final Mailer mailer;
    private final Reminders reminders;

    public OnlineAppointments(Db db, ObjectMapper json, Mailer mailer, Reminders reminders) {
        this.db = db;
        this.json = json;
        this.mailer = mailer;
        this.reminders = reminders;
    }

    // ---------------- rules ----------------

    public Rules rules() {
        Object s = db.one("Business", "SELECT coalesce(settings->'appointments', '{}'::jsonb) AS rules FROM tenants WHERE id = :t").get("rules");
        JsonNode node = s instanceof JsonNode n ? n : json.createObjectNode();
        Map<String, List<String>> hours = new LinkedHashMap<>();
        for (String d : DAYS) {
            JsonNode h = node.path("hours").path(d);
            if (h.isArray() && h.size() == 2) {
                hours.put(d, List.of(h.get(0).asText(), h.get(1).asText()));
            } else if (h.isNull()) {
                hours.put(d, null);
            } else {
                hours.put(d, "sun".equals(d) ? List.of("10:00", "17:00") : List.of("08:00", "19:00"));
            }
        }
        List<Integer> remind = new ArrayList<>();
        if (node.path("reminderHours").isArray()) {
            node.path("reminderHours").forEach(n -> remind.add(n.asInt()));
        } else {
            remind.addAll(List.of(24, 2));
        }
        return new Rules(node.path("online").asBoolean(true), clamp(node.path("stepMinutes").asInt(15), 5, 120),
                clamp(node.path("noticeMinutes").asInt(60), 0, 7 * 24 * 60), clamp(node.path("daysAhead").asInt(30), 1, 180),
                node.path("deposit").isNumber() ? node.path("deposit").decimalValue().max(BigDecimal.ZERO) : BigDecimal.ZERO,
                clamp(node.path("cancelHours").asInt(2), 0, 72), remind, hours);
    }

    public Map<String, Object> rulesView(Rules r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("online", r.online());
        m.put("stepMinutes", r.stepMinutes());
        m.put("noticeMinutes", r.noticeMinutes());
        m.put("daysAhead", r.daysAhead());
        m.put("deposit", r.deposit());
        m.put("cancelHours", r.cancelHours());
        m.put("reminderHours", r.reminderHours());
        m.put("hours", r.hours());
        return m;
    }

    public void saveRules(Body b) {
        Map<String, Object> m = new LinkedHashMap<>();
        Rules cur = rules();
        m.put("online", b.has("online") ? b.bool("online", true) : cur.online());
        m.put("stepMinutes", clamp(b.integer("stepMinutes", cur.stepMinutes()), 5, 120));
        m.put("noticeMinutes", clamp(b.integer("noticeMinutes", cur.noticeMinutes()), 0, 7 * 24 * 60));
        m.put("daysAhead", clamp(b.integer("daysAhead", cur.daysAhead()), 1, 180));
        BigDecimal dep = b.num("deposit", cur.deposit());
        if (dep.signum() < 0 || dep.compareTo(BigDecimal.valueOf(100_000)) > 0) {
            throw ApiException.bad("A deposit is between 0 and 100,000");
        }
        m.put("deposit", dep);
        m.put("cancelHours", clamp(b.integer("cancelHours", cur.cancelHours()), 0, 72));
        List<Integer> remind = new ArrayList<>();
        Object raw = b.raw("reminderHours");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                int h = Integer.parseInt(String.valueOf(o));
                if (h < 1 || h > 72) {
                    throw ApiException.bad("Reminders go between 1 and 72 hours before");
                }
                if (!remind.contains(h)) {
                    remind.add(h);
                }
            }
        } else {
            remind.addAll(cur.reminderHours());
        }
        m.put("reminderHours", remind);
        m.put("hours", b.has("hours") ? cleanHours(b.raw("hours")) : cur.hours());
        db.exec("UPDATE tenants SET settings = settings || jsonb_build_object('appointments', cast(:v as jsonb)) WHERE id = :t", "v", db.toJson(m));
    }

    /** {"mon": ["09:00","18:00"], "sun": null}: days missing are closed; times are checked. */
    Map<String, List<String>> cleanHours(Object raw) {
        if (!(raw instanceof Map<?, ?> in)) {
            throw ApiException.bad("Working hours are missing");
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String d : DAYS) {
            Object v = in.get(d);
            if (v instanceof List<?> l && l.size() == 2) {
                LocalTime a = time(String.valueOf(l.get(0)));
                LocalTime z = time(String.valueOf(l.get(1)));
                if (!z.isAfter(a)) {
                    throw ApiException.bad("On " + d + ", the finishing time must be after the starting time");
                }
                out.put(d, List.of(a.format(HM), z.format(HM)));
            } else {
                out.put(d, null);
            }
        }
        return out;
    }

    /** A staff member's own week, or null to follow the business's hours. */
    public void setStaffHours(UUID userId, Object raw) {
        TenantContext.requireRole("MANAGER");
        if (db.value("SELECT count(*) FROM users WHERE id = :id AND tenant_id = :t", Long.class, "id", userId) == 0) {
            throw ApiException.notFound("Staff member");
        }
        db.exec("UPDATE users SET work_hours = cast(:h as jsonb) WHERE id = :id AND tenant_id = :t",
                "id", userId, "h", raw == null ? null : db.toJson(cleanHours(raw)));
    }

    // ---------------- free times ----------------

    /** The people a client can book: stylists, plus owners or managers who set their own hours. */
    public List<Map<String, Object>> bookableStaff() {
        List<Map<String, Object>> staff = db.list("""
                SELECT id, name, role, work_hours FROM users WHERE tenant_id = :t AND active
                  AND (role = 'STYLIST' OR (role IN ('OWNER', 'MANAGER', 'SALES') AND work_hours IS NOT NULL))
                ORDER BY name""");
        if (staff.isEmpty()) {
            staff = db.list("""
                    SELECT id, name, role, work_hours FROM users WHERE tenant_id = :t AND active
                      AND role IN ('STYLIST', 'OWNER', 'MANAGER', 'SALES') ORDER BY role = 'OWNER', name""");
        }
        return staff;
    }

    public Map<String, Object> service(UUID itemId) {
        return db.find("""
                SELECT id, name, price, coalesce(duration_min, 60) AS duration_min FROM items
                WHERE id = :id AND active AND kind = 'SERVICE'""", "id", itemId).orElseThrow(() -> ApiException.notFound("Service"));
    }

    public List<Map<String, Object>> services() {
        return db.list("""
                SELECT i.id, i.name, i.price, coalesce(i.duration_min, 60) AS duration_min, c.name AS category
                FROM items i LEFT JOIN categories c ON c.id = i.category_id
                WHERE i.active AND i.kind = 'SERVICE' AND i.price > 0 ORDER BY c.name NULLS LAST, i.name""");
    }

    /** Free start times on a day for a service, with who is free at each. */
    public List<Map<String, Object>> times(LocalDate day, UUID itemId, UUID staffId) {
        Rules r = rules();
        LocalDate today = LocalDate.now(NAIROBI);
        if (day.isBefore(today) || day.isAfter(today.plusDays(r.daysAhead()))) {
            return List.of();
        }
        int minutes = ((Number) service(itemId).get("durationMin")).intValue();
        Instant earliest = Instant.now().plus(r.noticeMinutes(), ChronoUnit.MINUTES);
        String dayKey = DAYS.get(day.getDayOfWeek().getValue() - 1);
        Map<LocalTime, Set<String>> free = new java.util.TreeMap<>();
        for (Map<String, Object> s : bookableStaff()) {
            if (staffId != null && !staffId.toString().equals(s.get("id").toString())) {
                continue;
            }
            List<String> h = hoursOf(s, dayKey, r);
            if (h == null) {
                continue;
            }
            LocalTime open = time(h.get(0));
            LocalTime close = time(h.get(1));
            List<Map<String, Object>> taken = db.list("SELECT starts_at, ends_at FROM appointments a WHERE a.staff_id = :s AND "
                    + BUSY + " AND a.starts_at < :to AND a.ends_at > :from",
                    "s", UUID.fromString(s.get("id").toString()), "from", ts(day, LocalTime.MIN), "to", ts(day.plusDays(1), LocalTime.MIN));
            for (LocalTime t = open; !t.plusMinutes(minutes).isAfter(close) && !t.plusMinutes(minutes).equals(LocalTime.MIN); t = t.plusMinutes(r.stepMinutes())) {
                Instant a = day.atTime(t).atZone(NAIROBI).toInstant();
                Instant z = a.plus(minutes, ChronoUnit.MINUTES);
                if (a.isBefore(earliest)) {
                    continue;
                }
                boolean clash = taken.stream().anyMatch(x -> Instant.parse(x.get("startsAt").toString()).isBefore(z)
                        && Instant.parse(x.get("endsAt").toString()).isAfter(a));
                if (!clash) {
                    free.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(s.get("id").toString());
                }
                if (t.plusMinutes(r.stepMinutes()).isBefore(t)) {
                    break; // wrapped past midnight
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        free.forEach((t, who) -> out.add(Map.of("time", t.format(HM), "staffIds", List.copyOf(who))));
        return out;
    }

    private List<String> hoursOf(Map<String, Object> staff, String dayKey, Rules r) {
        Object wh = staff.get("workHours");
        if (wh instanceof JsonNode n && n.isObject()) {
            JsonNode h = n.path(dayKey);
            return h.isArray() && h.size() == 2 ? List.of(h.get(0).asText(), h.get(1).asText()) : null;
        }
        return r.hours().get(dayKey);
    }

    // ---------------- booking ----------------

    /**
     * {itemId, staffId (optional: anyone), date, time, name, phone, email, note}. Online bookings need a free time from
     * {@link #times}; with a deposit they're HELD until paid.
     */
    public Map<String, Object> book(Body b) {
        Rules r = rules();
        if (!r.online()) {
            throw ApiException.forbidden("Online booking is switched off. Please call us to book.");
        }
        UUID itemId = parse(b.str("itemId", null), "Choose a service");
        Map<String, Object> svc = service(itemId);
        LocalDate day = b.date("date");
        LocalTime t = time(b.str("time", ""));
        String name = clip(b.str("name", null), 120);
        String phone = clip(b.str("phone", null), 20);
        String email = b.str("email", null);
        if (name == null || name.isBlank()) {
            throw ApiException.bad("Add your name");
        }
        if ((phone == null || phone.isBlank()) && (email == null || email.isBlank())) {
            throw ApiException.bad("Add a phone number or email so we can reach you");
        }
        if (email != null && !email.isBlank() && !email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.bad("Check the email address");
        }
        UUID wanted = b.has("staffId") ? parse(b.str("staffId"), "That stylist") : null;
        String time = t.format(HM);
        List<String> who = times(day, itemId, wanted).stream().filter(x -> time.equals(x.get("time"))).findFirst()
                .map(x -> (List<String>) x.get("staffIds")).orElse(List.of());
        if (who.isEmpty()) {
            throw ApiException.conflict("That time has just been taken. Please choose another.");
        }
        UUID staff = UUID.fromString(who.get(0));
        // Two clients pressing Book at the same moment: one wins, the other is told to choose again.
        db.list("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtext(cast(:s as text)))) x", "s", staff.toString());
        Instant start = day.atTime(t).atZone(NAIROBI).toInstant();
        Instant end = start.plus(((Number) svc.get("durationMin")).intValue(), ChronoUnit.MINUTES);
        if (db.value("SELECT count(*) FROM appointments a WHERE a.staff_id = :s AND " + BUSY + " AND a.starts_at < :end AND a.ends_at > :start",
                Long.class, "s", staff, "start", Timestamp.from(start), "end", Timestamp.from(end)) > 0) {
            throw ApiException.conflict("That time has just been taken. Please choose another.");
        }
        UUID customer = phone == null ? null : db.value("SELECT id FROM customers WHERE phone = :p LIMIT 1", UUID.class, "p", phone);
        if (customer == null) {
            customer = db.insert("INSERT INTO customers (tenant_id, name, phone, email) VALUES (:t, :n, :p, :e) RETURNING id",
                    "n", name.trim(), "p", phone, "e", email == null || email.isBlank() ? null : email.trim().toLowerCase());
        }
        boolean deposit = r.deposit().signum() > 0;
        UUID id = db.insert("""
                INSERT INTO appointments (tenant_id, customer_id, customer_name, phone, email, item_id, staff_id, starts_at, ends_at, notes,
                                          status, source, deposit_amount, deposit_status, hold_until)
                VALUES (:t, :c, :n, :p, :e, :item, :staff, :start, :end, :note, :status, 'ONLINE', :dep, :depStatus, :hold) RETURNING id""",
                "c", customer, "n", name.trim(), "p", phone, "e", email == null || email.isBlank() ? null : email.trim().toLowerCase(),
                "item", itemId, "staff", staff, "start", Timestamp.from(start), "end", Timestamp.from(end), "note", clip(b.str("note", null), 300),
                "status", deposit ? "HELD" : "BOOKED", "dep", r.deposit(), "depStatus", deposit ? "WAITING" : "NONE",
                "hold", deposit ? Timestamp.from(Instant.now().plus(HOLD_MINUTES, ChronoUnit.MINUTES)) : null);
        if (!deposit) {
            confirm(id);
        }
        return view(id);
    }

    /** Free times again for anything HELD whose deposit wasn't paid in time. */
    public int sweep() {
        return db.exec("""
                UPDATE appointments SET status = 'CANCELLED', cancel_reason = 'Deposit not paid in time'
                WHERE status = 'HELD' AND hold_until < now()""");
    }

    public UUID idOfToken(String token) {
        return db.value("SELECT id FROM appointments WHERE token = :tk", UUID.class, "tk", token);
    }

    /** The client's view: no internal ids beyond what they need. */
    public Map<String, Object> view(UUID id) {
        Map<String, Object> a = db.one("Booking", """
                SELECT a.id, a.token, a.status, a.customer_name, a.starts_at, a.ends_at, a.deposit_amount, a.deposit_status, a.hold_until,
                       a.cancel_reason, i.name AS service, i.price, u.name AS staff
                FROM appointments a LEFT JOIN items i ON i.id = a.item_id LEFT JOIN users u ON u.id = a.staff_id AND u.tenant_id = a.tenant_id WHERE a.id = :id""", "id", id);
        Map<String, Object> out = new LinkedHashMap<>(a);
        Instant start = Instant.parse(a.get("startsAt").toString());
        out.put("day", start.atZone(NAIROBI).format(DAY));
        out.put("time", start.atZone(NAIROBI).format(HM));
        out.put("canCancel", List.of("BOOKED", "HELD").contains(a.get("status"))
                && Instant.now().plus(rules().cancelHours(), ChronoUnit.HOURS).isBefore(start));
        return out;
    }

    /** The client cancels from their link (up to the business's cut-off). */
    public Map<String, Object> cancelByClient(UUID id) {
        Map<String, Object> v = view(id);
        if (!Boolean.TRUE.equals(v.get("canCancel"))) {
            throw ApiException.conflict("It's too close to your appointment to cancel online. Please call us.");
        }
        db.exec("UPDATE appointments SET status = 'CANCELLED', cancel_reason = 'Cancelled by the client' WHERE id = :id", "id", id);
        return view(id);
    }

    // ---------------- staff ----------------

    /** Online bookings nobody has looked at yet. */
    public Map<String, Object> alerts() {
        List<Map<String, Object>> list = db.list("""
                SELECT a.id, a.customer_name, a.starts_at, i.name AS service, u.name AS staff, a.status, a.created_at
                FROM appointments a LEFT JOIN items i ON i.id = a.item_id LEFT JOIN users u ON u.id = a.staff_id AND u.tenant_id = a.tenant_id
                WHERE a.source = 'ONLINE' AND a.acknowledged_at IS NULL AND a.status IN ('BOOKED', 'HELD')
                ORDER BY a.created_at""");
        return Map.of("count", list.size(), "bookings", list);
    }

    public int acknowledge() {
        return db.exec("UPDATE appointments SET acknowledged_at = now() WHERE source = 'ONLINE' AND acknowledged_at IS NULL");
    }

    // ---------------- emails and reminders ----------------

    /** Booked (or deposit paid): tell the client. */
    public void confirm(UUID id) {
        Map<String, Object> a = contact(id);
        if (a == null) {
            return;
        }
        Map<String, Object> v = view(id);
        Map<String, Object> biz = biz();
        String link = link(biz, (String) v.get("token"));
        String when = v.get("day") + ", " + v.get("time");
        List<String[]> facts = new ArrayList<>(List.of(new String[]{"When", when}, new String[]{"Service", String.valueOf(v.get("service"))},
                new String[]{"With", String.valueOf(v.get("staff"))}));
        BigDecimal dep = (BigDecimal) v.get("depositAmount");
        if ("PAID".equals(v.get("depositStatus")) && dep.signum() > 0) {
            facts.add(new String[]{"Deposit paid", "KES " + dep.stripTrailingZeros().toPlainString() + " (comes off your bill)"});
        }
        reminders.sendOnce("APPOINTMENT", id, "CONFIRM", contactOf(a),
                new Reminders.Message("Booked: " + v.get("service") + " at " + biz.get("name") + ", " + when,
                        "Hi " + first((String) a.get("customerName")) + ",\n\nYou're booked for " + v.get("service") + " with " + v.get("staff")
                                + " on " + when + ".\nSee or cancel your booking: " + link + "\n\n" + biz.get("name"),
                        EmailLayout.h("You're booked", null)
                                + EmailLayout.p("Hi " + Mailer.esc(first((String) a.get("customerName"))) + ", see you soon.")
                                + EmailLayout.facts(facts.toArray(new String[0][]))
                                + EmailLayout.button("See or cancel your booking", link, (String) biz.get("brandColor"))));
    }

    /** A reminder before the appointment ({@code kind}: BEFORE_24H, BEFORE_2H, or MANUAL-...). */
    public String remind(UUID id, String kind) {
        Map<String, Object> a = contact(id);
        if (a == null) {
            return "NO_CONTACT";
        }
        Map<String, Object> v = view(id);
        Map<String, Object> biz = biz();
        String link = link(biz, (String) v.get("token"));
        String when = v.get("day") + " at " + v.get("time");
        return reminders.sendOnce("APPOINTMENT", id, kind, contactOf(a),
                new Reminders.Message("Reminder: " + v.get("service") + " at " + biz.get("name") + ", " + when,
                        "Hi " + first((String) a.get("customerName")) + ",\n\nA reminder: " + v.get("service") + " with " + v.get("staff") + ", " + when
                                + ".\nCan't make it? Please cancel so someone else can have the time: " + link + "\n\n" + biz.get("name"),
                        EmailLayout.h("See you soon", null)
                                + EmailLayout.p("Hi " + Mailer.esc(first((String) a.get("customerName"))) + ", a reminder of your appointment.")
                                + EmailLayout.facts(new String[][]{{"When", when}, {"Service", String.valueOf(v.get("service"))}, {"With", String.valueOf(v.get("staff"))}})
                                + EmailLayout.p("Can't make it? Please cancel so someone else can have the time.")
                                + EmailLayout.button("See or cancel your booking", link, (String) biz.get("brandColor"))));
    }

    /** Due reminders for this business: each booked appointment gets each reminder once, if it was booked early enough. */
    public int sendDueReminders() {
        int sent = 0;
        for (int hours : rules().reminderHours()) {
            List<UUID> due = db.list("""
                    SELECT a.id FROM appointments a
                    WHERE a.status = 'BOOKED' AND (a.email IS NOT NULL OR a.phone IS NOT NULL)
                      AND a.starts_at > now() AND a.starts_at <= now() + make_interval(hours => :h)
                      AND a.created_at < a.starts_at - make_interval(hours => :h) + interval '30 minutes'
                      AND NOT EXISTS (SELECT 1 FROM reminders x WHERE x.ref_kind = 'APPOINTMENT' AND x.ref_id = a.id AND x.kind = :k)""",
                    "h", hours, "k", "BEFORE_" + hours + "H").stream().map(m -> UUID.fromString(m.get("id").toString())).toList();
            for (UUID id : due) {
                if ("SENT".equals(remind(id, "BEFORE_" + hours + "H"))) {
                    sent++;
                }
            }
        }
        return sent;
    }

    private Map<String, Object> contact(UUID id) {
        Map<String, Object> a = db.one("Booking", "SELECT customer_name, email, phone FROM appointments WHERE id = :id", "id", id);
        return a.get("email") == null && a.get("phone") == null ? null : a;
    }

    private static Reminders.Contact contactOf(Map<String, Object> a) {
        return new Reminders.Contact((String) a.get("customerName"), (String) a.get("email"), (String) a.get("phone"));
    }

    Map<String, Object> biz() {
        return db.one("Business", "SELECT name, slug, brand_color AS \"brandColor\" FROM tenants WHERE id = :t");
    }

    String link(Map<String, Object> biz, String token) {
        return mailer.appUrl() + "/book/" + biz.get("slug") + "/a/" + token;
    }

    // ---------------- helpers ----------------

    private static Timestamp ts(LocalDate d, LocalTime t) {
        return Timestamp.from(d.atTime(t).atZone(NAIROBI).toInstant());
    }

    static LocalTime time(String s) {
        try {
            return LocalTime.parse(s.length() == 4 ? "0" + s : s);
        } catch (RuntimeException e) {
            throw ApiException.bad("Choose a time like 09:30");
        }
    }

    private static UUID parse(String s, String what) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw ApiException.bad(what);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String first(String name) {
        return name == null ? "" : name.trim().split("\\s+")[0];
    }

    static DayOfWeek dayOf(String key) {
        return DayOfWeek.of(DAYS.indexOf(key) + 1);
    }
}
