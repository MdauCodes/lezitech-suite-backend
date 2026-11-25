package com.lezitech.suite.attendance;

import com.lezitech.suite.activity.ActivityLog;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.files.FileService;
import com.lezitech.suite.platform.PlanFeatures;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.lezitech.suite.attendance.AttendanceClock.NAIROBI;

/**
 * Staff attendance: clocking in and out at the branch (location plus a selfie or the shop code), the hours each branch
 * and person works, the day's board and the timesheets. Every time is the server's, in Nairobi time.
 */
@Service
public class AttendanceService {

    /** Worse than this and the phone doesn't know where it is well enough. */
    static final double MAX_ACCURACY_M = 150;
    static final double MAX_SET_ACCURACY_M = 100;
    /** A person can't move this fast between two clock events. */
    static final double MAX_SPEED_KMH = 150;
    static final int SELFIE_DAYS = 60;
    static final int MAX_ATTEMPTS = 10;
    static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(10);
    /** A forgotten clock-out is closed this long after the day's end, so a person finishing late can still clock out. */
    static final Duration CLOSE_AFTER = Duration.ofMinutes(30);

    public record Hours(LocalTime start, LocalTime end, int graceMin) {
    }

    private final Db db;
    private final FileService files;
    private final ActivityLog activity;
    private final PlanFeatures features;
    private final AttendanceClock clock;
    private final Map<UUID, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    public AttendanceService(Db db, FileService files, ActivityLog activity, PlanFeatures features, AttendanceClock clock) {
        this.db = db;
        this.files = files;
        this.activity = activity;
        this.features = features;
        this.clock = clock;
    }

    // ---------------- helpers ----------------

    static double meters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private static java.sql.Date date(LocalDate d) {
        return java.sql.Date.valueOf(d);
    }

    private static double dbl(Object o) {
        return ((Number) o).doubleValue();
    }

    static String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    /** Where a person works: their branch, or the main place. */
    UUID branchOf(UUID user) {
        UUID id = db.value("""
                SELECT coalesce((SELECT location_id FROM users WHERE id = :user), (SELECT id FROM locations WHERE is_default LIMIT 1))""",
                UUID.class, "user", user);
        if (id == null) {
            throw ApiException.bad("There is no branch to clock in at yet");
        }
        return id;
    }

    Map<String, Object> branch(UUID id) {
        return db.one("Branch", """
                SELECT id, name, lat, lng, radius_m, att_method AS method, att_till_needs_clock AS till_needs_clock,
                       att_allow_printed AS allow_printed, att_secret, att_print_token FROM locations WHERE id = :id""", "id", id);
    }

    /** The key behind the shop code, made the first time it is needed. */
    String secretOf(UUID branch) {
        Map<String, Object> b = branch(branch);
        String s = (String) b.get("attSecret");
        if (s == null) {
            s = ShopCode.newSecret();
            db.exec("UPDATE locations SET att_secret = :s WHERE id = :id AND att_secret IS NULL", "s", s, "id", branch);
            s = (String) branch(branch).get("attSecret");
        }
        return s;
    }

    String printTokenOf(UUID branch) {
        String s = (String) branch(branch).get("attPrintToken");
        if (s == null) {
            s = ShopCode.newSecret().substring(0, 32);
            db.exec("UPDATE locations SET att_print_token = :s WHERE id = :id AND att_print_token IS NULL", "s", s, "id", branch);
            s = (String) branch(branch).get("attPrintToken");
        }
        return s;
    }

    /** The hours that apply to a person on a weekday: their own if they have any set, else the branch's. */
    Hours hoursFor(UUID user, UUID branch, int weekday) {
        long own = db.value("SELECT count(*) FROM attendance_hours WHERE location_id = :b AND user_id = :user", Long.class, "b", branch, "user", user);
        Map<String, Object> row = (own > 0
                ? db.find("""
                    SELECT to_char(start_time, 'HH24:MI') AS s, to_char(end_time, 'HH24:MI') AS e, grace_min FROM attendance_hours
                    WHERE location_id = :b AND user_id = :user AND weekday = :wd""", "b", branch, "user", user, "wd", weekday)
                : db.find("""
                    SELECT to_char(start_time, 'HH24:MI') AS s, to_char(end_time, 'HH24:MI') AS e, grace_min FROM attendance_hours
                    WHERE location_id = :b AND user_id IS NULL AND weekday = :wd""", "b", branch, "wd", weekday)).orElse(null);
        return row == null ? null : new Hours(LocalTime.parse((String) row.get("s")), LocalTime.parse((String) row.get("e")), ((Number) row.get("graceMin")).intValue());
    }

