package com.lezitech.suite.sector;

import com.fasterxml.jackson.databind.JsonNode;
import com.lezitech.suite.common.Db;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fills a new workspace from its sector pack so the trial shows value on day one:
 * a restaurant opens to a floor plan with a menu, a salon to a booking diary with stylists.
 * Must run inside TenantContext.runAs for the new tenant.
 */
@Service
public class SeedService {

    private final Db db;
    private final PasswordEncoder encoder;

    public SeedService(Db db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    @Transactional
    public void seed(JsonNode pack, boolean withSamples, String tenantSlug) {
        JsonNode seed = pack.get("seed");
        Map<String, UUID> locations = new HashMap<>();
        boolean first = true;
        for (JsonNode l : seed.path("locations")) {
            UUID parent = l.has("parent") ? locations.get(l.get("parent").asText()) : null;
            UUID id = db.insert("""
                    INSERT INTO locations (tenant_id, name, kind, parent_id, is_default)
                    VALUES (:t, :name, :kind, :parent, :def) RETURNING id""",
                    "name", l.get("name").asText(), "kind", l.get("kind").asText(), "parent", parent,
                    "def", first && "SHOP".equals(l.get("kind").asText()) || (first && seed.path("locations").size() == 1));
            locations.put(l.get("name").asText(), id);
            first = false;
        }
        // Wholesale starts with the warehouse; make sure exactly one location is the default till location.
        db.exec("""
                UPDATE locations SET is_default = true WHERE id = (
                  SELECT id FROM locations WHERE tenant_id = :t ORDER BY is_default DESC, (kind = 'SHOP') DESC, created_at LIMIT 1)""");

        int stageSort = 0;
        for (JsonNode s : pack.path("stages")) {
            db.exec("""
                    INSERT INTO pipeline_stages (tenant_id, name, sort, probability, is_won, is_lost, rot_days)
                    VALUES (:t, :name, :sort, :p, :won, :lost, :rot)""",
                    "name", s.get("name").asText(), "sort", stageSort++, "p", s.path("probability").asInt(10),
                    "won", s.path("won").asBoolean(false), "lost", s.path("lost").asBoolean(false), "rot", s.path("rot").asInt(7));
        }

        if (!withSamples) {
            return;
        }

        UUID defaultLoc = db.value("SELECT id FROM locations WHERE tenant_id = :t AND is_default", UUID.class);
        int catSort = 0;
        for (JsonNode c : seed.path("categories")) {
            UUID catId = db.insert("INSERT INTO categories (tenant_id, name, color, sort) VALUES (:t, :name, :color, :sort) RETURNING id",
                    "name", c.get("name").asText(), "color", c.path("color").asText(null), "sort", catSort++);
            for (JsonNode i : c.path("items")) {
                String kind = i.has("kind") ? i.get("kind").asText()
                        : "RESTAURANT".equals(pack.get("posMode").asText()) ? "MENU" : "PRODUCT";
                boolean tracks = "PRODUCT".equals(kind);
                UUID itemId = db.insert("""
                        INSERT INTO items (tenant_id, category_id, name, kind, unit, price, cost, wholesale_price, wholesale_min_qty,
                                           barcode, track_stock, reorder_level, duration_min, station, needs_prescription, options, color)
                        VALUES (:t, :cat, :name, :kind, :unit, :price, :cost, :wp, :wmin, :barcode, :track, :reorder, :dur, :station,
                                :rx, cast(:options as jsonb), :color) RETURNING id""",
                        "cat", catId, "name", i.get("name").asText(), "kind", kind, "unit", i.path("unit").asText("pc"),
                        "price", new BigDecimal(i.path("price").asText("0")), "cost", new BigDecimal(i.path("cost").asText("0")),
                        "wp", i.has("wholesale") ? new BigDecimal(i.get("wholesale").asText()) : null,
                        "wmin", i.has("wholesaleMin") ? new BigDecimal(i.get("wholesaleMin").asText()) : null,
                        "barcode", i.path("barcode").asText(null), "track", tracks,
                        "reorder", new BigDecimal(i.path("reorder").asText("0")),
                        "dur", i.has("duration") ? i.get("duration").asInt() : null,
                        "station", i.path("station").asText(null), "rx", i.path("rx").asBoolean(false),
                        "options", i.has("options") ? i.get("options").toString() : "[]", "color", c.path("color").asText(null));
                if (tracks && i.has("stock")) {
                    BigDecimal qty = new BigDecimal(i.get("stock").asText());
                    db.exec("INSERT INTO stock_levels (tenant_id, item_id, location_id, qty) VALUES (:t, :item, :loc, :qty)",
                            "item", itemId, "loc", defaultLoc, "qty", qty);
                    db.exec("""
                            INSERT INTO stock_moves (tenant_id, item_id, location_id, qty, reason, ref)
                            VALUES (:t, :item, :loc, :qty, 'OPENING', 'Sample data')""",
                            "item", itemId, "loc", defaultLoc, "qty", qty);
                    if (i.has("batch")) {
                        db.exec("""
                                INSERT INTO stock_batches (tenant_id, item_id, location_id, batch_no, expiry_date, qty)
                                VALUES (:t, :item, :loc, :batch, :exp, :qty)""",
                                "item", itemId, "loc", defaultLoc, "batch", i.get("batch").asText(),
                                "exp", LocalDate.now().plusDays(i.path("expiryDays").asInt(365)), "qty", qty);
                    }
                }
            }
        }

        int floorSort = 0;
        for (JsonNode f : seed.path("floors")) {
            UUID floorId = db.insert("INSERT INTO floors (tenant_id, name, sort) VALUES (:t, :name, :sort) RETURNING id",
                    "name", f.get("name").asText(), "sort", floorSort++);
            int count = f.path("tables").asInt(6);
            String prefix = floorSort == 1 ? "T" : f.get("name").asText().substring(0, 1).toUpperCase();
            for (int n = 0; n < count; n++) {
                db.exec("""
                        INSERT INTO dining_tables (tenant_id, floor_id, label, seats, shape, x, y)
                        VALUES (:t, :floor, :label, :seats, :shape, :x, :y)""",
                        "floor", floorId, "label", prefix + (n + 1), "seats", n % 3 == 0 ? 2 : 4,
                        "shape", n % 3 == 0 ? "round" : "square", "x", n % 4, "y", n / 4);
            }
        }

        for (JsonNode c : seed.path("customers")) {
            db.exec("""
                    INSERT INTO customers (tenant_id, name, phone, company, kind, credit_limit)
                    VALUES (:t, :name, :phone, :company, :kind, :credit)""",
                    "name", c.get("name").asText(), "phone", c.path("phone").asText(null), "company", c.path("company").asText(null),
                    "kind", c.path("kind").asText("RETAIL"), "credit", new BigDecimal(c.path("credit").asText("0")));
        }
        for (JsonNode s : seed.path("suppliers")) {
            db.exec("INSERT INTO suppliers (tenant_id, name, phone) VALUES (:t, :name, :phone)",
                    "name", s.get("name").asText(), "phone", s.path("phone").asText(null));
        }

        UUID firstStage = db.value("SELECT id FROM pipeline_stages WHERE tenant_id = :t ORDER BY sort LIMIT 1", UUID.class);
        for (JsonNode d : seed.path("deals")) {
            db.exec("""
                    INSERT INTO deals (tenant_id, title, contact_name, value, stage_id, owner_id, source)
                    VALUES (:t, :title, :contact, :value, :stage, :u, 'Sample')""",
                    "title", d.get("title").asText(), "contact", d.path("contact").asText(null),
                    "value", new BigDecimal(d.path("value").asText("0")), "stage", firstStage);
        }

        // Sample staff (stylists etc.) can't sign in until the owner gives them a PIN or password.
        int n = 1;
        for (JsonNode s : seed.path("staff")) {
            db.exec("""
                    INSERT INTO users (tenant_id, email, name, password_hash, role, active)
                    VALUES (:t, :email, :name, :hash, :role, true)""",
                    "email", "staff" + n++ + "@" + tenantSlug + ".suite.local", "name", s.get("name").asText(),
                    "hash", encoder.encode(UUID.randomUUID().toString()), "role", s.path("role").asText("CASHIER"));
        }
    }
}
