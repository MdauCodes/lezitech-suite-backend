package com.lezitech.suite.rooms;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reservations for rooms, cottages and halls. Each stay owns a folio (an open order) from the
 * moment it's booked, so deposits, restaurant bills and room nights all land on one bill.
 */
@Service
@Transactional
public class StayService {

    private final Db db;
    private final OrderService orders;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.sales.ManagerRequestService requests;
    private final RoomRates rates;

    public StayService(Db db, OrderService orders, RoomRates rates) {
        this.db = db;
        this.orders = orders;
        this.rates = rates;
    }

    /** Owner choices for rooms ({@code settings.rooms}). */
    public record Rules(boolean online, int depositPercent, int cancelHours, boolean requireGuestId) {
    }

    public Rules rules() {
        Map<String, Object> r = db.one("Business", """
                SELECT coalesce((settings->'rooms'->>'online')::boolean, true) AS online,
                       coalesce((settings->'rooms'->>'depositPercent')::int, 0) AS deposit_percent,
                       coalesce((settings->'rooms'->>'cancelHours')::int, 48) AS cancel_hours,
                       coalesce((settings->'rooms'->>'requireGuestId')::boolean, false) AS require_guest_id
                FROM tenants WHERE id = :t""");
        return new Rules(Boolean.TRUE.equals(r.get("online")), ((Number) r.get("depositPercent")).intValue(),
                ((Number) r.get("cancelHours")).intValue(), Boolean.TRUE.equals(r.get("requireGuestId")));
    }

    public void saveRules(Body b) {
        com.lezitech.suite.tenant.TenantContext.requireRole("MANAGER");
        Rules cur = rules();
        int dep = b.integer("depositPercent", cur.depositPercent());
        if (dep < 0 || dep > 100) {
            throw ApiException.bad("The deposit is between 0% and 100% of the stay");
        }
        Map<String, Object> m = Map.of("online", b.bool("online", cur.online()), "depositPercent", dep,
                "cancelHours", Math.max(0, Math.min(720, b.integer("cancelHours", cur.cancelHours()))),
                "requireGuestId", b.bool("requireGuestId", cur.requireGuestId()));
        db.exec("UPDATE tenants SET settings = settings || jsonb_build_object('rooms', cast(:v as jsonb)) WHERE id = :t", "v", db.toJson(m));
    }

