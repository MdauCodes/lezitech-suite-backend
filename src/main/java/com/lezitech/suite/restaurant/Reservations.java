package com.lezitech.suite.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Restaurants, package R4: table bookings and the waiting list. A booking from the restaurant's link gets a table at
 * once if one fits (a deposit first when the owner's rule asks for one) and rings on the staff screens until someone
 * acknowledges it. Guests who haven't come 15 minutes after their time free the table; any deposit is kept.
 */
@Service
public class Reservations {

    public static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    /** Statuses that hold a table. */
    static final String HOLDING = "('HELD', 'BOOKED', 'SEATED')";

    /** The owner's booking rules (tenants.settings.bookings), with defaults. */
    public record Rules(boolean online, LocalTime openFrom, LocalTime openTo, int slotMin, int durationMin, int graceMin, int maxParty,
                        int reminderHours, BigDecimal depositPerPerson, int depositFromParty, List<String> depositDays, int holdMin) {
        BigDecimal depositFor(int party, LocalDate day) {
            if (depositPerPerson.signum() <= 0 || party < Math.max(1, depositFromParty)) {
                return BigDecimal.ZERO;
            }
            if (!depositDays.isEmpty() && !depositDays.contains(day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase())) {
                return BigDecimal.ZERO;
            }
            return depositPerPerson.multiply(BigDecimal.valueOf(party));
        }
    }

    private final Db db;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OrderService orders;
    private final Mailer mailer;

    public Reservations(Db db, JdbcTemplate jdbc, ObjectMapper json, OrderService orders, Mailer mailer) {
        this.db = db;
        this.jdbc = jdbc;
        this.json = json;
        this.orders = orders;
        this.mailer = mailer;
    }

    // ---------------- rules ----------------

    public Rules rules(UUID tenant) {
        JsonNode s;
        try {
            s = json.readTree(jdbc.queryForObject("SELECT coalesce(settings->'bookings', '{}'::jsonb)::text FROM tenants WHERE id = ?", String.class, tenant));
        } catch (Exception e) {
            s = json.createObjectNode();
        }
        List<String> days = new ArrayList<>();
        s.path("depositDays").forEach(d -> days.add(d.asText().toUpperCase()));
        return new Rules(s.path("online").asBoolean(true), time(s.path("openFrom").asText("11:00"), "11:00"), time(s.path("openTo").asText("22:00"), "22:00"),
                clamp(s.path("slotMin").asInt(30), 15, 120), clamp(s.path("durationMin").asInt(90), 30, 360), clamp(s.path("graceMin").asInt(15), 5, 120),
                clamp(s.path("maxParty").asInt(12), 1, 200), clamp(s.path("reminderHours").asInt(3), 0, 48),
                s.path("depositPerPerson").isNumber() ? s.path("depositPerPerson").decimalValue() : BigDecimal.ZERO,
                s.path("depositFromParty").asInt(0), days, clamp(s.path("holdMin").asInt(20), 5, 120));
    }

    public Map<String, Object> rulesView(Rules r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", r.online());
        out.put("openFrom", r.openFrom().format(HHMM));
        out.put("openTo", r.openTo().format(HHMM));
        out.put("slotMin", r.slotMin());
        out.put("durationMin", r.durationMin());
        out.put("graceMin", r.graceMin());
        out.put("maxParty", r.maxParty());
        out.put("reminderHours", r.reminderHours());
        out.put("depositPerPerson", r.depositPerPerson());
        out.put("depositFromParty", r.depositFromParty());
        out.put("depositDays", r.depositDays());
        return out;
    }

    /** Save the owner's rules (only the known keys, checked). */
    public void saveRules(Body b) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("online", b.bool("online", true));
        s.put("openFrom", time(b.str("openFrom", "11:00"), null).format(HHMM));
        s.put("openTo", time(b.str("openTo", "22:00"), null).format(HHMM));
        s.put("slotMin", clamp(b.integer("slotMin", 30), 15, 120));
        s.put("durationMin", clamp(b.integer("durationMin", 90), 30, 360));
        s.put("graceMin", clamp(b.integer("graceMin", 15), 5, 120));
        s.put("maxParty", clamp(b.integer("maxParty", 12), 1, 200));
        s.put("reminderHours", clamp(b.integer("reminderHours", 3), 0, 48));
        BigDecimal per = b.num("depositPerPerson", BigDecimal.ZERO);
        if (per.signum() < 0) {
            throw ApiException.bad("A deposit can't be below zero");
        }
        s.put("depositPerPerson", per);
        s.put("depositFromParty", clamp(b.integer("depositFromParty", 0), 0, 200));
        List<String> days = new ArrayList<>();
        if (b.raw("depositDays") instanceof List<?> given) {
            for (Object d : given) {
                String x = String.valueOf(d).toUpperCase();
                if (List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").contains(x)) {
                    days.add(x);
                }
            }
        }
        s.put("depositDays", days);
        jdbc.update("UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{bookings}', cast(? as jsonb)) WHERE id = ?",
                db.toJson(s), TenantContext.tenantId());
    }

    // ---------------- tables and times ----------------

    /** Free up tables: guests who never came, and online holds whose deposit wasn't paid in time. */
    public void sweep() {
        Rules r = rules(TenantContext.tenantId());
        db.exec("UPDATE reservations SET status = 'NO_SHOW' WHERE status = 'BOOKED' AND starts_at + make_interval(mins => :g) < now()", "g", r.graceMin());
        db.exec("""
                UPDATE reservations SET status = 'CANCELLED', cancel_reason = 'The deposit wasn''t paid in time', deposit_status = 'NONE'
                WHERE status = 'HELD' AND hold_until < now()""");
    }

    /** The smallest free table for this many people at this time, or null. */
    public UUID freeTable(int party, Instant start, int minutes, UUID except) {
        return db.value("""
                SELECT t.id FROM dining_tables t
                WHERE t.seats >= :party AND NOT EXISTS (
                    SELECT 1 FROM reservations r WHERE r.table_id = t.id AND r.status IN """ + HOLDING + """
                       AND (cast(:except as uuid) IS NULL OR r.id <> cast(:except as uuid))
                       AND r.starts_at < :end AND r.starts_at + make_interval(mins => r.duration_min) > :start)
                ORDER BY t.seats, t.label LIMIT 1""", UUID.class,
                "party", party, "start", Timestamp.from(start), "end", Timestamp.from(start.plusSeconds(minutes * 60L)),
                "except", except == null ? null : except.toString());
    }

    /** Times on a day a party can book, from opening to the last sitting, not in the past (30 minutes' notice). */
    public List<String> times(LocalDate day, int party) {
        Rules r = rules(TenantContext.tenantId());
        sweep();
        List<String> out = new ArrayList<>();
        Instant soonest = Instant.now().plusSeconds(30 * 60);
        for (LocalTime t = r.openFrom(); !t.plusMinutes(r.durationMin()).isAfter(r.openTo()) && !t.isBefore(r.openFrom()); t = t.plusMinutes(r.slotMin())) {
            Instant at = ZonedDateTime.of(day, t, NAIROBI).toInstant();
            if (!at.isBefore(soonest) && freeTable(party, at, r.durationMin(), null) != null) {
                out.add(t.format(HHMM));
            }
            if (t.plusMinutes(r.slotMin()).isBefore(t)) {
                break;
            }
        }
        return out;
    }

    // ---------------- making a booking ----------------

    /**
     * {name, phone, email, party, date (yyyy-mm-dd), time (HH:mm), note, tableId (staff only)}. Online: a table is
     * found at once; a deposit holds it until paid. Staff: booked straight away (no deposit), on the table they pick.
     */
    public Map<String, Object> book(Body b, String source) {
        UUID tenant = TenantContext.tenantId();
        Rules r = rules(tenant);
        boolean online = "ONLINE".equals(source);
        if (online && !r.online()) {
            throw ApiException.forbidden("This restaurant isn't taking bookings online. Please call them.");
        }
        sweep();
        String name = clip(b.str("name", "").trim(), 80);
        if (name.isEmpty()) {
            throw ApiException.bad("Enter the name for the booking");
        }
        int party = b.integer("party", 0);
        if (party < 1 || party > (online ? r.maxParty() : 200)) {
            throw ApiException.bad(online ? "For more than " + r.maxParty() + " people, please call the restaurant" : "Enter how many people");
        }
        String phone = b.str("phone", "").isBlank() ? null : TableOrderService.phone(b.str("phone"));
        String email = b.str("email", "").isBlank() ? null : b.str("email").trim().toLowerCase();
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.bad("That email doesn't look right");
        }
        if (online && phone == null) {
            throw ApiException.bad("Enter your phone number so the restaurant can reach you");
        }
        LocalDate day;
        LocalTime time;
        try {
            day = LocalDate.parse(b.str("date"));
            time = LocalTime.parse(b.str("time"));
        } catch (RuntimeException e) {
            throw ApiException.bad("Choose a day and a time");
        }
        Instant start = ZonedDateTime.of(day, time, NAIROBI).toInstant();
        if (start.isBefore(Instant.now().minusSeconds(5 * 60))) {
            throw ApiException.bad("That time has already passed");
        }
        if (online && (time.isBefore(r.openFrom()) || time.plusMinutes(r.durationMin()).isAfter(r.openTo()))) {
            throw ApiException.bad("The restaurant takes bookings from " + r.openFrom().format(HHMM) + " to " + r.openTo().minusMinutes(r.durationMin()).format(HHMM));
        }
        UUID table;
        if (!online && b.has("tableId") && !b.str("tableId", "").isBlank()) {
            table = UUID.fromString(b.str("tableId"));
            if (db.value("SELECT count(*) FROM dining_tables WHERE id = :id", Long.class, "id", table) == 0) {
                throw ApiException.notFound("That table");
            }
            UUID clash = db.value("""
                    SELECT id FROM reservations WHERE table_id = :tb AND status IN """ + HOLDING + """
                       AND starts_at < :end AND starts_at + make_interval(mins => duration_min) > :start LIMIT 1""", UUID.class,
                    "tb", table, "start", Timestamp.from(start), "end", Timestamp.from(start.plusSeconds(r.durationMin() * 60L)));
            if (clash != null) {
                throw ApiException.conflict("That table is already booked then. Pick another table or time.");
            }
        } else {
            table = freeTable(party, start, r.durationMin(), null);
            if (table == null) {
                List<String> other = times(day, party);
                throw ApiException.conflict("No table for " + party + " at " + time.format(HHMM)
                        + (other.isEmpty() ? " that day. Try another day." : ". Free times that day: " + String.join(", ", other.subList(0, Math.min(6, other.size())))));
            }
        }
        BigDecimal deposit = online ? r.depositFor(party, day) : BigDecimal.ZERO;
        String status = deposit.signum() > 0 ? "HELD" : "BOOKED";
        UUID id = db.insert("""
                INSERT INTO reservations (tenant_id, source, status, name, phone, email, party, starts_at, duration_min, table_id, note,
                                          deposit_amount, deposit_status, hold_until, created_by, acknowledged_at)
                VALUES (:t, :src, :st, :name, :phone, :email, :party, :start, :dur, :tb, :note, :dep, :ds,
                        CASE WHEN :held THEN now() + make_interval(mins => :hold) END, :u, CASE WHEN :ack THEN now() END) RETURNING id""",
                "src", source, "st", status, "name", name, "phone", phone, "email", email, "party", party, "start", Timestamp.from(start),
                "dur", r.durationMin(), "tb", table, "note", clip(b.str("note", null), 300), "dep", deposit,
                "ds", deposit.signum() > 0 ? "WAITING" : "NONE", "held", deposit.signum() > 0, "hold", r.holdMin(), "ack", !online);
        if ("BOOKED".equals(status)) {
            confirmEmail(id);
        }
        return view(id);
    }

    /** A walk-in joins the waiting list: {name, party, phone, email, note}. Staff add them at the door. */
    public Map<String, Object> waitlist(Body b) {
        String name = clip(b.str("name", "").trim(), 80);
        int party = b.integer("party", 0);
        if (name.isEmpty() || party < 1) {
            throw ApiException.bad("Enter a name and how many people");
        }
        String email = b.str("email", "").isBlank() ? null : b.str("email").trim().toLowerCase();
        UUID id = db.insert("""
                INSERT INTO reservations (tenant_id, source, status, name, phone, email, party, starts_at, note, created_by, acknowledged_at)
                VALUES (:t, 'WALK_IN', 'WAITING', :name, :phone, :email, :party, now(), :note, :u, now()) RETURNING id""",
                "name", name, "phone", b.str("phone", "").isBlank() ? null : TableOrderService.phone(b.str("phone")), "email", email,
                "party", party, "note", clip(b.str("note", null), 300));
        Map<String, Object> v = view(id);
        if (email != null) {
            Map<String, Object> biz = biz();
            String link = link(biz, (String) v.get("token"));
            mailer.trySendAs(TenantContext.tenantId(), email, name, "You're on the waiting list at " + biz.get("name"),
                    "Hi " + first(name) + ",\n\nYou're on the waiting list for a table for " + party + ". We'll email you here when your table is ready."
                            + "\nSee your place in the queue: " + link + "\n\n" + biz.get("name"),
                    EmailLayout.h("You're on the waiting list", null)
                            + EmailLayout.p("Hi " + Mailer.esc(first(name)) + ", you're on the list for a table for <b>" + party + "</b>. We'll email you here the moment your table is ready.")
                            + EmailLayout.button("See your place in the queue", link, (String) biz.get("brandColor")));
        }
        return v;
    }

    // ---------------- the day's list and actions ----------------

    /** The day's bookings, the waiting list, and the tables. */
    public Map<String, Object> day(LocalDate day) {
        sweep();
        Instant from = day.atStartOfDay(NAIROBI).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(NAIROBI).toInstant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookings", db.list("""
                SELECT r.id, r.token, r.source, r.status, r.name, r.phone, r.email, r.party, r.starts_at, r.duration_min, r.table_id,
                       t.label AS table_label, r.note, r.deposit_amount, r.deposit_status, r.deposit_receipt, r.acknowledged_at, r.order_id,
                       r.cancel_reason, r.created_at
                FROM reservations r LEFT JOIN dining_tables t ON t.id = r.table_id
                WHERE r.source <> 'WALK_IN' AND r.starts_at >= :from AND r.starts_at < :to
                ORDER BY r.starts_at, r.created_at""", "from", Timestamp.from(from), "to", Timestamp.from(to)));
        out.put("waiting", db.list("""
                SELECT r.id, r.token, r.status, r.name, r.phone, r.email, r.party, r.created_at, r.ready_at, r.note
                FROM reservations r WHERE r.source = 'WALK_IN' AND r.status IN ('WAITING', 'READY')
                ORDER BY r.created_at"""));
        out.put("tables", db.list("SELECT id, label, seats FROM dining_tables ORDER BY label"));
        out.put("rules", rulesView(rules(TenantContext.tenantId())));
        return out;
    }

    /** Seat a booking or a walk-in: {tableId (optional)}. Opens their bill on the table; a paid deposit goes on the bill. */
    public Map<String, Object> seat(UUID id, Body b) {
        Map<String, Object> r = db.one("Booking", "SELECT * FROM reservations WHERE id = :id FOR UPDATE", "id", id);
        if (!List.of("BOOKED", "WAITING", "READY", "HELD", "NO_SHOW").contains(r.get("status"))) {
            throw ApiException.conflict("This booking can't be seated (it's " + String.valueOf(r.get("status")).toLowerCase() + ")");
        }
        UUID table = b.has("tableId") && !b.str("tableId", "").isBlank() ? UUID.fromString(b.str("tableId"))
                : r.get("tableId") == null ? null : UUID.fromString(r.get("tableId").toString());
        if (table == null) {
            throw ApiException.bad("Choose a table");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", "DINE_IN");
        body.put("tableId", table.toString());
        body.put("guests", r.get("party"));
        body.put("customerLabel", r.get("name"));
        body.put("allowSecondBill", true);
        body.put("lines", List.of());
        UUID order = orders.create(new Body(body));
        if ("PAID".equals(r.get("depositStatus")) && ((BigDecimal) r.get("depositAmount")).signum() > 0) {
            orders.depositByServer(order, "MPESA", (BigDecimal) r.get("depositAmount"), "Booking deposit " + r.get("depositReceipt"));
            db.exec("UPDATE reservations SET deposit_status = 'APPLIED' WHERE id = :id", "id", id);
        }
        db.exec("UPDATE reservations SET status = 'SEATED', seated_at = now(), table_id = :tb, order_id = :o, acknowledged_at = coalesce(acknowledged_at, now()) WHERE id = :id",
                "id", id, "tb", table, "o", order);
        Map<String, Object> out = new LinkedHashMap<>(view(id));
        out.put("orderId", order);
        return out;
    }

    /** A walk-in's table is ready: tell them (email) and keep them on the list until seated. */
    public Map<String, Object> ready(UUID id) {
        Map<String, Object> r = db.one("Booking", "SELECT * FROM reservations WHERE id = :id", "id", id);
        if (!"WAITING".equals(r.get("status")) && !"READY".equals(r.get("status"))) {
            throw ApiException.conflict("Only someone on the waiting list can be told their table is ready");
        }
        db.exec("UPDATE reservations SET status = 'READY', ready_at = now() WHERE id = :id", "id", id);
        String email = (String) r.get("email");
        if (email != null) {
            Map<String, Object> biz = biz();
            String name = (String) r.get("name");
            mailer.trySendAs(TenantContext.tenantId(), email, name, "Your table at " + biz.get("name") + " is ready",
                    "Hi " + first(name) + ",\n\nYour table for " + r.get("party") + " is ready. Please come to the front and give your name.\n\n" + biz.get("name"),
                    EmailLayout.h("Your table is ready", null)
                            + EmailLayout.p("Hi " + Mailer.esc(first(name)) + ", your table for <b>" + r.get("party") + "</b> is ready. Please come to the front and give your name."));
        }
        return view(id);
    }

    /** {status: NO_SHOW | CANCELLED, reason}. */
    public Map<String, Object> close(UUID id, String status, String reason) {
        if (!List.of("NO_SHOW", "CANCELLED").contains(status)) {
            throw ApiException.bad("Choose: didn't come, or cancelled");
        }
        Map<String, Object> r = db.one("Booking", "SELECT status FROM reservations WHERE id = :id", "id", id);
        if (List.of("SEATED", "CANCELLED").contains(r.get("status"))) {
            throw ApiException.conflict("This booking is already " + String.valueOf(r.get("status")).toLowerCase());
        }
        db.exec("UPDATE reservations SET status = :s, cancel_reason = :r, acknowledged_at = coalesce(acknowledged_at, now()) WHERE id = :id",
                "id", id, "s", status, "r", clip(reason, 200));
        return view(id);
    }

    /** Move a booking: {tableId, date, time}. */
    public Map<String, Object> move(UUID id, Body b) {
        Map<String, Object> r = db.one("Booking", "SELECT * FROM reservations WHERE id = :id", "id", id);
        if (!List.of("BOOKED", "HELD").contains(r.get("status"))) {
            throw ApiException.conflict("Only a booking that hasn't started can be moved");
        }
        Instant start = ((Timestamp) asTs(r.get("startsAt"))).toInstant();
        if (b.has("date") || b.has("time")) {
            LocalDate d = b.has("date") ? LocalDate.parse(b.str("date")) : start.atZone(NAIROBI).toLocalDate();
            LocalTime t = b.has("time") ? LocalTime.parse(b.str("time")) : start.atZone(NAIROBI).toLocalTime();
            start = ZonedDateTime.of(d, t, NAIROBI).toInstant();
        }
        int minutes = (Integer) r.get("durationMin");
        UUID table = b.has("tableId") && !b.str("tableId", "").isBlank() ? UUID.fromString(b.str("tableId"))
                : freeTable((Integer) r.get("party"), start, minutes, id);
        if (table == null) {
            throw ApiException.conflict("No table free for them then");
        }
        UUID clash = db.value("""
                SELECT id FROM reservations WHERE table_id = :tb AND id <> :id AND status IN """ + HOLDING + """
                   AND starts_at < :end AND starts_at + make_interval(mins => duration_min) > :start LIMIT 1""", UUID.class,
                "tb", table, "id", id, "start", Timestamp.from(start), "end", Timestamp.from(start.plusSeconds(minutes * 60L)));
        if (clash != null) {
            throw ApiException.conflict("That table is already booked then");
        }
        db.exec("UPDATE reservations SET table_id = :tb, starts_at = :s, reminded_at = NULL WHERE id = :id", "id", id, "tb", table, "s", Timestamp.from(start));
        return view(id);
    }

    // ---------------- the ring ----------------

    /** Online bookings nobody on the staff has acknowledged yet: they ring on every staff screen. */
    public Map<String, Object> alerts() {
        sweep();
        List<Map<String, Object>> items = db.list("""
                SELECT r.id, r.name, r.party, r.starts_at, r.status, r.deposit_amount, r.deposit_status, t.label AS table_label, r.created_at
                FROM reservations r LEFT JOIN dining_tables t ON t.id = r.table_id
                WHERE r.source = 'ONLINE' AND r.acknowledged_at IS NULL AND r.status IN ('HELD', 'BOOKED')
                ORDER BY r.created_at""");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("upTo", items.isEmpty() ? null : String.valueOf(items.get(items.size() - 1).get("createdAt")));
        out.put("items", items);
        return out;
    }

    /** Someone on the staff saw them: stop the ring for everyone (up to the newest one they saw). */
    public int acknowledge(String upTo) {
        Timestamp to = upTo == null || upTo.isBlank() ? Timestamp.from(Instant.now()) : asTs(upTo);
        return db.exec("""
                UPDATE reservations SET acknowledged_at = now(), acknowledged_by = :u
                WHERE source = 'ONLINE' AND acknowledged_at IS NULL AND created_at <= :to""", "to", to);
    }

    // ---------------- the guest's page ----------------

    /** What the guest sees on their booking page. */
    public Map<String, Object> view(UUID id) {
        Map<String, Object> r = db.one("Booking", """
                SELECT r.id, r.token, r.source, r.status, r.name, r.party, r.starts_at, r.duration_min, t.label AS table_label, r.note,
                       r.deposit_amount, r.deposit_status, r.hold_until, r.acknowledged_at IS NOT NULL AS confirmed_by_staff, r.ready_at,
                       r.cancel_reason, r.created_at,
                       CASE WHEN r.status = 'WAITING' THEN (SELECT count(*) FROM reservations w WHERE w.source = 'WALK_IN' AND w.status = 'WAITING'
                                                             AND w.created_at <= r.created_at) END AS place
                FROM reservations r LEFT JOIN dining_tables t ON t.id = r.table_id WHERE r.id = :id""", "id", id);
        Instant at = asTs(r.get("startsAt")).toInstant();
        r.put("day", DAY.format(at.atZone(NAIROBI)));
        r.put("time", TIME.format(at.atZone(NAIROBI)));
        return r;
    }

    public UUID idOfToken(String token) {
        return db.value("SELECT id FROM reservations WHERE token = :tk", UUID.class, "tk", token);
    }

    // ---------------- emails ----------------

    void confirmEmail(UUID id) {
        Map<String, Object> v = view(id);
        Map<String, Object> r = db.one("Booking", "SELECT email, name FROM reservations WHERE id = :id", "id", id);
        String email = (String) r.get("email");
        if (email == null) {
            return;
        }
        Map<String, Object> biz = biz();
        String name = (String) r.get("name");
        String link = link(biz, (String) v.get("token"));
        BigDecimal dep = (BigDecimal) v.get("depositAmount");
        String when = v.get("day") + ", " + v.get("time");
        List<String[]> facts = new ArrayList<>(List.of(new String[]{"Day", (String) v.get("day")}, new String[]{"Time", (String) v.get("time")},
                new String[]{"Guests", String.valueOf(v.get("party"))}));
        if (dep.signum() > 0) {
            facts.add(new String[]{"Deposit paid", "KES " + dep.stripTrailingZeros().toPlainString() + " (comes off your bill)"});
        }
        mailer.trySendAs(TenantContext.tenantId(), email, name, "Your table at " + biz.get("name") + ": " + when,
                "Hi " + first(name) + ",\n\nYour table for " + v.get("party") + " is booked for " + when + ".\n"
                        + (dep.signum() > 0 ? "Deposit paid: KES " + dep.stripTrailingZeros().toPlainString() + ", which comes off your bill.\n" : "")
                        + "Please arrive on time: tables are held for 15 minutes.\n\nSee or cancel your booking: " + link + "\n\n" + biz.get("name"),
                EmailLayout.h("Your table is booked", null)
                        + EmailLayout.p("Hi " + Mailer.esc(first(name)) + ", we look forward to seeing you.")
                        + EmailLayout.facts(facts.toArray(new String[0][]))
                        + EmailLayout.p("<span style=\"color:#6b6b66\">Please arrive on time: tables are held for 15 minutes after your booking.</span>")
                        + EmailLayout.button("See or cancel your booking", link, (String) biz.get("brandColor")));
    }

    void reminderEmail(UUID id) {
        Map<String, Object> v = view(id);
        Map<String, Object> r = db.one("Booking", "SELECT email, name FROM reservations WHERE id = :id", "id", id);
        if (r.get("email") == null) {
            return;
        }
        Map<String, Object> biz = biz();
        String name = (String) r.get("name");
        String link = link(biz, (String) v.get("token"));
        mailer.trySendAs(TenantContext.tenantId(), (String) r.get("email"), name, "See you at " + v.get("time") + ": your table at " + biz.get("name"),
                "Hi " + first(name) + ",\n\nA reminder: your table for " + v.get("party") + " is booked for " + v.get("day") + ", " + v.get("time")
                        + ".\nCan't make it? Please cancel so someone else can have the table: " + link + "\n\n" + biz.get("name"),
                EmailLayout.h("See you soon", null)
                        + EmailLayout.p("Hi " + Mailer.esc(first(name)) + ", a reminder of your booking.")
                        + EmailLayout.facts(new String[][]{{"Day", (String) v.get("day")}, {"Time", (String) v.get("time")}, {"Guests", String.valueOf(v.get("party"))}})
                        + EmailLayout.p("Can't make it? Please cancel so someone else can have the table.")
                        + EmailLayout.button("See or cancel your booking", link, (String) biz.get("brandColor")));
    }

    // ---------------- helpers ----------------

    Map<String, Object> biz() {
        return jdbc.queryForMap("SELECT name, slug, brand_color AS \"brandColor\" FROM tenants WHERE id = ?", TenantContext.tenantId());
    }

    String link(Map<String, Object> biz, String token) {
        return mailer.appUrl() + "/book/" + biz.get("slug") + "/r/" + token;
    }

    private static Timestamp asTs(Object o) {
        if (o instanceof Timestamp t) {
            return t;
        }
        String s = String.valueOf(o).replace(' ', 'T');
        try {
            return Timestamp.from(java.time.OffsetDateTime.parse(s).toInstant());
        } catch (RuntimeException e) {
            try {
                return Timestamp.from(Instant.parse(s));
            } catch (RuntimeException e2) {
                return Timestamp.valueOf(String.valueOf(o).replace('T', ' ').replaceAll("[+Z].*$", ""));
            }
        }
    }

    private static LocalTime time(String s, String fallback) {
        try {
            return LocalTime.parse(s);
        } catch (RuntimeException e) {
            if (fallback == null) {
                throw ApiException.bad("Times look like 11:00 or 22:30");
            }
            return LocalTime.parse(fallback);
        }
    }

    private static int clamp(Integer v, int lo, int hi) {
        int x = v == null ? lo : v;
        return Math.max(lo, Math.min(hi, x));
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String first(String name) {
        return name == null ? "" : name.trim().split("\\s+")[0];
    }

}