    private static Map<String, Object> hoursJson(Hours h) {
        if (h == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", hhmm(h.start()));
        m.put("end", hhmm(h.end()));
        m.put("graceMin", h.graceMin());
        return m;
    }

    /** When a shift that belongs to {@code day} starts and ends (an end earlier than the start is the next morning). */
    static Instant startOf(LocalDate day, Hours h) {
        return day.atTime(h.start()).atZone(NAIROBI).toInstant();
    }

    static Instant endOf(LocalDate day, Hours h) {
        LocalDate d = h.end().isAfter(h.start()) ? day : day.plusDays(1);
        return d.atTime(h.end()).atZone(NAIROBI).toInstant();
    }

    /** The work day a clock-in belongs to: today, or yesterday while yesterday's night shift is still running (end + 30 min). */
    private LocalDate workDayForIn(UUID user, UUID branch, Instant now) {
        LocalDate today = LocalDateTime.ofInstant(now, NAIROBI).toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        Hours y = hoursFor(user, branch, yesterday.getDayOfWeek().getValue());
        if (y != null && !y.end().isAfter(y.start()) && now.isBefore(endOf(yesterday, y).plus(CLOSE_AFTER))) {
            return yesterday;
        }
        return today;
    }

    private void limitAttempts(UUID user) {
        Instant now = clock.now();
        Deque<Instant> q = attempts.computeIfAbsent(user, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(ATTEMPT_WINDOW))) {
                q.removeFirst();
            }
            if (q.size() >= MAX_ATTEMPTS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_TRIES", "Too many tries. Wait a few minutes, then try again.");
            }
            q.addLast(now);
        }
    }

    // ---------------- clocking ----------------

    private Map<String, Object> lastEvent(UUID user) {
        return db.find("SELECT id, kind, at, day, lat, lng, location_id FROM attendance_events WHERE user_id = :user ORDER BY at DESC, kind DESC LIMIT 1", "user", user).orElse(null);
    }

    /** What the staff phone shows: where I clock, my state and today's hours. */
    public Map<String, Object> me() {
        UUID user = TenantContext.userId();
        UUID b = branchOf(user);
        Map<String, Object> br = branch(b);
        Instant now = clock.now();
        LocalDate today = LocalDateTime.ofInstant(now, NAIROBI).toLocalDate();
        Map<String, Object> last = lastEvent(user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", Map.of("id", b, "name", br.get("name"), "hasLocation", br.get("lat") != null, "radiusM", br.get("radiusM")));
        String method = (String) br.get("method");
        out.put("canSelfie", !"CODE".equals(method));
        out.put("canCode", !"SELFIE".equals(method));
        out.put("clockedIn", last != null && "IN".equals(last.get("kind")));
        out.put("since", last == null ? null : last.get("at"));
        out.put("today", hoursJson(hoursFor(user, b, today.getDayOfWeek().getValue())));
        out.put("tillNeedsClock", br.get("tillNeedsClock"));
        out.put("recent", db.list("""
                SELECT kind, at, method, flags, late_min, early_min FROM attendance_events WHERE user_id = :user ORDER BY at DESC LIMIT 8""", "user", user));
        return out;
    }

    /** {kind: IN|OUT, lat, lng, accuracy, code, selfie (base64 photo)}: the signed-in person clocks themselves. */
    public Map<String, Object> clock(Map<String, Object> raw) {
        TenantContext.Caller caller = TenantContext.caller();
        UUID user = caller.userId();
        limitAttempts(user);
        Body body = new Body(raw);
        String kind = body.str("kind", "").toUpperCase();
        if (!kind.equals("IN") && !kind.equals("OUT")) {
            throw ApiException.bad("Choose Clock in or Clock out");
        }
        UUID b = branchOf(user);
        Map<String, Object> br = branch(b);
        if (br.get("lat") == null) {
            throw ApiException.bad("This branch's location is not set yet. Ask your manager to set it, then try again.");
        }
        if (!body.has("lat") || !body.has("lng") || !body.has("accuracy")) {
            throw ApiException.bad("Your phone did not tell us where you are. Turn on location for this page, then try again.");
        }
        double lat = body.num("lat").doubleValue(), lng = body.num("lng").doubleValue(), acc = body.num("accuracy").doubleValue();
        if (Math.abs(lat) > 90 || Math.abs(lng) > 180 || acc < 0) {
            throw ApiException.bad("Your phone did not tell us where you are. Turn on location for this page, then try again.");
        }
        if (acc > MAX_ACCURACY_M) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOW_ACCURACY",
                    "Your phone can't find where you are well enough. Step near a door or window and try again.");
        }
        double dist = meters(lat, lng, dbl(br.get("lat")), dbl(br.get("lng")));
        int radius = ((Number) br.get("radiusM")).intValue();
        if (dist > radius) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OUTSIDE_RADIUS", "You are about " + Math.round(dist)
                    + " m from " + br.get("name") + ". You can clock " + (kind.equals("IN") ? "in" : "out") + " only at the shop.");
        }
        Map<String, Object> last = lastEvent(user);
        boolean open = last != null && "IN".equals(last.get("kind"));
        if (kind.equals("IN") && open) {
            throw ApiException.conflict("You are already clocked in.");
        }
        if (kind.equals("OUT") && !open) {
            throw ApiException.conflict("You are not clocked in.");
        }

        // The proof that they are at the shop.
        String allowed = (String) br.get("method");
        String given = body.str("code", null);
        String selfie = body.str("selfie", null);
        String method;
        Instant now = clock.now();
        if (given != null && !"SELFIE".equals(allowed)) {
            boolean printed = ShopCode.isPrinted(given);
            String bare = ShopCode.bare(given);
            if (printed) {
                String token = Boolean.TRUE.equals(br.get("allowPrinted")) ? (String) br.get("attPrintToken") : null;
                if (token == null || !ShopCode.same(token, bare)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CODE", "That printed code is not accepted here. Scan the code on the shop screen.");
                }
                method = "PRINTED";
            } else {
                if (!ShopCode.valid(secretOf(b), b, bare, now)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_CODE", "That shop code is wrong or has run out. Scan the screen again.");
                }
                method = "CODE";
            }
        } else if (selfie != null && !"CODE".equals(allowed)) {
            method = "SELFIE";
        } else if ("SELFIE".equals(allowed)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELFIE_NEEDED", "Take a selfie at the shop to clock " + (kind.equals("IN") ? "in" : "out") + ".");
        } else if ("CODE".equals(allowed)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CODE_NEEDED", "Scan the shop code to clock " + (kind.equals("IN") ? "in" : "out") + ".");
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROOF_NEEDED", "Take a selfie or scan the shop code to clock " + (kind.equals("IN") ? "in" : "out") + ".");
        }

        UUID file = null;
        if (method.equals("SELFIE")) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(selfie.contains(",") ? selfie.substring(selfie.indexOf(',') + 1) : selfie);
            } catch (IllegalArgumentException e) {
                throw ApiException.bad("That photo can't be used. Take it again.");
            }
            file = files.save(FileService.Purpose.ATTENDANCE_SELFIE, bytes);
        }

        // Signs of a fake location are flagged for the owner, not blocked.
        List<String> flags = new ArrayList<>();
        if (acc == 0) {
            flags.add("ZERO_ACCURACY");
        }
        if (last != null && last.get("lat") != null) {
            double jump = meters(dbl(last.get("lat")), dbl(last.get("lng")), lat, lng);
            double secs = Math.max(1, Duration.between(Instant.parse((String) last.get("at")), now).toSeconds());
            if (jump > 300 && jump / secs * 3.6 > MAX_SPEED_KMH) {
                flags.add("LOCATION_JUMP");
            }
        }

        // An OUT closes the day its clock-in started; an IN starts today's day (or the night shift still running from yesterday).
        LocalDate day = kind.equals("OUT") ? LocalDate.parse((String) last.get("day")) : workDayForIn(user, b, now);
        Hours hours = hoursFor(user, b, day.getDayOfWeek().getValue());
        Integer lateMin = null, earlyMin = null;
        // Late: only the day's first clock-in (coming back from a break is never late). Early: judged on the last
        // clock-out once the day has closed, when the timesheet is read.
        if (hours != null && kind.equals("IN")) {
            boolean first = db.value("SELECT count(*) FROM attendance_events WHERE user_id = :user AND day = :d AND kind = 'IN'", Long.class,
                    "user", user, "d", date(day)) == 0;
            long after = Duration.between(startOf(day, hours), now).toMinutes();
            if (first && after > hours.graceMin()) {
                flags.add("LATE");
                lateMin = (int) after;
            }
        }

        UUID id = db.insert("""
                INSERT INTO attendance_events (tenant_id, user_id, user_name, location_id, kind, at, day, lat, lng, accuracy_m, distance_m, method,
                                               selfie_file_id, flags, late_min, early_min)
                VALUES (:t, :u, :name, :loc, :kind, :at, :day, :lat, :lng, :acc, :dist, :method, :file, cast(:flags as text[]), :late, :early) RETURNING id""",
                "name", caller.name(), "loc", b, "kind", kind, "at", ts(now), "day", date(day), "lat", lat, "lng", lng, "acc", acc,
                "dist", dist, "method", method, "file", file, "flags", "{" + String.join(",", flags) + "}", "late", lateMin, "early", earlyMin);
        if (flags.contains("ZERO_ACCURACY") || flags.contains("LOCATION_JUMP")) {
            activity.record("ATTENDANCE_FLAG", ActivityLog.CONCERN, caller.name() + " clocked " + kind.toLowerCase() + " with a location that looks wrong ("
                    + (flags.contains("ZERO_ACCURACY") ? "accuracy reported as exactly 0" : "moved too far, too fast") + ")",
                    null, null, null, null, Map.of("eventId", id.toString(), "flags", flags));
        }
        Map<String, Object> out = me();
        out.put("event", Map.of("id", id, "kind", kind, "at", now.toString(), "method", method, "distanceM", Math.round(dist), "flags", flags));
        return out;
    }

    /** For the till: when the branch asks for it, a staff member has to be clocked in first. */
    public void requireClockedInForTill() {
        TenantContext.Caller c = TenantContext.callerOrNull();
        if (c == null || "OWNER".equals(c.role()) || c.userId() == null) {
            return;
        }
        UUID b = branchOf(c.userId());
        if (!Boolean.TRUE.equals(branch(b).get("tillNeedsClock")) || !features.has(c.tenantId(), "attendance")) {
            return;
        }
        Map<String, Object> last = lastEvent(c.userId());
        if (last == null || !"IN".equals(last.get("kind"))) {
            throw ApiException.conflict("Clock in first. Open Attendance, tap Clock in, then open your till.");
        }
    }

    // ---------------- the day's board and timesheets ----------------

    /** Staff who are on a branch's roll: everyone active except the owner, unless the owner has their own hours. */
    private List<Map<String, Object>> roll(UUID onlyBranch) {
        List<Map<String, Object>> rows = db.list("""
                SELECT u.id, u.name, u.role, coalesce(u.location_id, (SELECT id FROM locations WHERE is_default LIMIT 1)) AS branch_id
                FROM users u WHERE u.active AND u.tenant_id = :t AND u.role <> 'OWNER' ORDER BY u.name""");
        if (onlyBranch != null) {
            rows.removeIf(r -> !onlyBranch.toString().equals(r.get("branchId")));
        }
        return rows;
    }

    /** Today (or a given day): who is in, who is late, who has not come yet. */
    public Map<String, Object> board(LocalDate day, UUID onlyBranch) {
        Instant now = clock.now();
        LocalDateTime local = LocalDateTime.ofInstant(now, NAIROBI);
        LocalDate today = local.toLocalDate();
        List<Map<String, Object>> people = new ArrayList<>();
        int in = 0, late = 0, absent = 0, notYet = 0, out = 0;
        for (Map<String, Object> u : roll(onlyBranch)) {
            UUID id = UUID.fromString((String) u.get("id"));
            UUID b = UUID.fromString((String) u.get("branchId"));
            LocalDate shown = day;
            if (day.equals(today)) { // still in from last night's shift: that day's record is the one to show
                Map<String, Object> lastOne = lastEventOf(id);
                if (lastOne != null && "IN".equals(lastOne.get("kind")) && LocalDate.parse((String) lastOne.get("day")).equals(day.minusDays(1))) {
                    shown = day.minusDays(1);
                }
            }
            Hours h = hoursFor(id, b, shown.getDayOfWeek().getValue());
            List<Map<String, Object>> ev = events(id, shown, shown);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", id);
            row.put("name", u.get("name"));
            row.put("role", u.get("role"));
            row.put("branchId", b);
            row.put("hours", hoursJson(h));
            Map<String, Object> firstIn = ev.stream().filter(e -> "IN".equals(e.get("kind"))).findFirst().orElse(null);
            Map<String, Object> lastEv = ev.isEmpty() ? null : ev.get(ev.size() - 1);
            String status;
            if (firstIn != null) {
                boolean stillIn = "IN".equals(lastEv.get("kind"));
                status = stillIn ? "IN" : "OUT";
                if (stillIn) {
                    in++;
                } else {
                    out++;
                }
                row.put("inAt", firstIn.get("at"));
                row.put("selfieFileId", firstIn.get("selfieFileId"));
                row.put("method", firstIn.get("method"));
                if (!stillIn) {
                    row.put("outAt", lastEv.get("at"));
                    row.put("noClockOut", ((List<?>) lastEv.get("flags")).contains("NO_CLOCK_OUT"));
                }
                if (firstIn.get("lateMin") != null) {
                    late++;
                    row.put("lateMin", firstIn.get("lateMin"));
                }
                row.put("flags", firstIn.get("flags"));
            } else if (h == null) {
                status = "OFF";
            } else if (now.isAfter(endOf(day, h))) {
                status = "ABSENT";
                absent++;
            } else {
                status = "NOT_YET";
                notYet++;
            }
            row.put("status", status);
            people.add(row);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("day", day.toString());
        res.put("people", people);
        res.put("counts", Map.of("in", in, "out", out, "late", late, "absent", absent, "notYet", notYet));
        return res;
    }

    private Map<String, Object> lastEventOf(UUID user) {
        return db.find("SELECT kind, day FROM attendance_events WHERE user_id = :user ORDER BY at DESC, kind DESC LIMIT 1", "user", user).orElse(null);
    }

    private List<Map<String, Object>> events(UUID user, LocalDate from, LocalDate to) {
        return db.list("""
                SELECT id, kind, at, day, method, selfie_file_id, flags, late_min, early_min, distance_m, accuracy_m FROM attendance_events
                WHERE user_id = :user AND day >= :from AND day <= :to ORDER BY at, kind DESC""", "user", user, "from", date(from), "to", date(to));
    }

    /** One person's days: hours worked, lateness, early leaves, absences, with the photos. */
    public Map<String, Object> timesheet(UUID user, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(62).isBefore(to)) {
            throw ApiException.bad("Choose up to two months at a time");
        }
        Map<String, Object> u = db.one("Staff member", "SELECT id, name, role FROM users WHERE id = :id AND tenant_id = :t", "id", user);
        UUID b = branchOf(user);
        Instant now = clock.now();
        LocalDateTime local = LocalDateTime.ofInstant(now, NAIROBI);
        LocalDate today = local.toLocalDate();
        List<Map<String, Object>> all = events(user, from, to);
        List<Map<String, Object>> days = new ArrayList<>();
        long minutes = 0;
        int worked = 0, lateDays = 0, early = 0, absent = 0, noOut = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            LocalDate dd = d;
            List<Map<String, Object>> ev = all.stream().filter(e -> e.get("day").equals(dd.toString())).toList();
            Hours h = hoursFor(user, b, d.getDayOfWeek().getValue());
            if (ev.isEmpty() && h == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", d.toString());
            row.put("hours", hoursJson(h));
            long mins = 0;
            Instant openAt = null;
            Integer lateMin = null, earlyMin = null;
            boolean didntOut = false;
            Instant lastOutAt = null;
            boolean lastOutAuto = false;
            long breakMin = 0;
            List<Map<String, Object>> breaks = new ArrayList<>();
            for (Map<String, Object> e : ev) {
                Instant at = Instant.parse((String) e.get("at"));
                if ("IN".equals(e.get("kind"))) {
                    if (lastOutAt != null) {
                        long m = Duration.between(lastOutAt, at).toMinutes();
                        breakMin += m;
                        Map<String, Object> br = new LinkedHashMap<>();
                        br.put("from", lastOutAt.toString());
                        br.put("to", at.toString());
                        br.put("minutes", m);
                        breaks.add(br);
                    }
                    openAt = at;
                    if (lateMin == null && e.get("lateMin") != null) {
                        lateMin = ((Number) e.get("lateMin")).intValue();
                    }
                } else if (openAt != null) {
                    mins += Duration.between(openAt, at).toMinutes();
                    openAt = null;
                    lastOutAt = at;
                    lastOutAuto = "AUTO".equals(e.get("method"));
                    if (lastOutAuto) {
                        didntOut = true;
                    }
                }
            }
            boolean stillIn = openAt != null;
            boolean isAbsent = ev.isEmpty() && h != null && now.isAfter(endOf(d, h));
            // Left early: the day's last clock-out, judged once the day has closed.
            if (h != null && openAt == null && lastOutAt != null && !lastOutAuto && now.isAfter(endOf(d, h))) {
                long before = Duration.between(lastOutAt, endOf(d, h)).toMinutes();
                if (before >= 1) {
                    earlyMin = (int) before;
                }
            }
            if (ev.isEmpty() && !isAbsent) {
                continue; // a scheduled day that has not happened yet
            }
            row.put("events", ev);
            row.put("minutes", mins);
            row.put("stillIn", stillIn);
            row.put("lateMin", lateMin);
            row.put("earlyMin", earlyMin);
            row.put("noClockOut", didntOut);
            row.put("breakMinutes", breakMin);
            row.put("breaks", breaks);
            row.put("absent", isAbsent);
            minutes += mins;
            if (!ev.isEmpty()) {
                worked++;
            }
            if (lateMin != null) {
                lateDays++;
            }
            if (earlyMin != null) {
                early++;
            }
            if (isAbsent) {
                absent++;
            }
            if (didntOut) {
                noOut++;
            }
            days.add(row);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("minutes", minutes);
        totals.put("hours", Math.round(minutes / 6.0) / 10.0);
        totals.put("daysWorked", worked);
        totals.put("daysLate", lateDays);
        totals.put("earlyLeaves", early);
        totals.put("absences", absent);
        totals.put("noClockOut", noOut);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", u);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("days", days);
        out.put("totals", totals);
        return out;
    }

    /** Everyone's totals for a period, one row each (the download). */
    public Map<String, Object> timesheets(LocalDate from, LocalDate to, UUID onlyBranch) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> u : roll(onlyBranch)) {
            Map<String, Object> t = timesheet(UUID.fromString((String) u.get("id")), from, to);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", u.get("id"));
            row.put("name", u.get("name"));
            row.putAll((Map<String, Object>) t.get("totals"));
            rows.add(row);
        }
        return Map.of("from", from.toString(), "to", to.toString(), "staff", rows);
    }

    /** The owner's night summary line, or null when the business does not use attendance. */
    public Map<String, Object> nightLine(LocalDate day) {
        if (!features.has("attendance")) {
            return null;
        }
        long set = db.value("SELECT count(*) FROM locations WHERE lat IS NOT NULL", Long.class);
        if (set == 0) {
            return null;
        }
        Map<String, Object> board = board(day, null);
        List<String> lates = new ArrayList<>();
        int in = 0, absent = 0;
        for (Map<String, Object> p : (List<Map<String, Object>>) board.get("people")) {
            String s = (String) p.get("status");
            if (s.equals("IN") || s.equals("OUT")) {
                in++;
                if (p.get("lateMin") != null) {
                    lates.add(String.valueOf(p.get("name")).split(" ")[0] + " " + p.get("lateMin") + " min");
                }
            } else if (s.equals("ABSENT")) {
                absent++;
            }
        }
        String text = "Staff: " + in + " in" + (lates.isEmpty() ? "" : ", " + lates.size() + " late (" + String.join(", ", lates) + ")")
                + (absent > 0 ? ", " + absent + " absent" : "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("in", in);
        out.put("late", lates.size());
        out.put("absent", absent);
        out.put("text", text);
        return out;
    }

    // ---------------- setting up a branch ----------------

    public List<Map<String, Object>> branches() {
        List<Map<String, Object>> rows = db.list("""
                SELECT l.id, l.name, l.is_default AS main, l.lat, l.lng, l.radius_m, l.att_method AS method,
                       l.att_till_needs_clock AS till_needs_clock, l.att_allow_printed AS allow_printed
                FROM locations l WHERE l.parent_id IS NULL AND l.active AND (l.kind = 'SHOP' OR l.is_default)
                ORDER BY l.is_default DESC, l.name""");
        for (Map<String, Object> r : rows) {
            r.put("hasLocation", r.get("lat") != null);
            r.put("hours", db.list("""
                    SELECT weekday, to_char(start_time, 'HH24:MI') AS start, to_char(end_time, 'HH24:MI') AS "end", grace_min FROM attendance_hours
                    WHERE location_id = :b AND user_id IS NULL ORDER BY weekday""", "b", r.get("id")));
        }
        return rows;
    }

    /** {lat, lng, accuracy, radiusM?}: the manager stands in the shop and saves where it is. */
    public Map<String, Object> setLocation(UUID id, Map<String, Object> raw) {
        Body b = new Body(raw);
        branch(id);
        double lat = b.num("lat").doubleValue(), lng = b.num("lng").doubleValue();
        double acc = b.num("accuracy", java.math.BigDecimal.valueOf(Double.MAX_VALUE)).doubleValue();
        if (Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            throw ApiException.bad("That is not a place on the map");
        }
        if (acc > MAX_SET_ACCURACY_M) {
            throw ApiException.bad("Your phone can't find where you are well enough to mark the shop. Step near a door or window and try again.");
        }
        int radius = b.integer("radiusM", 100);
        checkRadius(radius);
        db.exec("UPDATE locations SET lat = :lat, lng = :lng, radius_m = :r WHERE id = :id", "lat", lat, "lng", lng, "r", radius, "id", id);
        db.exec("UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{attendance}', 'true'::jsonb) WHERE id = :t");
        return branches().stream().filter(x -> id.toString().equals(x.get("id"))).findFirst().orElseThrow();
    }

    private static void checkRadius(int radius) {
        if (radius < 30 || radius > 500) {
            throw ApiException.bad("The distance from the shop must be between 30 and 500 metres");
        }
    }

    /** {radiusM?, method?, tillNeedsClock?, allowPrinted?} */
    public Map<String, Object> setRules(UUID id, Map<String, Object> raw) {
        Body b = new Body(raw);
        Map<String, Object> cur = branch(id);
        int radius = b.integer("radiusM", ((Number) cur.get("radiusM")).intValue());
        checkRadius(radius);
        String method = b.str("method", (String) cur.get("method")).toUpperCase();
        if (!List.of("SELFIE", "CODE", "EITHER").contains(method)) {
            throw ApiException.bad("Choose Selfie at the shop, Scan the shop code, or Either");
        }
        db.exec("UPDATE locations SET radius_m = :r, att_method = :m, att_till_needs_clock = :till, att_allow_printed = :p WHERE id = :id",
                "r", radius, "m", method, "till", b.bool("tillNeedsClock", Boolean.TRUE.equals(cur.get("tillNeedsClock"))),
                "p", b.bool("allowPrinted", Boolean.TRUE.equals(cur.get("allowPrinted"))), "id", id);
        return branches().stream().filter(x -> id.toString().equals(x.get("id"))).findFirst().orElseThrow();
    }

    /** {days: [{weekday 1-7, start "HH:mm", end, graceMin}]} for a branch, or for one person when {@code user} is given. */
    public List<Map<String, Object>> setHours(UUID branch, UUID user, Map<String, Object> raw) {
        branch(branch);
        if (user != null) {
            db.one("Staff member", "SELECT id FROM users WHERE id = :id AND tenant_id = :t", "id", user);
        }
        Object days = raw == null ? null : raw.get("days");
        if (!(days instanceof List<?> list)) {
            throw ApiException.bad("Send the days to work");
        }
        List<Object[]> rows = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Object o : list) {
            @SuppressWarnings("unchecked")
            Body d = new Body((Map<String, Object>) o);
            int wd = d.integer("weekday", 0);
            if (wd < 1 || wd > 7 || !seen.add(wd)) {
                throw ApiException.bad("Each day of the week can be set once");
            }
            LocalTime s, e;
            try {
                s = LocalTime.parse(d.str("start"));
                e = LocalTime.parse(d.str("end"));
            } catch (java.time.format.DateTimeParseException ex) {
                throw ApiException.bad("Times look like 08:00");
            }
            if (e.equals(s)) {
                throw ApiException.bad("The finishing time can't be the same as the starting time. For a night shift, finish earlier than you start, e.g. 18:00 to 02:00.");
            }
            int grace = d.integer("graceMin", 10);
            if (grace < 0 || grace > 120) {
                throw ApiException.bad("Minutes of grace must be between 0 and 120");
            }
            rows.add(new Object[]{wd, s.toString(), e.toString(), grace});
        }
        if (user == null) {
            db.exec("DELETE FROM attendance_hours WHERE location_id = :b AND user_id IS NULL", "b", branch);
        } else {
            db.exec("DELETE FROM attendance_hours WHERE location_id = :b AND user_id = :user", "b", branch, "user", user);
        }
        for (Object[] r : rows) {
            db.exec("""
                    INSERT INTO attendance_hours (tenant_id, location_id, user_id, weekday, start_time, end_time, grace_min)
                    VALUES (:t, :b, :user, :wd, cast(:s as time), cast(:e as time), :g)""",
                    "b", branch, "user", user, "wd", r[0], "s", r[1], "e", r[2], "g", r[3]);
        }
        return hoursList(branch, user);
    }

    public List<Map<String, Object>> hoursList(UUID branch, UUID user) {
        return user == null
                ? db.list("""
                    SELECT weekday, to_char(start_time, 'HH24:MI') AS start, to_char(end_time, 'HH24:MI') AS "end", grace_min FROM attendance_hours
                    WHERE location_id = :b AND user_id IS NULL ORDER BY weekday""", "b", branch)
                : db.list("""
                    SELECT weekday, to_char(start_time, 'HH24:MI') AS start, to_char(end_time, 'HH24:MI') AS "end", grace_min FROM attendance_hours
                    WHERE location_id = :b AND user_id = :user ORDER BY weekday""", "b", branch, "user", user);
    }

    // ---------------- the shop code, printed code and screen links ----------------

    public Map<String, Object> liveCode(UUID branch) {
        Instant now = clock.now();
        String code = ShopCode.current(secretOf(branch), branch, now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", branch(branch).get("name"));
        out.put("code", code);
        out.put("qr", ShopCode.qr(code));
        out.put("secondsLeft", ShopCode.secondsLeft(now));
        return out;
    }

    public Map<String, Object> rotateSecret(UUID branch) {
        branch(branch);
        db.exec("UPDATE locations SET att_secret = :s WHERE id = :id", "s", ShopCode.newSecret(), "id", branch);
        return liveCode(branch);
    }

    public Map<String, Object> printed(UUID branch, boolean rotate) {
        branch(branch);
        if (rotate) {
            db.exec("UPDATE locations SET att_print_token = :s WHERE id = :id", "s", ShopCode.newSecret().substring(0, 32), "id", branch);
        }
        String token = printTokenOf(branch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", branch(branch).get("name"));
        out.put("qr", ShopCode.printedQr(token));
        out.put("accepted", branch(branch).get("allowPrinted"));
        out.put("warning", "A printed code can be photographed and shared, so it is weaker than the screen code. Staff still have to be at the shop, because their location is always checked.");
        return out;
    }

    /** A link for a shop screen that can only show the code. Returned once; only a hash is kept. */
    public Map<String, Object> newKiosk(UUID branch, String label) {
        branch(branch);
        String slug = db.value("SELECT slug FROM tenants WHERE id = :t", String.class);
        String token = slug + "." + ShopCode.newSecret().substring(0, 32);
        UUID id = db.insert("INSERT INTO attendance_kiosks (tenant_id, location_id, label, token_hash) VALUES (:t, :b, :label, :h) RETURNING id",
                "b", branch, "label", label == null || label.isBlank() ? "Shop screen" : label.trim().substring(0, Math.min(80, label.trim().length())), "h", hash(token));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("token", token);
        out.put("path", "/attendance-screen/" + token);
        return out;
    }

    public List<Map<String, Object>> kiosks(UUID branch) {
        return db.list("SELECT id, label, created_at, revoked_at FROM attendance_kiosks WHERE location_id = :b ORDER BY created_at DESC", "b", branch);
    }

    public void revokeKiosk(UUID id) {
        if (db.exec("UPDATE attendance_kiosks SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL", "id", id) == 0) {
            throw ApiException.notFound("Screen link");
        }
    }

    static String hash(String token) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** For the public screen: the code for the branch a live link belongs to (inside the business's own context). */
    public Map<String, Object> screenFor(String token) {
        Map<String, Object> k = db.find("SELECT location_id, revoked_at FROM attendance_kiosks WHERE token_hash = :h", "h", hash(token)).orElse(null);
        if (k == null || k.get("revokedAt") != null) {
            throw ApiException.notFound("Screen link");
        }
        features.require("attendance");
        return liveCode(UUID.fromString((String) k.get("locationId")));
    }

    // ---------------- the nightly jobs ----------------

    /** Close days where someone forgot to clock out: the day ends at the end time, marked "Didn't clock out". */
    public int closeForgotten() {
        Instant now = clock.now();
        int closed = 0;
        for (Map<String, Object> e : db.list("""
                SELECT e.id, e.user_id, e.user_name, e.location_id, e.at, e.day FROM attendance_events e
                WHERE e.kind = 'IN' AND NOT EXISTS (SELECT 1 FROM attendance_events o WHERE o.user_id = e.user_id AND o.at > e.at)""")) {
            UUID user = UUID.fromString((String) e.get("userId"));
            UUID loc = UUID.fromString((String) e.get("locationId"));
            LocalDate day = LocalDate.parse((String) e.get("day"));
            Instant in = Instant.parse((String) e.get("at"));
            Hours h = hoursFor(user, loc, day.getDayOfWeek().getValue());
            Instant closeAt = h == null ? day.atTime(23, 59).atZone(NAIROBI).toInstant() : endOf(day, h);
            if (closeAt.isBefore(in)) {
                closeAt = in;
            }
            if (now.isBefore(closeAt.plus(CLOSE_AFTER))) {
                continue;
            }
            db.exec("""
                    INSERT INTO attendance_events (tenant_id, user_id, user_name, location_id, kind, at, day, method, flags)
                    VALUES (:t, :user, :name, :loc, 'OUT', :at, :day, 'AUTO', '{NO_CLOCK_OUT}')""",
                    "user", user, "name", e.get("userName"), "loc", loc, "at", ts(closeAt), "day", date(day));
            closed++;
        }
        return closed;
    }

    /** Delete selfies older than 60 days (the clock record stays, the photo goes). */
    public int purgeSelfies() {
        Instant cutoff = clock.now().minus(Duration.ofDays(SELFIE_DAYS));
        int n = 0;
        for (Map<String, Object> e : db.list("SELECT id, selfie_file_id FROM attendance_events WHERE selfie_file_id IS NOT NULL AND at < :cut", "cut", ts(cutoff))) {
            files.remove(UUID.fromString((String) e.get("selfieFileId")));
            db.exec("UPDATE attendance_events SET selfie_file_id = NULL WHERE id = :id", "id", e.get("id"));
            n++;
        }
        return n;
    }

    public LocalDate today() {
        return LocalDateTime.ofInstant(clock.now(), NAIROBI).toLocalDate();
    }
}
