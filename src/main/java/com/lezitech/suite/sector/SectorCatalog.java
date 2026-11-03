package com.lezitech.suite.sector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sector packs (cf. ERPNext's domains and Odoo's industry presets): one product, and each
 * pack decides which screens show, what things are called, the till layout, defaults and
 * the sample data a new trial starts with. Lives in sectors.json so packs are easy to add.
 */
@Component
public class SectorCatalog {

    private final Map<String, JsonNode> packs = new LinkedHashMap<>();

    public SectorCatalog(ObjectMapper json) throws IOException {
        try (InputStream in = new ClassPathResource("sectors.json").getInputStream()) {
            for (JsonNode pack : json.readTree(in)) {
                packs.put(pack.get("key").asText(), pack);
            }
        }
    }

    public JsonNode get(String key) {
        JsonNode pack = packs.get(key);
        if (pack == null) {
            throw ApiException.bad("Unknown sector: " + key);
        }
        return pack;
    }

    /** Everything except seed data, for the signup picker and the app shell. */
    public JsonNode publicView(String key) {
        JsonNode copy = get(key).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("seed");
        return copy;
    }

    public List<JsonNode> all() {
        List<JsonNode> out = new ArrayList<>();
        packs.keySet().forEach(k -> out.add(publicView(k)));
        return out;
    }
}
