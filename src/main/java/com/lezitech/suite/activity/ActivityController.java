package com.lezitech.suite.activity;

import com.lezitech.suite.common.Db;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The activity log for owners and managers: filter by person, kind or "concerning only", with totals per person. */
@RestController
@Transactional(readOnly = true)
public class ActivityController {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private final Db db;

    public ActivityController(Db db) {
        this.db = db;
    }

    @GetMapping("/api/activity")
    public Map<String, Object> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                    @RequestParam(required = false) UUID userId, @RequestParam(required = false) String kind,
                                    @RequestParam(defaultValue = "false") boolean concerning) {
        LocalDate f = from == null ? LocalDate.now(NAIROBI).minusDays(6) : from;
        LocalDate t = to == null ? LocalDate.now(NAIROBI) : to;
        Object[] p = {"from", Timestamp.from(f.atStartOfDay(NAIROBI).toInstant()), "to", Timestamp.from(t.plusDays(1).atStartOfDay(NAIROBI).toInstant()),
                "user", userId, "kind", kind == null || kind.isBlank() ? null : kind.toUpperCase(), "concerning", concerning};
        String where = """
                created_at >= :from AND created_at < :to
                  AND (cast(:user as uuid) IS NULL OR user_id = cast(:user as uuid))
                  AND (cast(:kind as text) IS NULL OR kind = cast(:kind as text))
                  AND (NOT :concerning OR severity = 'CONCERN')""";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("entries", db.list("SELECT * FROM activity_log WHERE " + where + " ORDER BY created_at DESC LIMIT 500", p));
        out.put("byPerson", db.list("""
                SELECT user_id, coalesce(user_name, 'Someone') AS name, count(*) AS events,
                       count(*) FILTER (WHERE severity = 'CONCERN') AS concerns,
                       coalesce(sum(amount) FILTER (WHERE kind IN ('PRICE_CHANGE', 'BELOW_LOWEST_PRICE', 'DISCOUNT')), 0) AS given_up,
                       coalesce(sum(amount) FILTER (WHERE kind = 'REFUND'), 0) AS refunded,
                       coalesce(sum(amount) FILTER (WHERE kind = 'CASH_SHORT'), 0) AS cash_short
                FROM activity_log WHERE """ + " " + where + " GROUP BY user_id, user_name ORDER BY concerns DESC, given_up DESC", p));
        out.put("totals", db.one("Totals", """
                SELECT count(*) AS events, count(*) FILTER (WHERE severity = 'CONCERN') AS concerns,
                       coalesce(sum(amount) FILTER (WHERE kind IN ('PRICE_CHANGE', 'BELOW_LOWEST_PRICE', 'DISCOUNT')), 0) AS given_up
                FROM activity_log WHERE """ + " " + where, p));
        return out;
    }
}