    public UUID reserve(Body b) {
        UUID spaceId = UUID.fromString(b.str("spaceId"));
        LocalDate in = b.date("checkIn");
        LocalDate out = b.date("checkOut");
        if (in == null || out == null || !out.isAfter(in)) {
            throw ApiException.bad("Check-out must be after check-in");
        }
        assertFree(spaceId, in, out, null);
        Map<String, Object> space = db.one("Room", "SELECT * FROM spaces WHERE id = :spaceId", "spaceId", spaceId);
        // A price typed by staff is per night and fixed; otherwise each night takes its seasonal or weekend price.
        boolean fixed = b.has("rate");
        RoomRates.Quote quote = rates.quote(spaceId, in, out);
        BigDecimal perNight = fixed ? b.num("rate") : quote.total().divide(BigDecimal.valueOf(quote.nights()), 2, java.math.RoundingMode.HALF_UP);
        String guest = b.str("guestName");
        UUID customerId = b.has("customerId") ? UUID.fromString(b.str("customerId")) : null;
        if (customerId == null && b.has("phone")) {
            customerId = db.value("SELECT id FROM customers WHERE phone = :phone LIMIT 1", UUID.class, "phone", b.str("phone"));
        }
        if (customerId == null) {
            customerId = db.insert("INSERT INTO customers (tenant_id, name, phone) VALUES (:t, :name, :phone) RETURNING id",
                    "name", guest, "phone", b.str("phone", null));
        }
        Map<String, Object> folioBody = new HashMap<>();
        folioBody.put("channel", "ROOM");
        folioBody.put("customerId", customerId.toString());
        folioBody.put("note", "Folio: " + space.get("name") + " · " + guest);
        folioBody.put("lines", List.of());
        UUID folio = orders.create(new Body(folioBody));
        UUID id = db.insert("""
                INSERT INTO stays (tenant_id, space_id, customer_id, guest_name, phone, adults, children, check_in, check_out,
                                   rate, board, source, status, notes, folio_id, email, room_total, rate_fixed, hold_until, deposit_amount,
                                   event_name, organiser, people, nationality)
                VALUES (:t, :spaceId, :customerId, :guest, :phone, :adults, :children, :in, :out, :rate, :board, :source,
                        :status, :notes, :folio, :email, :total, :fixed, :hold, :deposit, :event, :organiser, :people, :nat) RETURNING id""",
                "spaceId", spaceId, "customerId", customerId, "guest", guest, "phone", b.str("phone", null),
                "adults", b.integer("adults", 1), "children", b.integer("children", 0), "in", in, "out", out,
                "rate", perNight, "board", b.str("board", "BB"),
                "source", b.str("source", "Walk-in"), "status", b.str("status", "RESERVED"), "notes", b.str("notes", null),
                "folio", folio, "email", b.str("email", null), "total", fixed ? perNight.multiply(BigDecimal.valueOf(quote.nights())) : quote.total(),
                "fixed", fixed, "hold", b.has("holdMinutes") ? java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60L * b.integer("holdMinutes", 15))) : null,
                "deposit", b.num("depositAmount", BigDecimal.ZERO), "event", b.str("eventName", null), "organiser", b.str("organiser", null),
                "people", b.integer("people", null), "nat", b.str("nationality", null));
        db.exec("UPDATE orders SET stay_id = :id WHERE id = :folio", "id", id, "folio", folio);
        return id;
    }

    public void assertFree(UUID spaceId, LocalDate in, LocalDate out, UUID ignoreStay) {
        Map<String, Object> blocked = db.find("""
                SELECT summary, starts_on, ends_on FROM room_blocks WHERE space_id = :spaceId AND starts_on < :out AND ends_on > :in LIMIT 1""",
                "spaceId", spaceId, "in", in, "out", out).orElse(null);
        if (blocked != null) {
            throw ApiException.conflict("Booked elsewhere (" + blocked.get("summary") + ") from " + blocked.get("startsOn") + " to " + blocked.get("endsOn"));
        }
        Map<String, Object> clash = db.find("""
                SELECT guest_name, check_in, check_out FROM stays
                WHERE space_id = :spaceId AND (status IN ('RESERVED','IN_HOUSE') OR (status = 'HELD' AND hold_until > now()))
                  AND check_in < :out AND check_out > :in
                  AND (cast(:ignore as uuid) IS NULL OR id <> cast(:ignore as uuid)) LIMIT 1""",
                "spaceId", spaceId, "in", in, "out", out, "ignore", ignoreStay).orElse(null);
        if (clash != null) {
            throw ApiException.conflict("Already booked by " + clash.get("guestName") + " from "
                    + clash.get("checkIn") + " to " + clash.get("checkOut"));
        }
    }

    /** {idType, idNumber, nationality, comingFrom, vehicleReg}: the guest register, required if the owner switched it on. */
    public void checkIn(UUID id, Body b) {
        Map<String, Object> s0 = stay(id);
        String idNumber = b.str("idNumber", (String) s0.get("idNumber"));
        String nationality = b.str("nationality", (String) s0.get("nationality"));
        if (rules().requireGuestId() && (idNumber == null || idNumber.isBlank() || nationality == null || nationality.isBlank())) {
            throw ApiException.bad("Add the guest's ID or passport number and nationality for the register");
        }
        db.exec("""
                UPDATE stays SET id_type = coalesce(:type, id_type), id_number = coalesce(:num, id_number), nationality = coalesce(:nat, nationality),
                       coming_from = coalesce(:from, coming_from), vehicle_reg = coalesce(:car, vehicle_reg) WHERE id = :id""",
                "id", id, "type", b.str("idType", null), "num", b.str("idNumber", null), "nat", b.str("nationality", null),
                "from", b.str("comingFrom", null), "car", b.str("vehicleReg", null));
        checkIn(id);
    }

    public void checkIn(UUID id) {
        Map<String, Object> s = stay(id);
        if (!"RESERVED".equals(s.get("status"))) {
            throw ApiException.conflict("Only reserved stays can check in");
        }
        Object hk = db.value("SELECT housekeeping FROM spaces WHERE id = :spaceId", String.class, "spaceId", s.get("spaceId"));
        if ("OUT_OF_ORDER".equals(hk)) {
            throw ApiException.conflict("This room is out of order; move the guest first");
        }
        db.exec("UPDATE stays SET status = 'IN_HOUSE', check_in = least(check_in, current_date) WHERE id = :id", "id", id);
    }

    /** Posts the room nights to the folio and frees the room for housekeeping. Payment happens on the folio. */
    public UUID checkOut(UUID id) {
        Map<String, Object> s = stay(id);
        if (!"IN_HOUSE".equals(s.get("status"))) {
            throw ApiException.conflict("Only in-house guests can check out");
        }
        LocalDate in = LocalDate.parse(s.get("checkIn").toString());
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"));
        LocalDate out = today.isAfter(in) ? today : in.plusDays(1);
        long nights = Math.max(1, ChronoUnit.DAYS.between(in, out));
        UUID folio = UUID.fromString(s.get("folioId").toString());
        String room = db.value("SELECT name FROM spaces WHERE id = :spaceId", String.class, "spaceId", s.get("spaceId"));
        Map<String, Object> line = new HashMap<>();
        line.put("name", "Room " + room + " · " + nights + " night" + (nights == 1 ? "" : "s") + " (" + s.get("board") + ")");
        if (Boolean.TRUE.equals(s.get("rateFixed"))) {
            line.put("unitPrice", s.get("rate"));
            line.put("qty", nights);
        } else {
            // Each night at its own price (season, weekend), for the nights actually stayed.
            line.put("unitPrice", rates.quote(UUID.fromString(s.get("spaceId").toString()), in, out).total());
            line.put("qty", 1);
        }
        line.put("taxRate", 16);
        orders.addLines(folio, List.of(line));
        // Early or late departures shorten or extend the stay to the real date.
        db.exec("UPDATE stays SET status = 'CHECKED_OUT', check_out = :out WHERE id = :id", "id", id, "out", out);
        db.exec("UPDATE spaces SET housekeeping = 'DIRTY' WHERE id = :spaceId", "spaceId", s.get("spaceId"));
        return folio;
    }

    /**
     * Call off a reservation that has not checked in. The reservation itself is always called off; its unpaid
     * bill goes only if this job may cancel bills, otherwise a manager is asked and the bill is left alone.
     * Returns the words to show the person who did it -- what happened to the bill is the part they need.
     */
    public String cancel(UUID id, String status) {
        Map<String, Object> s = stay(id);
        if (!"RESERVED".equals(s.get("status")) && !"HELD".equals(s.get("status"))) {
            throw ApiException.conflict("Only reservations not yet checked in can be cancelled");
        }
        db.exec("UPDATE stays SET status = :status WHERE id = :id", "id", id, "status", status);
        if (s.get("folioId") == null) {
            return "The reservation is called off.";
        }
        UUID folio = UUID.fromString(s.get("folioId").toString());
        BigDecimal paid = db.value("SELECT paid FROM orders WHERE id = :folio", BigDecimal.class, "folio", folio);
        if (paid != null && paid.signum() != 0) {
            // Money was taken: the bill stays open so someone decides about giving it back. Nobody is asked,
            // because cancelling it would push the guest's money back out, which is a refund and a separate
            // decision -- but the front desk must be told the money is still sitting there.
            return "The reservation is called off. The guest has already paid "
                    + paid.stripTrailingZeros().toPlainString()
                    + " on this booking, and that money is still on the bill: a manager decides whether to give it back.";
        }
        String words = status.toLowerCase().replace('_', ' ');
        String who = TenantContext.callerOrNull() == null || TenantContext.caller().name() == null
                ? "The front desk" : TenantContext.caller().name();
        String room = db.value("SELECT name FROM spaces WHERE id = :id", String.class, "id", s.get("spaceId"));
        boolean gone = requests.cancelOrAsk(folio, "Reservation " + words,
                who + " called off the reservation for " + (room == null ? "a room" : "room " + room)
                        + " (" + words + ") and asks to cancel its bill");
        return gone
                ? "The reservation is called off."
                : "The reservation is called off. A manager has been asked about its bill, which stays until they answer.";
    }

    public void move(UUID id, UUID spaceId, LocalDate in, LocalDate out) {
        Map<String, Object> s = stay(id);
        LocalDate newIn = in == null ? LocalDate.parse(s.get("checkIn").toString()) : in;
        LocalDate newOut = out == null ? LocalDate.parse(s.get("checkOut").toString()) : out;
        if (!newOut.isAfter(newIn)) {
            throw ApiException.bad("Check-out must be after check-in");
        }
        UUID target = spaceId == null ? UUID.fromString(s.get("spaceId").toString()) : spaceId;
        assertFree(target, newIn, newOut, id);
        db.exec("UPDATE stays SET space_id = :target, check_in = :in, check_out = :out WHERE id = :id",
                "id", id, "target", target, "in", newIn, "out", newOut);
    }

    private Map<String, Object> stay(UUID id) {
        return db.one("Stay", "SELECT * FROM stays WHERE id = :id", "id", id);
    }
}
