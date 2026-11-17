package com.lezitech.suite.rooms;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.files.SafeFetch;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Staying in step with Airbnb, Booking.com and others by iCal, both ways:
 * - in: each room can follow calendars from other sites; their bookings block the room here (read every 15 minutes);
 * - out: each room has its own calendar link (secret token) for the other sites to read our bookings.
 */
@Service
@Transactional
public class RoomCalendars {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final Db db;
    /** How a calendar is read (tests swap it for canned text). */
    private Function<String, String> fetcher = url -> SafeFetch.text(url, MAX_BYTES);

    public RoomCalendars(Db db) {
        this.db = db;
    }

    public void useFetcher(Function<String, String> f) {
        this.fetcher = f;
    }

    public List<Map<String, Object>> list() {
        return db.list("""
                SELECT c.*, sp.name AS space_name, (SELECT count(*) FROM room_blocks b WHERE b.calendar_id = c.id AND b.ends_on >= current_date) AS upcoming
                FROM room_calendars c JOIN spaces sp ON sp.id = c.space_id ORDER BY sp.name, c.source""");
    }

    /** {spaceId, source, url}: the link is checked (https, public) before it's kept, then read straight away. */
    public Map<String, Object> add(Body b) {
        TenantContext.requireRole("MANAGER");
        UUID space = UUID.fromString(b.str("spaceId"));
        db.one("Room", "SELECT id FROM spaces WHERE id = :id", "id", space);
        String url = b.str("url", "").trim();
        if (url.length() > 1000) {
            throw ApiException.bad("That link is too long");
        }
        SafeFetch.check(url);
        String source = b.str("source", "Other").trim();
        UUID id = db.insert("INSERT INTO room_calendars (tenant_id, space_id, source, url) VALUES (:t, :s, :src, :url) RETURNING id",
                "s", space, "src", source.isEmpty() ? "Other" : source.length() > 40 ? source.substring(0, 40) : source, "url", url);
        sync(id);
        return db.one("Calendar", "SELECT * FROM room_calendars WHERE id = :id", "id", id);
    }

    public void remove(UUID id) {
        TenantContext.requireRole("MANAGER");
        db.exec("DELETE FROM room_calendars WHERE id = :id", "id", id);
    }

    /** Read one calendar and replace its blocks. Errors are kept on the calendar, never thrown. */
    public String sync(UUID calendarId) {
        Map<String, Object> c = db.one("Calendar", "SELECT * FROM room_calendars WHERE id = :id", "id", calendarId);
        String error = null;
        try {
            String text = fetcher.apply((String) c.get("url"));
            List<Ical.Event> events = Ical.parse(text);
            if (!text.contains("BEGIN:VCALENDAR")) {
                throw ApiException.bad("That link isn't a calendar");
            }
            LocalDate past = LocalDate.now(NAIROBI).minusDays(30);
            db.exec("DELETE FROM room_blocks WHERE calendar_id = :id", "id", calendarId);
            for (Ical.Event e : events) {
                if (e.end().isBefore(past) || e.start().isAfter(LocalDate.now(NAIROBI).plusYears(2))) {
                    continue;
                }
                // Our own bookings come back from sites that echo calendars: don't block the room against itself.
                if (e.uid() != null && e.uid().endsWith("@biznez")) {
                    continue;
                }
                db.exec("""
                        INSERT INTO room_blocks (tenant_id, space_id, calendar_id, starts_on, ends_on, uid, summary)
                        VALUES (:t, :s, :c, :a, :z, :uid, :sum)""",
                        "s", c.get("spaceId"), "c", calendarId, "a", e.start(), "z", e.end(), "uid", e.uid(),
                        "sum", c.get("source") + (e.summary() == null ? "" : ": " + e.summary()));
            }
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (error.length() > 300) {
                error = error.substring(0, 300);
            }
        }
        db.exec("UPDATE room_calendars SET last_synced_at = now(), last_error = :e WHERE id = :id", "e", error, "id", calendarId);
        return error;
    }

    public int syncAll() {
        int n = 0;
        for (Map<String, Object> c : db.list("SELECT id FROM room_calendars")) {
            if (sync(UUID.fromString(c.get("id").toString())) == null) {
                n++;
            }
        }
        return n;
    }

    /** The room's own calendar for other sites: our reservations and in-house stays, no names. */
    public String export(UUID spaceId, String token) {
        Map<String, Object> room = db.find("SELECT name, ical_token FROM spaces WHERE id = :id", "id", spaceId)
                .orElseThrow(() -> ApiException.notFound("Calendar"));
        if (room.get("icalToken") == null || !java.security.MessageDigest.isEqual(room.get("icalToken").toString().getBytes(), token.getBytes())) {
            throw ApiException.notFound("Calendar");
        }
        List<Ical.Event> events = new ArrayList<>();
        for (Map<String, Object> s : db.list("""
                SELECT id, check_in, check_out FROM stays WHERE space_id = :id AND status IN ('RESERVED', 'IN_HOUSE', 'HELD')
                  AND check_out >= current_date - 30 ORDER BY check_in""", "id", spaceId)) {
            events.add(new Ical.Event(s.get("id") + "@biznez", LocalDate.parse(s.get("checkIn").toString()),
                    LocalDate.parse(s.get("checkOut").toString()), "Booked"));
        }
        return Ical.write(String.valueOf(room.get("name")), events);
    }
}
