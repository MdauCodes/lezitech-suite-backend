package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Order-status screens per area (e.g. "Terrace TV"): the owner names a screen and gives it tables, and the screen shows
 * only those tables' orders. Each screen has its own secret key in its link, since boards show customers' names.
 * The restaurant-wide board (Settings, display key) keeps showing everything.
 */
@RestController
@RequestMapping("/api/board-screens")
@Transactional
public class BoardScreenController {

    static final int MAX_SCREENS = 20;

    private final Db db;

    public BoardScreenController(Db db) {
        this.db = db;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        TenantContext.requireRole("MANAGER");
        List<Map<String, Object>> screens = db.list("SELECT id, name, description, screen_key, created_at FROM board_screens ORDER BY created_at");
        List<Map<String, Object>> links = db.list("SELECT screen_id, table_id FROM board_screen_tables");
        for (Map<String, Object> s : screens) {
            s.put("tableIds", links.stream().filter(l -> s.get("id").equals(l.get("screenId"))).map(l -> l.get("tableId")).toList());
        }
        return screens;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        if (db.value("SELECT count(*) FROM board_screens", Long.class) >= MAX_SCREENS) {
            throw ApiException.bad("That's the most screens a business can have (" + MAX_SCREENS + ")");
        }
        Body b = new Body(raw);
        UUID id = db.insert("INSERT INTO board_screens (tenant_id, name, description) VALUES (:t, :n, :d) RETURNING id",
                "n", name(b), "d", clip(b.str("description", null), 200));
        setTables(id, b.list("tableIds"));
        return one(id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        if (db.exec("UPDATE board_screens SET name = :n, description = :d WHERE id = :id", "id", id, "n", name(b),
                "d", clip(b.str("description", null), 200)) == 0) {
            throw ApiException.notFound("Screen");
        }
        if (raw.containsKey("tableIds")) {
            setTables(id, b.list("tableIds"));
        }
        return one(id);
    }

    /** A new secret for the screen's link (if the old link got out). The old link stops working. */
    @PostMapping("/{id}/new-link")
    public Map<String, Object> newLink(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        if (db.exec("UPDATE board_screens SET screen_key = replace(gen_random_uuid()::text, '-', '') WHERE id = :id", "id", id) == 0) {
            throw ApiException.notFound("Screen");
        }
        return one(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        db.exec("DELETE FROM board_screens WHERE id = :id", "id", id);
    }

    private void setTables(UUID screen, List<?> raw) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Object o : raw == null ? List.of() : raw) {
            try {
                ids.add(UUID.fromString(String.valueOf(o)));
            } catch (RuntimeException e) {
                throw ApiException.bad("Choose tables from your floor plan");
            }
        }
        db.exec("DELETE FROM board_screen_tables WHERE screen_id = :s", "s", screen);
        for (UUID t : ids) {
            // dining_tables is fenced by row-level security, so another business's table simply isn't found.
            if (db.value("SELECT count(*) FROM dining_tables WHERE id = :id", Long.class, "id", t) == 0) {
                throw ApiException.notFound("That table");
            }
            db.exec("INSERT INTO board_screen_tables (tenant_id, screen_id, table_id) VALUES (:t, :s, :tb)", "s", screen, "tb", t);
        }
    }

    private Map<String, Object> one(UUID id) {
        Map<String, Object> s = db.one("Screen", "SELECT id, name, description, screen_key, created_at FROM board_screens WHERE id = :id", "id", id);
        s.put("tableIds", new ArrayList<>(db.list("SELECT table_id FROM board_screen_tables WHERE screen_id = :id", "id", id)
                .stream().map(r -> r.get("tableId")).toList()));
        return s;
    }

    private static String name(Body b) {
        String n = clip(b.str("name", null), 60);
        if (n == null) {
            throw ApiException.bad("Give the screen a name, like \"Terrace TV\"");
        }
        return n;
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
