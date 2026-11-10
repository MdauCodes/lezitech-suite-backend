package com.lezitech.suite.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.auth.AuthController;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.sector.SectorCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pilot and sales demos: one ready-made business per sector (slug demo-<sector>) with a person in
 * each role, so anyone can click "Waiter at Mama Oliech Kitchen" instead of typing logins.
 * Demo workspaces never expire and can be reset from the control panel.
 */
@Service
public class DemoService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final JdbcTemplate jdbc;
    private final SectorCatalog sectors;
    private final AuthController auth;
    private final PasswordEncoder encoder;
    private final boolean enabled;

    public DemoService(JdbcTemplate jdbc, SectorCatalog sectors, AuthController auth, PasswordEncoder encoder,
                       @Value("${suite.demo.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.sectors = sectors;
        this.auth = auth;
        this.encoder = encoder;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        for (JsonNode pack : sectors.all()) {
            String key = pack.get("key").asText();
            if (pack.path("comingSoon").asBoolean(false)) {
                continue; // no demo for sectors that aren't open yet
            }
            if (!exists(key)) {
                try {
                    create(key);
                    log.info("Created demo workspace demo-{}", key);
                } catch (Exception e) {
                    log.warn("Could not create demo workspace for {}: {}", key, e.getMessage());
                }
            }
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode pack : sectors.all()) {
            String key = pack.get("key").asText();
            if (pack.path("comingSoon").asBoolean(false)) {
                continue;
            }
            JsonNode demo = pack.path("demo");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sector", key);
            row.put("sectorName", pack.get("name").asText());
            row.put("tagline", pack.get("tagline").asText());
            row.put("icon", pack.get("icon").asText());
            row.put("color", demo.path("color").asText(pack.get("color").asText()));
            row.put("business", demo.path("business").asText(pack.get("name").asText()));
            row.put("slug", "demo-" + key);
            row.put("ready", exists(key));
            List<Map<String, Object>> roles = new ArrayList<>();
            demo.path("roles").forEach(r -> roles.add(Map.of("role", r.get("role").asText(), "label", r.get("label").asText(), "name", r.get("name").asText())));
            row.put("roles", roles);
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> login(String sector, String role) {
        if (!enabled) {
            throw ApiException.forbidden("Demo logins are switched off");
        }
        UUID tenantId = tenantId(sector);
        if (tenantId == null) {
            throw ApiException.notFound("Demo workspace");
        }
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, name, role FROM users WHERE tenant_id = ? AND role = ? AND active ORDER BY created_at LIMIT 1", tenantId, role);
        if (users.isEmpty()) {
            throw ApiException.notFound("Demo user for that role");
        }
        Map<String, Object> u = users.get(0);
        return auth.session((UUID) u.get("id"), tenantId, role, (String) u.get("name"));
    }

    /** Wipe the demo business and rebuild it with fresh sample data. */
    public void reset(String sector) throws Exception {
        UUID tenantId = tenantId(sector);
        if (tenantId != null) {
            jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        }
        create(sector);
    }

    private void create(String key) throws Exception {
        JsonNode pack = sectors.get(key);
        JsonNode demo = pack.path("demo");
        String domain = "demo-" + key + ".lezitech.test";
        Map<String, Object> body = new HashMap<>();
        body.put("businessName", demo.path("business").asText(pack.get("name").asText()));
        body.put("sector", key);
        body.put("subSector", pack.get("subSectors").get(0).asText());
        body.put("brandColor", demo.path("color").asText(pack.get("color").asText()));
        body.put("name", demo.path("roles").path(0).path("name").asText("Demo owner"));
        body.put("email", "owner@" + domain);
        body.put("password", UUID.randomUUID().toString());
        body.put("phone", "0700000000");
        body.put("tagline", "Demo business · data resets from time to time");
        body.put("withSamples", true);
        if (!pack.path("extras").isEmpty()) {
            body.put("extras", List.of(pack.path("extras").get(0).get("key").asText()));
        }
        Map<String, Object> session = auth.createWorkspace(body, "demo-" + key);
        @SuppressWarnings("unchecked")
        UUID tenantId = UUID.fromString(((Map<String, Object>) session.get("tenant")).get("id").toString());
        jdbc.update("""
                UPDATE tenants SET is_demo = true, status = 'ACTIVE', plan = 'PRO', paid_until = now() + interval '20 years'
                WHERE id = ?""", tenantId);
        int n = 0;
        for (JsonNode r : demo.path("roles")) {
            if (n++ == 0) {
                continue; // the owner was created by signup
            }
            String role = r.get("role").asText();
            String name = r.get("name").asText();
            // Seeded sample staff (e.g. stylists) take on the demo names instead of duplicating.
            int renamed = jdbc.update("""
                    UPDATE users SET name = ? WHERE id = (SELECT id FROM users WHERE tenant_id = ? AND role = ? AND email LIKE 'staff%' LIMIT 1)""",
                    name, tenantId, role);
            if (renamed == 0) {
                jdbc.update("INSERT INTO users (tenant_id, email, name, password_hash, role, pin_hash) VALUES (?, ?, ?, ?, ?, ?)",
                        tenantId, role.toLowerCase() + n + "@" + domain, name, encoder.encode(UUID.randomUUID().toString()), role,
                        encoder.encode(String.valueOf(1000 + n)));
            }
        }
    }

    private boolean exists(String key) {
        return tenantId(key) != null;
    }

    private UUID tenantId(String key) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM tenants WHERE slug = ? AND is_demo", UUID.class, "demo-" + key);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        // demo-* slugs are reserved for demos; move any ordinary workspace that took one before the rule existed.
        jdbc.update("UPDATE tenants SET slug = slug || '-' || substr(md5(id::text), 1, 4) WHERE slug = ? AND NOT is_demo", "demo-" + key);
        return null;
    }
}
