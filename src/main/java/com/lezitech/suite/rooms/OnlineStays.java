package com.lezitech.suite.rooms;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.notify.Reminders;
import com.lezitech.suite.sales.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Guests book rooms online: dates, then the room types free for the whole stay with the price (seasons, weekends),
 * then their details. With a deposit the room is held until the M-Pesa prompt is paid (the deposit goes on the stay's
 * bill); without one it's reserved straight away. The front desk sees new online bookings ring until seen.
 */
@Service
@Transactional
public class OnlineStays {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final int HOLD_MINUTES = 20;
    static final String ROOM_KINDS = "('ROOM','COTTAGE','TENT','APARTMENT')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    private final Db db;
    private final StayService stays;
    private final RoomRates rates;
    private final OrderService orders;
    private final Mailer mailer;
    private final Reminders reminders;

    public OnlineStays(Db db, StayService stays, RoomRates rates, OrderService orders, Mailer mailer, Reminders reminders) {
        this.db = db;
        this.stays = stays;
        this.rates = rates;
        this.orders = orders;
        this.mailer = mailer;
        this.reminders = reminders;
    }

    /** Rooms free for every night of the stay, by room type, cheapest room's price first. */
    public List<Map<String, Object>> options(LocalDate in, LocalDate out, int guests) {
        check(in, out);
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        for (Map<String, Object> r : db.list("""
                SELECT sp.id, sp.name, coalesce(sp.type_name, sp.name) AS type, sp.capacity, sp.rate FROM spaces sp
                WHERE sp.active AND sp.kind IN """ + " " + ROOM_KINDS + """
                  AND sp.housekeeping <> 'OUT_OF_ORDER' AND sp.capacity >= :g
                  AND NOT EXISTS (SELECT 1 FROM stays s WHERE s.space_id = sp.id AND (s.status IN ('RESERVED','IN_HOUSE') OR (s.status = 'HELD' AND s.hold_until > now()))
                                  AND s.check_in < :out AND s.check_out > :in)
                  AND NOT EXISTS (SELECT 1 FROM room_blocks b WHERE b.space_id = sp.id AND b.starts_on < :out AND b.ends_on > :in)
                ORDER BY sp.sort, sp.name""", "in", in, "out", out, "g", Math.max(1, guests))) {
            RoomRates.Quote q = rates.quote(UUID.fromString(r.get("id").toString()), in, out);
            String type = (String) r.get("type");
            Map<String, Object> t = byType.get(type);
            if (t == null || ((BigDecimal) t.get("total")).compareTo(q.total()) > 0) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("type", type);
                o.put("capacity", r.get("capacity"));
                o.put("nights", q.nights());
                o.put("total", q.total());
                o.put("perNight", q.total().divide(BigDecimal.valueOf(q.nights()), 0, RoundingMode.HALF_UP));
                o.put("minNights", q.minNights());
                o.put("free", t == null ? 1 : ((Number) t.get("free")).intValue() + 1);
                byType.put(type, o);
            } else {
                t.put("free", ((Number) t.get("free")).intValue() + 1);
            }
        }
        return new ArrayList<>(byType.values());
    }

    /** {type, checkIn, checkOut, adults, children, name, phone, email, nationality, note}. */
    public Map<String, Object> book(Body b) {
        StayService.Rules r = stays.rules();
        if (!r.online()) {
            throw ApiException.forbidden("Online booking is closed. Please call us to book.");
        }
        LocalDate in = b.date("checkIn");
        LocalDate out = b.date("checkOut");
        check(in, out);
        String type = b.str("type", "");
        String name = b.str("name", "").trim();
        String phone = b.str("phone", "").trim();
        String email = b.str("email", "").trim().toLowerCase();
        if (name.isEmpty() || (phone.isEmpty() && email.isEmpty())) {
            throw ApiException.bad("Add your name, and a phone number or email");
        }
        if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.bad("Check the email address");
        }
        int adults = Math.max(1, b.integer("adults", 1));
        int children = Math.max(0, b.integer("children", 0));
        // The first free room of that type, locked so two guests can't take the same one.
        UUID room = null;
        for (Map<String, Object> sp : db.list("""
                SELECT sp.id FROM spaces sp WHERE sp.active AND sp.kind IN """ + " " + ROOM_KINDS + """
                  AND coalesce(sp.type_name, sp.name) = :type AND sp.housekeeping <> 'OUT_OF_ORDER' AND sp.capacity >= :g
                ORDER BY sp.sort, sp.name FOR UPDATE""", "type", type, "g", adults + children)) {
            try {
                stays.assertFree(UUID.fromString(sp.get("id").toString()), in, out, null);
                room = UUID.fromString(sp.get("id").toString());
                break;
            } catch (ApiException busy) {
                // try the next room of this type
            }
        }
        if (room == null) {
            throw ApiException.conflict("That room type has just been booked for those dates. Please choose another.");
        }
        RoomRates.Quote q = rates.quote(room, in, out);
        if (q.nights() < q.minNights()) {
            throw ApiException.bad("Those dates need a stay of at least " + q.minNights() + " nights");
        }
        BigDecimal deposit = q.total().multiply(BigDecimal.valueOf(r.depositPercent())).divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spaceId", room.toString());
        body.put("checkIn", in.toString());
        body.put("checkOut", out.toString());
        body.put("guestName", name);
        if (!phone.isEmpty()) {
            body.put("phone", phone);
        }
        if (!email.isEmpty()) {
            body.put("email", email);
        }
        body.put("adults", adults);
        body.put("children", children);
        body.put("source", "Online");
        body.put("notes", b.str("note", null));
        if (b.has("nationality")) {
            body.put("nationality", b.str("nationality"));
        }
        if (deposit.signum() > 0) {
            body.put("status", "HELD");
            body.put("holdMinutes", HOLD_MINUTES);
            body.put("depositAmount", deposit);
        }
        UUID id = stays.reserve(new Body(body));
        if (deposit.signum() <= 0) {
            confirm(id);
        }
        return view(id);
    }

    public StayService.Rules rulesOf() {
        return stays.rules();
    }

    /** Free rooms held for a deposit nobody paid in time. */
    public int sweep() {
        int n = 0;
        // Never cancel one whose deposit came in (even if confirming it hit a snag): staff sort those out.
        for (Map<String, Object> s : db.list("""
                SELECT st.id, st.folio_id FROM stays st WHERE st.status = 'HELD' AND st.hold_until < now()
                  AND NOT EXISTS (SELECT 1 FROM mpesa_requests m WHERE m.stay_id = st.id AND m.status = 'PAID')""")) {
            db.exec("UPDATE stays SET status = 'CANCELLED', notes = coalesce(notes || ' · ', '') || 'Deposit not paid in time' WHERE id = :id", "id", s.get("id"));
            if (s.get("folioId") != null) {
                orders.voidOrder(UUID.fromString(s.get("folioId").toString()), "Online booking: deposit not paid in time");
            }
            n++;
        }
        return n;
    }

    /** The deposit was paid: it goes on the stay's bill and the room is reserved. */
    public void depositPaid(UUID stayId, String receipt) {
        Map<String, Object> s = db.one("Stay", "SELECT status, folio_id, deposit_amount, space_id, check_in, check_out FROM stays WHERE id = :id", "id", stayId);
        if ("CANCELLED".equals(s.get("status"))) {
            // Paid after the hold ran out: only bring it back if the room is still free; otherwise staff refund or move them.
            try {
                stays.assertFree(UUID.fromString(s.get("spaceId").toString()), LocalDate.parse(s.get("checkIn").toString()),
                        LocalDate.parse(s.get("checkOut").toString()), stayId);
            } catch (ApiException e) {
                db.exec("UPDATE stays SET notes = coalesce(notes || ' · ', '') || :n WHERE id = :id", "id", stayId,
                        "n", "Deposit " + receipt + " paid after the room was let go: refund or move the guest");
                return;
            }
        }
        if (s.get("folioId") != null && ((BigDecimal) s.get("depositAmount")).signum() > 0) {
            orders.depositByServer(UUID.fromString(s.get("folioId").toString()), "MPESA", (BigDecimal) s.get("depositAmount"), "Room deposit " + receipt);
        }
        db.exec("UPDATE stays SET status = CASE WHEN status IN ('HELD', 'CANCELLED') THEN 'RESERVED' ELSE status END, hold_until = NULL WHERE id = :id", "id", stayId);
        confirm(stayId);
    }

    public UUID idOfToken(String token) {
        return db.value("SELECT id FROM stays WHERE token = :tk", UUID.class, "tk", token);
    }

    public Map<String, Object> view(UUID id) {
        Map<String, Object> s = db.one("Stay", """
                SELECT s.id, s.token, s.status, s.guest_name, s.check_in, s.check_out, s.adults, s.children, s.room_total, s.deposit_amount,
                       s.hold_until, coalesce(sp.type_name, sp.name) AS room_type, o.paid AS paid
                FROM stays s JOIN spaces sp ON sp.id = s.space_id LEFT JOIN orders o ON o.id = s.folio_id WHERE s.id = :id""", "id", id);
        Map<String, Object> out = new LinkedHashMap<>(s);
        LocalDate in = LocalDate.parse(s.get("checkIn").toString());
        out.put("arrive", in.format(DAY));
        out.put("leave", LocalDate.parse(s.get("checkOut").toString()).format(DAY));
        out.put("nights", ChronoUnit.DAYS.between(in, LocalDate.parse(s.get("checkOut").toString())));
        out.put("canCancel", List.of("RESERVED", "HELD").contains(s.get("status"))
                && Instant.now().plus(stays.rules().cancelHours(), ChronoUnit.HOURS).isBefore(in.atTime(14, 0).atZone(NAIROBI).toInstant()));
        return out;
    }

    public Map<String, Object> cancelByGuest(UUID id) {
        if (!Boolean.TRUE.equals(view(id).get("canCancel"))) {
            throw ApiException.conflict("It's too close to your arrival to cancel online. Please call us.");
        }
        stays.cancel(id, "CANCELLED");
        return view(id);
    }

    /** New online bookings the front desk hasn't seen. */
    public Map<String, Object> alerts() {
        List<Map<String, Object>> list = db.list("""
                SELECT s.id, s.guest_name, s.check_in, s.check_out, sp.name AS room, s.status FROM stays s JOIN spaces sp ON sp.id = s.space_id
                WHERE s.source = 'Online' AND s.acknowledged_at IS NULL AND s.status IN ('RESERVED', 'HELD') ORDER BY s.created_at""");
        return Map.of("count", list.size(), "stays", list);
    }

    public int acknowledge() {
        return db.exec("UPDATE stays SET acknowledged_at = now() WHERE source = 'Online' AND acknowledged_at IS NULL");
    }

    /** The guest register for a date range: everyone who stayed, with their ID details. */
    public List<Map<String, Object>> register(LocalDate from, LocalDate to) {
        return db.list("""
                SELECT s.guest_name, s.phone, s.id_type, s.id_number, s.nationality, s.coming_from, s.vehicle_reg, s.adults, s.children,
                       s.check_in, s.check_out, sp.name AS room, s.status
                FROM stays s JOIN spaces sp ON sp.id = s.space_id
                WHERE s.status IN ('IN_HOUSE', 'CHECKED_OUT') AND s.check_in <= :to AND s.check_out >= :from
                ORDER BY s.check_in, sp.name""", "from", from, "to", to);
    }

    private void confirm(UUID id) {
        Map<String, Object> s = db.one("Stay", "SELECT guest_name, email, phone FROM stays WHERE id = :id", "id", id);
        if (s.get("email") == null) {
            return;
        }
        Map<String, Object> v = view(id);
        Map<String, Object> biz = db.one("Business", "SELECT name, slug, brand_color AS \"brandColor\" FROM tenants WHERE id = :t");
        String link = mailer.appUrl() + "/stay/" + biz.get("slug") + "/s/" + v.get("token");
        String first = String.valueOf(s.get("guestName")).trim().split("\\s+")[0];
        reminders.sendOnce("STAY", id, "CONFIRM", new Reminders.Contact((String) s.get("guestName"), (String) s.get("email"), (String) s.get("phone")),
                new Reminders.Message("Your stay at " + biz.get("name") + ": " + v.get("arrive"),
                        "Hi " + first + ",\n\nYour " + v.get("roomType") + " is booked from " + v.get("arrive") + " to " + v.get("leave") + ".\nSee or cancel your booking: " + link
                                + "\n\n" + biz.get("name"),
                        EmailLayout.h("Your stay is booked", null)
                                + EmailLayout.p("Hi " + Mailer.esc(first) + ", we look forward to welcoming you.")
                                + EmailLayout.facts(new String[][]{{"Arriving", String.valueOf(v.get("arrive"))}, {"Leaving", String.valueOf(v.get("leave"))},
                                        {"Room", String.valueOf(v.get("roomType"))}, {"Guests", v.get("adults") + (((Number) v.get("children")).intValue() > 0 ? " + " + v.get("children") + " children" : "")},
                                        {"Price", "KES " + ((BigDecimal) v.get("roomTotal")).stripTrailingZeros().toPlainString()}})
                                + EmailLayout.button("See or cancel your booking", link, (String) biz.get("brandColor"))));
    }

    private static void check(LocalDate in, LocalDate out) {
        LocalDate today = LocalDate.now(NAIROBI);
        if (in == null || out == null || !out.isAfter(in)) {
            throw ApiException.bad("Choose the day you arrive and the day you leave");
        }
        if (in.isBefore(today) || in.isAfter(today.plusDays(540)) || ChronoUnit.DAYS.between(in, out) > 60) {
            throw ApiException.bad("Choose dates from today, for up to 60 nights");
        }
    }
}
