package com.lezitech.suite.sector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The guide a visitor downloads from a sector's page on the website: one PDF per trade, made here rather
 * than at the website's build, because the sector packs live here and the website only keeps a copy of them.
 *
 * Open to everyone (/api/public/**): nobody should have to sign up to read what the system does for them.
 * Each guide is made once and kept in memory afterwards - the packs only change when we deploy - and the
 * browser is told it may keep it for a day.
 */
@RestController
@RequestMapping("/api/public/guide")
public class SectorGuideController {

    private final SectorCatalog sectors;
    private final JsonNode copy;
    private final Map<String, byte[]> made = new ConcurrentHashMap<>();

    public SectorGuideController(SectorCatalog sectors, ObjectMapper json) throws IOException {
        this.sectors = sectors;
        try (InputStream in = new ClassPathResource("guide.json").getInputStream()) {
            this.copy = json.readTree(in);
        }
    }

    /** The guide for a whole trade, e.g. /api/public/guide/restaurant.pdf */
    @GetMapping("/{sector}.pdf")
    public ResponseEntity<byte[]> sector(@PathVariable String sector) {
        return pdf(sector, null);
    }

    /** The guide for one kind of business inside a trade, e.g. /api/public/guide/retail/minimart.pdf */
    @GetMapping("/{sector}/{sub}.pdf")
    public ResponseEntity<byte[]> subSector(@PathVariable String sector, @PathVariable String sub) {
        return pdf(sector, sub);
    }

    private ResponseEntity<byte[]> pdf(String sector, String sub) {
        String key = clean(sector);
        String subKey = sub == null ? null : clean(sub);
        if (key.isEmpty() || (subKey != null && subKey.isEmpty())) {
            throw ApiException.bad("That is not a kind of business we know.");
        }
        if (subKey != null && sectors.findSub(key, subKey) == null) {
            // Unknown sub-sector: give the trade's own guide rather than nothing at all.
            subKey = null;
        }
        String cacheKey = key + (subKey == null ? "" : "/" + subKey);
        byte[] bytes = made.get(cacheKey);
        if (bytes == null) {
            JsonNode pack = sectors.publicView(key, subKey);
            bytes = SectorGuidePdf.render(pack, copy, sectors.comingSoon(key, subKey));
            made.put(cacheKey, bytes);
        }
        String name = "busynez-guide-" + cacheKey.replace('/', '-') + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(bytes);
    }

    /** Only the shapes a sector or sub-sector key can have, so nothing from the address reaches a file path. */
    private static String clean(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
    }
}
