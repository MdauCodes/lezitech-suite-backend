package com.lezitech.suite.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.sector.SectorCatalog;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Screens customers see without logging in, all in the business's own branding:
 * the staff sign-in page, the order-status board, an order tracking page and the QR menu.
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final JdbcTemplate jdbc;
    private final PublicService service;
    private final SectorCatalog sectors;
    private final ObjectMapper json;

    public PublicController(JdbcTemplate jdbc, PublicService service, SectorCatalog sectors, ObjectMapper json) {
        this.jdbc = jdbc;
        this.service = service;
        this.sectors = sectors;
        this.json = json;
    }

    @GetMapping("/workspace/{slug}")
    public Map<String, Object> workspace(@PathVariable String slug) {
        return brand(tenant(slug));
    }

    @GetMapping("/board/{slug}")
    public Map<String, Object> board(@PathVariable String slug, @RequestParam("k") String key) {
        Map<String, Object> t = tenant(slug);
        if (!MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), ((String) t.get("display_key")).getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.notFound("Screen");
        }
        Map<String, Object> out = brand(t);
        out.putAll(as(t, service::board));
        return out;
    }

    @GetMapping("/track/{slug}/{token}")
    public Map<String, Object> track(@PathVariable String slug, @PathVariable UUID token) {
        Map<String, Object> t = tenant(slug);
        Map<String, Object> out = brand(t);
        out.put("order", as(t, () -> service.track(token)));
        return out;
    }

    @GetMapping("/menu/{slug}")
    public Map<String, Object> menu(@PathVariable String slug) {
        Map<String, Object> t = tenant(slug);
        Map<String, Object> out = brand(t);
        out.putAll(as(t, service::menu));
        return out;
    }

    private Map<String, Object> tenant(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, slug, name, sector, sub_sector, brand_color, logo_url, phone, currency, display_key, status,
                       settings::text AS settings FROM tenants WHERE slug = ?""", slug);
        if (rows.isEmpty() || "SUSPENDED".equals(rows.get(0).get("status"))) {
            throw ApiException.notFound("Business");
        }
        return rows.get(0);
    }

    private Map<String, Object> brand(Map<String, Object> t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", t.get("slug"));
        out.put("name", t.get("name"));
        out.put("sector", t.get("sector"));
        out.put("sectorName", sectors.get((String) t.get("sector")).get("name").asText());
        out.put("subSector", t.get("sub_sector"));
        out.put("brandColor", t.get("brand_color"));
        out.put("logoUrl", t.get("logo_url"));
        out.put("phone", t.get("phone"));
        out.put("currency", t.get("currency"));
        try {
            var s = json.readTree((String) t.get("settings"));
            out.put("accentColor", s.path("accentColor").asText(null));
            out.put("tagline", s.path("tagline").asText(null));
            out.put("address", s.path("address").asText(null));
        } catch (Exception ignored) {
            // settings are always valid JSON; branding is best effort
        }
        return out;
    }

    private <T> T as(Map<String, Object> t, Supplier<T> work) {
        return TenantContext.runAs((UUID) t.get("id"), null, work);
    }
}
