package com.lezitech.suite.sector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lezitech.suite.common.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sector packs (cf. ERPNext's domains and Odoo's industry presets): one product, and each
 * pack decides which screens show, what things are called, the till layout, defaults and
 * the sample data a new trial starts with. Lives in sectors.json so packs are easy to add.
 *
 * Sub-sectors (subsectors.json) personalise a pack: a hardware shop and a boutique are both "retail", but they
 * sell differently, count different things and start with different sample items. A sub-sector can override:
 *  terms, features         merged key by key over the pack's
 *  kpis, modules, home, posMode, tagline, extras, stages   replaced
 *  seed                    merged key by key (e.g. its own categories and items; the pack's locations stay)
 *  questions               extra setup questions asked at signup, answers kept in settings.setupAnswers
 *  seo                     headline, pitch and keywords for its public page
 * A workspace with no sub-sector, or one typed freely before sub-sectors existed, keeps the plain pack.
 */
@Component
public class SectorCatalog {

    private static final Set<String> REPLACED = Set.of("kpis", "modules", "home", "posMode", "tagline", "extras", "stages");
    private static final Set<String> MERGED = Set.of("terms", "features", "seed");

    private final Map<String, JsonNode> packs = new LinkedHashMap<>();
    private final Map<String, List<JsonNode>> subSectors = new LinkedHashMap<>();
    private final ObjectMapper json;

    public SectorCatalog(ObjectMapper json) throws IOException {
        this.json = json;
        try (InputStream in = new ClassPathResource("sectors.json").getInputStream()) {
            for (JsonNode pack : json.readTree(in)) {
                packs.put(pack.get("key").asText(), pack);
            }
        }
        ClassPathResource subs = new ClassPathResource("subsectors.json");
        if (subs.exists()) {
            try (InputStream in = subs.getInputStream()) {
                JsonNode all = json.readTree(in);
                all.fields().forEachRemaining(e -> {
                    if (!packs.containsKey(e.getKey())) {
                        throw new IllegalStateException("subsectors.json names an unknown sector: " + e.getKey());
                    }
                    List<JsonNode> list = new ArrayList<>();
                    e.getValue().forEach(list::add);
                    subSectors.put(e.getKey(), list);
                });
            }
        }
    }

    /** The plain sector pack. */
    public JsonNode get(String key) {
        JsonNode pack = packs.get(key);
        if (pack == null) {
            throw ApiException.bad("Unknown sector: " + key);
        }
        return pack;
    }

    /** The pack personalised for a sub-sector (matched by its key or name); the plain pack if there is no match. */
    public JsonNode get(String key, String subSector) {
        JsonNode pack = get(key);
        JsonNode sub = findSub(key, subSector);
        if (sub == null) {
            return pack;
        }
        ObjectNode out = pack.deepCopy();
        sub.fields().forEachRemaining(f -> {
            String k = f.getKey();
            if (MERGED.contains(k) && f.getValue().isObject()) {
                ObjectNode target = out.has(k) && out.get(k).isObject() ? (ObjectNode) out.get(k) : out.putObject(k);
                f.getValue().fields().forEachRemaining(g -> target.set(g.getKey(), g.getValue().deepCopy()));
            } else if (REPLACED.contains(k)) {
                out.set(k, f.getValue().deepCopy());
            }
        });
        out.put("subSector", sub.get("name").asText());
        out.put("subSectorKey", sub.get("key").asText());
        if (sub.has("questions")) {
            out.set("questions", sub.get("questions").deepCopy());
        }
        if (sub.has("seo")) {
            out.set("seo", sub.get("seo").deepCopy());
        }
        return out;
    }

    /** The sub-sector's definition, or null. */
    public JsonNode findSub(String key, String subSector) {
        if (subSector == null || subSector.isBlank()) {
            return null;
        }
        String want = subSector.trim().toLowerCase(Locale.ROOT);
        for (JsonNode s : subSectors.getOrDefault(key, List.of())) {
            if (s.get("key").asText().equalsIgnoreCase(want) || s.get("name").asText().toLowerCase(Locale.ROOT).equals(want)) {
                return s;
            }
        }
        return null;
    }

    /** Everything except seed data, for the signup picker and the app shell. */
    public JsonNode publicView(String key) {
        return publicView(key, null);
    }

    /** The app shell's view of a workspace's pack: personalised for its sub-sector, without seed data. */
    public JsonNode publicView(String key, String subSector) {
        ObjectNode copy = (ObjectNode) get(key, subSector).deepCopy();
        copy.remove("seed");
        List<JsonNode> subs = subSectors.get(key);
        if (subs != null && !subs.isEmpty()) {
            // The picker lists the personalised sub-sectors; names stay the stored value for older workspaces.
            ArrayNode names = copy.putArray("subSectors");
            ArrayNode details = copy.putArray("subSectorDetails");
            for (JsonNode s : subs) {
                names.add(s.get("name").asText());
                ObjectNode d = details.addObject();
                d.put("key", s.get("key").asText());
                d.put("name", s.get("name").asText());
                d.put("tagline", s.path("tagline").asText(""));
                if (s.has("questions")) {
                    d.set("questions", s.get("questions").deepCopy());
                }
                if (s.has("seo")) {
                    d.set("seo", s.get("seo").deepCopy());
                }
            }
        }
        return copy;
    }

    public List<JsonNode> all() {
        List<JsonNode> out = new ArrayList<>();
        packs.keySet().forEach(k -> out.add(publicView(k)));
        return out;
    }

    /** Every sector and sub-sector with its public-page text, for the marketing site. */
    public List<Map<String, Object>> pages() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : packs.keySet()) {
            for (JsonNode s : subSectors.getOrDefault(k, List.of())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sector", k);
                m.put("key", s.get("key").asText());
                m.put("name", s.get("name").asText());
                m.put("tagline", s.path("tagline").asText(""));
                m.put("seo", json.convertValue(s.path("seo"), Map.class));
                out.add(m);
            }
        }
        return out;
    }
}
