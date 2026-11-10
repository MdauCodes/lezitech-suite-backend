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
    private final com.lezitech.suite.rooms.StayService stays;
    private final com.lezitech.suite.sales.OrderService orders;
    private final com.lezitech.suite.property.PropertyController property;

    public SeedService(Db db, PasswordEncoder encoder, com.lezitech.suite.rooms.StayService stays,
                       com.lezitech.suite.sales.OrderService orders, com.lezitech.suite.property.PropertyController property) {
        this.db = db;
        this.encoder = encoder;
        this.stays = stays;
        this.orders = orders;
        this.property = property;
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
                                           barcode, track_stock, reorder_level, duration_min, station, needs_prescription, options, color, tax_rate)
                        VALUES (:t, :cat, :name, :kind, :unit, :price, :cost, :wp, :wmin, :barcode, :track, :reorder, :dur, :station,
                                :rx, cast(:options as jsonb), :color, :tax) RETURNING id""",
                        "cat", catId, "name", i.get("name").asText(), "kind", kind, "unit", i.path("unit").asText("pc"),
                        "price", new BigDecimal(i.path("price").asText("0")), "cost", new BigDecimal(i.path("cost").asText("0")),
                        "wp", i.has("wholesale") ? new BigDecimal(i.get("wholesale").asText()) : null,
                        "wmin", i.has("wholesaleMin") ? new BigDecimal(i.get("wholesaleMin").asText()) : null,
                        "barcode", i.path("barcode").asText(null), "track", tracks,
                        "reorder", new BigDecimal(i.path("reorder").asText("0")),
                        "dur", i.has("duration") ? Integer.valueOf(i.get("duration").asInt())
                                : i.has("days") ? Integer.valueOf(i.get("days").asInt() * 1440) : null,
                        "tax", new BigDecimal(i.path("tax").asText("16")),
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

        seedSpacesAndBookings(seed);

        // Sample staff (stylists etc.) can't sign in until the owner gives them a PIN or password.
        int n = 1;
        for (JsonNode s : seed.path("staff")) {
            db.exec("""
                    INSERT INTO users (tenant_id, email, name, password_hash, role, active, commission_rate)
                    VALUES (:t, :email, :name, :hash, :role, true, :rate)""",
                    "email", "staff" + n++ + "@" + tenantSlug + ".suite.local", "name", s.get("name").asText(),
                    "hash", encoder.encode(UUID.randomUUID().toString()), "role", s.path("role").asText("CASHIER"),
                    "rate", s.has("commission") ? new java.math.BigDecimal(s.get("commission").asText()) : null);
        }
    }

    /** A restaurant or shop that also lets rooms starts with a few to try, if its pack has none. */
    @Transactional
    public void seedExtraRooms() {
        if (Boolean.TRUE.equals(db.value("SELECT exists(SELECT 1 FROM spaces)", Boolean.class))) {
            return;
        }
        String[][] rooms = {{"Room 1", "ROOM", "Standard", "2", "4500"}, {"Room 2", "ROOM", "Standard", "2", "4500"},
                {"Room 3", "ROOM", "Family", "4", "7500"}, {"Function hall", "HALL", "Hall (per day)", "60", "15000"}};
        int sort = 0;
        for (String[] r : rooms) {
            db.exec("""
                    INSERT INTO spaces (tenant_id, name, kind, type_name, capacity, rate, sort)
                    VALUES (:t, :name, :kind, :type, :cap, :rate, :sort)""",
                    "name", r[0], "kind", r[1], "type", r[2], "cap", Integer.parseInt(r[3]), "rate", new BigDecimal(r[4]), "sort", sort++);
        }
    }

    /** Rooms, reservations, tour departures and leases for the hotel, tours and property packs. */
    private void seedSpacesAndBookings(JsonNode seed) {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"));
        int sort = 0;
        Map<String, UUID> spaces = new HashMap<>();
        for (JsonNode g : seed.path("spaces")) {
            for (JsonNode name : g.path("names")) {
                UUID id = db.insert("""
                        INSERT INTO spaces (tenant_id, name, kind, type_name, capacity, rate, service_charge, area_sqft, floor, sort)
                        VALUES (:t, :name, :kind, :type, :cap, :rate, :svc, :area, :floor, :sort) RETURNING id""",
                        "name", name.asText(), "kind", g.path("kind").asText("ROOM"), "type", g.path("type").asText(null),
                        "cap", g.path("capacity").asInt(2), "rate", new BigDecimal(g.path("rate").asText("0")),
                        "svc", new BigDecimal(g.path("service").asText("0")),
                        "area", g.has("area") ? Integer.valueOf(g.get("area").asInt()) : null, "floor", g.path("floor").asText(null),
                        "sort", sort++);
                spaces.put(name.asText(), id);
            }
        }
        for (JsonNode st : seed.path("stays")) {
            LocalDate in = today.plusDays(st.path("in").asInt(0));
            Map<String, Object> body = new HashMap<>();
            body.put("spaceId", spaces.get(st.get("space").asText()).toString());
            body.put("guestName", st.get("guest").asText());
            body.put("phone", st.path("phone").asText(null));
            body.put("checkIn", in.toString());
            body.put("checkOut", in.plusDays(st.path("nights").asInt(1)).toString());
            body.put("source", st.path("source").asText("Walk-in"));
            body.put("status", st.path("status").asText("RESERVED"));
            UUID stayId = stays.reserve(new com.lezitech.suite.common.Body(body));
            if ("IN_HOUSE".equals(st.path("status").asText())) {
                UUID folio = db.value("SELECT folio_id FROM stays WHERE id = :stayId", UUID.class, "stayId", stayId);
                orders.addLines(folio, java.util.List.of(Map.of("name", "Dinner: chicken curry", "unitPrice", 1100, "qty", 1, "taxRate", 16)));
            }
        }
        java.util.List<UUID> trips = new java.util.ArrayList<>();
        for (JsonNode tr : seed.path("trips")) {
            Map<String, Object> pkg = db.one("Package", "SELECT id, duration_min FROM items WHERE name = :name", "name", tr.get("package").asText());
            int days = pkg.get("durationMin") == null ? 1 : Math.max(1, ((Integer) pkg.get("durationMin")) / 1440);
            LocalDate start = today.plusDays(tr.path("inDays").asInt(7));
            trips.add(db.insert("""
                    INSERT INTO trips (tenant_id, item_id, starts_on, ends_on, seats, vehicle, guide)
                    VALUES (:t, :item, :start, :end, :seats, :vehicle, :guide) RETURNING id""",
                    "item", pkg.get("id"), "start", start, "end", start.plusDays(days - 1L), "seats", tr.path("seats").asInt(7),
                    "vehicle", tr.path("vehicle").asText(null), "guide", tr.path("guide").asText(null)));
        }
        for (JsonNode bk : seed.path("tripBookings")) {
            UUID trip = trips.get(bk.path("trip").asInt(0));
            Map<String, Object> t = db.one("Departure", "SELECT * FROM trips WHERE id = :trip", "trip", trip);
            String lead = bk.get("lead").asText();
            UUID customer = db.insert("INSERT INTO customers (tenant_id, name, phone) VALUES (:t, :name, :phone) RETURNING id",
                    "name", lead, "phone", bk.path("phone").asText(null));
            int pax = bk.path("pax").asInt(1);
            Map<String, Object> inv = new HashMap<>();
            inv.put("channel", "TOUR");
            inv.put("customerId", customer.toString());
            inv.put("dueOn", t.get("startsOn"));
            inv.put("lines", java.util.List.of(Map.of("itemId", t.get("itemId"), "qty", pax)));
            UUID order = orders.create(new com.lezitech.suite.common.Body(inv));
            db.exec("""
                    INSERT INTO trip_bookings (tenant_id, trip_id, customer_id, lead_name, phone, pax, pickup, nationality, order_id)
                    VALUES (:t, :trip, :customer, :lead, :phone, :pax, :pickup, :nat, :order)""",
                    "trip", trip, "customer", customer, "lead", lead, "phone", bk.path("phone").asText(null), "pax", pax,
                    "pickup", bk.path("pickup").asText(null), "nat", bk.path("nationality").asText(null), "order", order);
            if (bk.has("deposit")) {
                orders.pay(order, new com.lezitech.suite.common.Body(Map.of("deposit", true, "payments",
                        java.util.List.of(Map.of("method", "MPESA", "amount", bk.get("deposit").asInt(), "reference", "SAMPLE")))));
            }
        }
        if (seed.path("leases").isEmpty()) {
            return;
        }
        for (JsonNode l : seed.path("leases")) {
            UUID unit = spaces.get(l.get("space").asText());
            Map<String, Object> sp = db.one("Unit", "SELECT * FROM spaces WHERE id = :unit", "unit", unit);
            UUID customer = db.insert("""
                    INSERT INTO customers (tenant_id, name, phone, company, kind) VALUES (:t, :name, :phone, :name, 'CORPORATE') RETURNING id""",
                    "name", l.get("tenant").asText(), "phone", l.path("phone").asText(null));
            db.exec("""
                    INSERT INTO leases (tenant_id, space_id, customer_id, starts_on, rent, service_charge, deposit, due_day)
                    VALUES (:t, :unit, :customer, :start, :rent, :svc, :deposit, 5)""",
                    "unit", unit, "customer", customer, "start", today.minusMonths(l.path("monthsAgo").asInt(1)).withDayOfMonth(1),
                    "rent", sp.get("rate"), "svc", sp.get("serviceCharge"), "deposit", new BigDecimal(l.path("deposit").asText("0")));
        }
        // Last month mostly paid, this month just raised: shows arrears and collection on day one.
        java.time.YearMonth now = java.time.YearMonth.from(today);
        property.raiseInvoices(now.minusMonths(1));
        property.raiseInvoices(now);
        int n = 0;
        for (Map<String, Object> inv : db.list("SELECT id, total FROM orders WHERE period = :p ORDER BY number", "p", now.minusMonths(1).toString())) {
            if (n++ % 3 == 2) {
                continue;
            }
            orders.pay(UUID.fromString(inv.get("id").toString()), new com.lezitech.suite.common.Body(Map.of("payments",
                    java.util.List.of(Map.of("method", "MPESA", "amount", inv.get("total"), "reference", "SAMPLE")))));
        }
    }
}
