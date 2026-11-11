package com.lezitech.suite.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The owner's own choices about who does what (Settings, Advanced: "Who can do what").
 *
 * Defaults stay in RoleGuard and lib/roles.ts; this only applies where the owner changed something:
 * - which screens a job has (stored as settings.access.ROLE.modules), enforced here on the server too, so hiding a
 *   screen really closes it rather than just removing a menu item;
 * - whether a job may give refunds, or discounts without a manager's PIN.
 * The owner always keeps everything; staff and settings screens can't be handed out this way.
 */
@Component
public class AccessPolicy {

    /** Screens the owner may switch per job. Staff, billing and settings are deliberately not on the list. */
    public static final Set<String> GRANTABLE = Set.of("dashboard", "rooms", "trips", "property", "floor", "bookings", "pos", "kitchen",
            "crm", "quotes", "sales", "customers", "items", "stock", "purchasing", "reports");

    /**
     * API areas that belong to one screen. reads=false: the area's lists are also used elsewhere (the till looks up
     * items and customers), so only changes are closed when the screen is taken away.
     */
    record Area(Pattern path, String module, boolean reads) {
    }

    static final List<Area> AREAS = List.of(
            new Area(Pattern.compile("/api/reports/dashboard"), "dashboard", true),
            new Area(Pattern.compile("/api/reports/(sales|back-office|customers|tax|commissions).*"), "reports", true),
            new Area(Pattern.compile("/api/(pipeline|deals|activities)(/.*)?"), "crm", true),
            new Area(Pattern.compile("/api/(stock)(/.*)?"), "stock", true),
            new Area(Pattern.compile("/api/(purchases|suppliers)(/.*)?"), "purchasing", true),
            new Area(Pattern.compile("/api/property(/.*)?"), "property", true),
            new Area(Pattern.compile("/api/trips(/.*)?"), "trips", false),
            new Area(Pattern.compile("/api/(spaces|stays)(/.*)?"), "rooms", false),
            new Area(Pattern.compile("/api/appointments(/.*)?"), "bookings", false),
            new Area(Pattern.compile("/api/kitchen(/.*)?"), "kitchen", false),
            new Area(Pattern.compile("/api/(floors|tables)(/.*)?"), "floor", false),
            new Area(Pattern.compile("/api/(items|categories)(/.*)?"), "items", false),
            new Area(Pattern.compile("/api/customers(/.*)?"), "customers", false));

    public record Job(Set<String> modules, Boolean refunds, Boolean discounts) {
    }

    private record Cached(Map<String, Job> jobs, long at) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public AccessPolicy(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void forget(UUID tenant) {
        cache.remove(tenant);
    }

    /** The owner's settings for a job, or null where the defaults apply. */
    public Job job(UUID tenant, String role) {
        Cached c = cache.get(tenant);
        if (c == null || System.currentTimeMillis() - c.at() > 30_000) {
            c = new Cached(load(tenant), System.currentTimeMillis());
            cache.put(tenant, c);
        }
        return c.jobs().get(role);
    }

    private Map<String, Job> load(UUID tenant) {
        Map<String, Job> out = new LinkedHashMap<>();
        try {
            String raw = jdbc.queryForObject("SELECT settings -> 'access' FROM tenants WHERE id = ?", String.class, tenant);
            if (raw == null) {
                return out;
            }
            JsonNode access = json.readTree(raw);
            access.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                Set<String> mods = null;
                if (v.has("modules") && v.get("modules").isArray()) {
                    mods = new LinkedHashSet<>();
                    for (JsonNode m : v.get("modules")) {
                        mods.add(m.asText());
                    }
                }
                out.put(e.getKey(), new Job(mods, v.has("refunds") ? v.get("refunds").asBoolean() : null,
                        v.has("discounts") ? v.get("discounts").asBoolean() : null));
            });
        } catch (Exception e) {
            // unreadable settings: defaults apply
        }
        return out;
    }

    /**
     * For a request by a job the owner customised: TRUE = the owner gave this screen (allow), FALSE = the owner took it
     * away (refuse), null = not a screen-owned area or no change (the default rules decide).
     */
    public Boolean screenDecision(UUID tenant, String role, String method, String path) {
        Job j = job(tenant, role);
        if (j == null || j.modules() == null) {
            return null;
        }
        for (Area a : AREAS) {
            if (a.path().matcher(path).matches()) {
                boolean write = !"GET".equals(method);
                if (!write && !a.reads()) {
                    return null;
                }
                return j.modules().contains(a.module());
            }
        }
        return null;
    }

    public boolean mayRefund(UUID tenant, String role) {
        if ("OWNER".equals(role)) {
            return true;
        }
        Job j = job(tenant, role);
        return j != null && j.refunds() != null ? j.refunds() : "MANAGER".equals(role);
    }

    public boolean mayDiscount(UUID tenant, String role) {
        if ("OWNER".equals(role)) {
            return true;
        }
        Job j = job(tenant, role);
        return j != null && j.discounts() != null ? j.discounts() : "MANAGER".equals(role);
    }

    /** Checks what the owner sends in Settings before it is saved. */
    public static Map<String, Object> clean(Object raw, Set<String> roles) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) {
            return out;
        }
        m.forEach((k, v) -> {
            String role = String.valueOf(k).toUpperCase();
            if ("OWNER".equals(role) || !roles.contains(role) || !(v instanceof Map<?, ?> job)) {
                if (!roles.contains(role)) {
                    throw ApiException.bad("Unknown job " + k);
                }
                return;
            }
            Map<String, Object> j = new LinkedHashMap<>();
            if (job.get("modules") instanceof List<?> mods) {
                List<String> ok = mods.stream().map(String::valueOf).filter(GRANTABLE::contains).distinct().toList();
                j.put("modules", ok);
            }
            if (job.get("refunds") instanceof Boolean r) {
                j.put("refunds", r);
            }
            if (job.get("discounts") instanceof Boolean d) {
                j.put("discounts", d);
            }
            out.put(role, j);
        });
        return out;
    }
}
