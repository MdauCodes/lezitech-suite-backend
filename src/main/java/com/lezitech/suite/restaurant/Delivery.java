package com.lezitech.suite.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.mpesa.MpesaService;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * R5, delivery.
 * <ul>
 *   <li>Areas, each with the customer's fee and the rider's pay for the trip.</li>
 *   <li>Riders: staff with the RIDER job (they sign in and see their run sheet) or a hired rider with just a name and
 *       phone (staff tap for them).</li>
 *   <li>A delivery per order: waiting for a rider, on the way, delivered or couldn't deliver.</li>
 *   <li>The customer pays first by M-Pesa, or at the door (cash, which the rider hands in, or M-Pesa).</li>
 *   <li>Riders are paid per trip, after each trip or several together; paid from the till, it comes off the till's cash.</li>
 *   <li>Delivery-app orders (Glovo, Uber Eats, Bolt Food) are recorded with the app's cut, so profit is true.</li>
 * </ul>
 */
@Service
public class Delivery {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    public record App(String key, String name, BigDecimal cut) {
    }

    /** The owner's delivery rules (tenants.settings.delivery). */
    public record Rules(boolean online, boolean payFirst, boolean payAtDoor, BigDecimal freeAbove, BigDecimal minimumOrder,
                        String riderPayWhen, List<App> apps) {
    }

    static final List<App> DEFAULT_APPS = List.of(
            new App("GLOVO", "Glovo", new BigDecimal("30")),
            new App("UBER_EATS", "Uber Eats", new BigDecimal("30")),
            new App("BOLT_FOOD", "Bolt Food", new BigDecimal("25")));

    private final Db db;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OrderService orders;
    private final TableOrderService tableOrders;
    private final MpesaService mpesa;

    public Delivery(Db db, JdbcTemplate jdbc, ObjectMapper json, OrderService orders, TableOrderService tableOrders, MpesaService mpesa) {
        this.db = db;
        this.jdbc = jdbc;
        this.json = json;
        this.orders = orders;
        this.tableOrders = tableOrders;
        this.mpesa = mpesa;
    }

    // ---------------- rules ----------------

    public Rules rules(UUID tenant) {
        JsonNode s;
        try {
            s = json.readTree(jdbc.queryForObject("SELECT coalesce(settings->'delivery', '{}'::jsonb)::text FROM tenants WHERE id = ?", String.class, tenant));
        } catch (Exception e) {
            s = json.createObjectNode();
        }
        List<App> apps = new ArrayList<>();
        if (s.path("apps").isArray()) {
            s.path("apps").forEach(a -> apps.add(new App(a.path("key").asText(), a.path("name").asText(), a.path("cut").decimalValue())));
        } else {
            apps.addAll(DEFAULT_APPS);
        }
        return new Rules(s.path("online").asBoolean(false), s.path("payFirst").asBoolean(true), s.path("payAtDoor").asBoolean(true),
                s.path("freeAbove").isNumber() ? s.path("freeAbove").decimalValue() : BigDecimal.ZERO,
                s.path("minimumOrder").isNumber() ? s.path("minimumOrder").decimalValue() : BigDecimal.ZERO,
                "LATER".equals(s.path("riderPayWhen").asText()) ? "LATER" : "EACH", apps);
    }

    public Map<String, Object> rulesView(Rules r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", r.online());
        out.put("payFirst", r.payFirst());
        out.put("payAtDoor", r.payAtDoor());
        out.put("freeAbove", r.freeAbove());
        out.put("minimumOrder", r.minimumOrder());
        out.put("riderPayWhen", r.riderPayWhen());
        out.put("apps", r.apps().stream().map(a -> Map.of("key", a.key(), "name", a.name(), "cut", a.cut())).toList());
        return out;
    }

    public void saveRules(Body b) {
        TenantContext.requireRole("MANAGER");
        boolean payFirst = b.bool("payFirst", true);
        boolean payAtDoor = b.bool("payAtDoor", true);
        if (!payFirst && !payAtDoor) {
            throw ApiException.bad("Tick at least one way for customers to pay");
        }
        BigDecimal freeAbove = b.num("freeAbove", BigDecimal.ZERO);
        BigDecimal minimum = b.num("minimumOrder", BigDecimal.ZERO);
        if (freeAbove.signum() < 0 || minimum.signum() < 0) {
            throw ApiException.bad("Amounts can't be below zero");
        }
        List<Map<String, Object>> apps = new ArrayList<>();
        if (!(b.raw("apps") instanceof List<?>)) {
            // Not sent: keep the apps as they are.
            rules(TenantContext.tenantId()).apps().forEach(x -> apps.add(Map.of("key", x.key(), "name", x.name(), "cut", x.cut())));
        } else if (b.raw("apps") instanceof List<?> given) {
            for (Object o : given) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String name = clip(String.valueOf(m.get("name") == null ? "" : m.get("name")).trim(), 30);
                if (name.isEmpty()) {
                    continue;
                }
                BigDecimal cut = new BigDecimal(String.valueOf(m.get("cut") == null ? "0" : m.get("cut")));
                if (cut.signum() < 0 || cut.compareTo(new BigDecimal("90")) > 0) {
                    throw ApiException.bad("An app's cut must be between 0% and 90%");
                }
                String key = m.get("key") == null || String.valueOf(m.get("key")).isBlank()
                        ? name.toUpperCase().replaceAll("[^A-Z0-9]+", "_") : String.valueOf(m.get("key"));
                apps.add(Map.of("key", clip(key, 20), "name", name, "cut", cut));
            }
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("online", b.bool("online", false));
        s.put("payFirst", payFirst);
        s.put("payAtDoor", payAtDoor);
        s.put("freeAbove", freeAbove);
        s.put("minimumOrder", minimum);
        s.put("riderPayWhen", "LATER".equals(b.str("riderPayWhen", "EACH")) ? "LATER" : "EACH");
        s.put("apps", apps);
        jdbc.update("UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{delivery}', cast(? as jsonb)) WHERE id = ?",
                db.toJson(s), TenantContext.tenantId());
    }

    // ---------------- areas and riders ----------------

    public List<Map<String, Object>> areas(boolean activeOnly) {
        return db.list("SELECT id, name, fee, rider_pay, minutes, active FROM delivery_areas"
                + (activeOnly ? " WHERE active" : "") + " ORDER BY sort, name");
    }

    public Map<String, Object> saveArea(UUID id, Body b) {
        TenantContext.requireRole("MANAGER");
        String name = clip(b.str("name", "").trim(), 80);
        if (name.isEmpty()) {
            throw ApiException.bad("Give the area a name, e.g. Kilimani");
        }
        BigDecimal fee = b.num("fee", BigDecimal.ZERO);
        BigDecimal pay = b.num("riderPay", BigDecimal.ZERO);
        if (fee.signum() < 0 || pay.signum() < 0) {
            throw ApiException.bad("Amounts can't be below zero");
        }
        int minutes = Math.max(5, Math.min(240, b.integer("minutes", 30)));
        Object[] p = {"name", name, "fee", fee, "pay", pay, "min", minutes, "active", b.bool("active", true), "id", id};
        try {
            if (id == null) {
                id = db.insert("""
                        INSERT INTO delivery_areas (tenant_id, name, fee, rider_pay, minutes, active, sort)
                        VALUES (:t, :name, :fee, :pay, :min, :active, (SELECT coalesce(max(sort), 0) + 1 FROM delivery_areas))
                        RETURNING id""", p);
            } else {
                db.one("Area", "SELECT id FROM delivery_areas WHERE id = :id", "id", id);
                db.exec("UPDATE delivery_areas SET name = :name, fee = :fee, rider_pay = :pay, minutes = :min, active = :active WHERE id = :id", p);
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("There's already an area called " + name);
        }
        return db.one("Area", "SELECT id, name, fee, rider_pay, minutes, active FROM delivery_areas WHERE id = :id", "id", id);
    }

    /**
     * Starting areas for Nairobi: about 130 places with boda fares from the CBD (estimates from the Trendysh delivery
     * list). Only areas the business doesn't have yet are added, and every one can be changed or switched off after.
     */
    public Map<String, Object> addNairobiAreas() {
        TenantContext.requireRole("MANAGER");
        JsonNode doc;
        try (var in = getClass().getResourceAsStream("/delivery-areas-nairobi.json")) {
            doc = json.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("delivery-areas-nairobi.json is missing", e);
        }
        int added = 0;
        int sort = db.value("SELECT coalesce(max(sort), 0) FROM delivery_areas", Integer.class);
        for (JsonNode a : doc.path("areas")) {
            added += db.exec("""
                    INSERT INTO delivery_areas (tenant_id, name, fee, rider_pay, minutes, sort)
                    VALUES (:t, :name, :fee, :pay, :min, :sort) ON CONFLICT (tenant_id, lower(name)) DO NOTHING""",
                    "name", a.path("name").asText(), "fee", a.path("fee").decimalValue(), "pay", a.path("riderPay").decimalValue(),
                    "min", a.path("minutes").asInt(30), "sort", ++sort);
        }
        return Map.of("added", added, "note", doc.path("note").asText());
    }

    /** Riders: staff with the RIDER job get a rider entry automatically. */
    public List<Map<String, Object>> riders() {
        db.exec("""
                INSERT INTO riders (tenant_id, user_id, name)
                SELECT :t, u.id, left(u.name, 80) FROM users u
                WHERE u.tenant_id = :t AND u.role = 'RIDER' AND u.active
                  AND NOT EXISTS (SELECT 1 FROM riders r WHERE r.user_id = u.id)""");
        return db.list("""
                SELECT r.id, r.user_id, r.name, r.phone, r.active, r.user_id IS NOT NULL AS signs_in,
                       (SELECT count(*) FROM deliveries d WHERE d.rider_id = r.id AND d.status = 'ON_THE_WAY') AS on_the_way,
                       (SELECT coalesce(sum(d.collected), 0) FROM deliveries d
                         WHERE d.rider_id = r.id AND d.collected > 0 AND d.handed_in_at IS NULL) AS cash_with_them,
                       (SELECT count(*) FROM deliveries d WHERE d.rider_id = r.id AND d.status = 'DELIVERED' AND d.rider_paid_at IS NULL AND d.rider_pay > 0) AS trips_not_paid,
                       (SELECT coalesce(sum(d.rider_pay), 0) FROM deliveries d
                         WHERE d.rider_id = r.id AND d.status = 'DELIVERED' AND d.rider_paid_at IS NULL) AS pay_not_paid
                FROM riders r ORDER BY r.active DESC, r.name""");
    }

    public Map<String, Object> saveRider(UUID id, Body b) {
        TenantContext.requireRole("MANAGER", "CASHIER");
        String name = clip(b.str("name", "").trim(), 80);
        if (name.isEmpty()) {
            throw ApiException.bad("Type the rider's name");
        }
        String phone = TableOrderService.phone(b.str("phone", null));
        if (id == null) {
            id = db.insert("INSERT INTO riders (tenant_id, name, phone) VALUES (:t, :name, :phone) RETURNING id", "name", name, "phone", phone);
        } else {
            db.one("Rider", "SELECT id FROM riders WHERE id = :id", "id", id);
            db.exec("UPDATE riders SET name = :name, phone = :phone, active = :active WHERE id = :id",
                    "id", id, "name", name, "phone", phone, "active", b.bool("active", true));
        }
        return db.one("Rider", "SELECT id, user_id, name, phone, active FROM riders WHERE id = :id", "id", id);
    }

    /** The rider entry of the signed-in staff member, if they're a rider. */
    public UUID myRider() {
        riders();
        return db.value("SELECT id FROM riders WHERE user_id = :u", UUID.class);
    }

    // ---------------- placing ----------------

    /** What a delivery needs: where to, who, and how they pay. */
    public record Where(UUID areaId, String areaName, BigDecimal fee, BigDecimal riderPay, String name, String phone, String address, String landmark) {
    }

    public Where where(Body d) {
        String phone = TableOrderService.phone(d.str("phone", null));
        if (phone == null) {
            throw ApiException.bad("Type a phone number the rider can call");
        }
        String address = clip(d.str("address", "").trim(), 300);
        if (address.length() < 4) {
            throw ApiException.bad("Type where to bring the food: the street, building or estate");
        }
        if (!d.has("areaId")) {
            throw ApiException.bad("Choose the area");
        }
        UUID areaId;
        try {
            areaId = UUID.fromString(d.str("areaId"));
        } catch (IllegalArgumentException e) {
            throw ApiException.bad("Choose the area");
        }
        Map<String, Object> area = db.find("SELECT * FROM delivery_areas WHERE id = :id AND active", "id", areaId)
                .orElseThrow(() -> ApiException.bad("We don't deliver to that area. Please choose another or call us."));
        return new Where(areaId, (String) area.get("name"), (BigDecimal) area.get("fee"), (BigDecimal) area.get("riderPay"),
                clip(d.str("name", null), 80), phone, address, clip(d.str("landmark", null), 200));
    }

    /**
     * Put a delivery on an order just created: the fee line (free above the owner's amount), the delivery row, the
     * channel, and the address saved for next time.
     */
    public void attach(UUID orderId, Where w, boolean payAtDoor) {
        Rules r = rules(TenantContext.tenantId());
        BigDecimal subtotal = db.value("SELECT coalesce(sum(line_total), 0) FROM order_lines WHERE order_id = :id", BigDecimal.class, "id", orderId);
        if (r.minimumOrder().signum() > 0 && subtotal.compareTo(r.minimumOrder()) < 0) {
            throw ApiException.bad("The smallest order we deliver is KES " + r.minimumOrder().toPlainString());
        }
        BigDecimal fee = r.freeAbove().signum() > 0 && subtotal.compareTo(r.freeAbove()) >= 0 ? BigDecimal.ZERO : w.fee();
        if (fee.signum() > 0) {
            orders.addLines(orderId, List.of(Map.of("name", "Delivery: " + w.areaName(), "unitPrice", fee, "qty", 1)));
        }
        orders.recalc(orderId);
        db.exec("""
                UPDATE orders SET channel = 'DELIVERY', customer_phone = coalesce(customer_phone, :phone),
                       customer_label = coalesce(customer_label, :name) WHERE id = :id""",
                "id", orderId, "phone", w.phone(), "name", w.name());
        db.exec("""
                INSERT INTO deliveries (tenant_id, order_id, area_id, area_name, name, phone, address, landmark, fee, pay_on_delivery,
                                        rider_pay, created_by)
                VALUES (:t, :o, :area, :areaName, :name, :phone, :address, :landmark, :fee, :door, :pay, :u)""",
                "o", orderId, "area", w.areaId(), "areaName", w.areaName(), "name", w.name(), "phone", w.phone(),
                "address", w.address(), "landmark", w.landmark(), "fee", fee, "door", payAtDoor, "pay", w.riderPay());
        db.exec("""
                INSERT INTO delivery_addresses (tenant_id, phone, name, area_id, address, landmark)
                VALUES (:t, :phone, :name, :area, :address, :landmark)
                ON CONFLICT (tenant_id, phone, lower(address))
                DO UPDATE SET name = coalesce(excluded.name, delivery_addresses.name), area_id = excluded.area_id,
                              landmark = coalesce(excluded.landmark, delivery_addresses.landmark), last_used_at = now()""",
                "phone", w.phone(), "name", w.name(), "area", w.areaId(), "address", w.address(), "landmark", w.landmark());
    }

    /**
     * A delivery ordered from the menu link: {lines, delivery: {phone, name, areaId, address, landmark, payAtDoor}}.
     * Paying first holds the order until the M-Pesa payment comes in; paying at the door sends it to the kitchen now.
     * All or nothing: a refused delivery leaves no order behind.
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> placeOnline(Map<String, Object> raw) {
        Rules r = rules(TenantContext.tenantId());
        if (!r.online()) {
            throw ApiException.forbidden("This restaurant isn't taking delivery orders online right now. Please call them.");
        }
        Body d = new Body(new Body(raw).map("delivery"));
        Where w = where(d);
        boolean door = (d.bool("payAtDoor", false) && r.payAtDoor()) || !r.payFirst();
        Map<String, Object> body = new LinkedHashMap<>(raw);
        body.remove("tableId");
        body.remove("delivery");
        if (w.name() != null) {
            body.put("customerLabel", w.name());
        }
        body.put("phone", w.phone());
        TableOrderService.Placed p = tableOrders.place(body, door ? "READY" : "FIRST");
        attach(p.orderId(), w, door);
        Map<String, Object> o = db.one("Order", "SELECT number, total, public_token, held_for_payment FROM orders WHERE id = :id", "id", p.orderId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", o.get("number"));
        out.put("token", o.get("publicToken"));
        out.put("total", o.get("total"));
        out.put("heldForPayment", o.get("heldForPayment"));
        out.put("payAtDoor", door);
        return out;
    }

    /** What the menu link shows for delivery: the areas and how customers may pay. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Map<String, Object> publicView() {
        Rules r = rules(TenantContext.tenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", r.online());
        out.put("payFirst", r.payFirst());
        out.put("payAtDoor", r.payAtDoor());
        out.put("freeAbove", r.freeAbove());
        out.put("minimumOrder", r.minimumOrder());
        out.put("areas", r.online() ? db.list("SELECT id, name, fee, minutes FROM delivery_areas WHERE active ORDER BY sort, name") : List.of());
        return out;
    }

    /** A delivery taken by phone at the restaurant: the order goes straight to the kitchen. */
    public Map<String, Object> placeByStaff(Body b) {
        TenantContext.requireRole("MANAGER", "CASHIER", "WAITER");
        Body d = new Body(b.map("delivery"));
        Where w = where(d);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", "DELIVERY");
        body.put("lines", b.list("lines"));
        body.put("customerLabel", w.name());
        body.put("note", b.str("note", null));
        if (b.list("lines") == null || b.list("lines").isEmpty()) {
            throw ApiException.bad("Add what they're ordering");
        }
        UUID orderId = orders.create(new Body(body));
        attach(orderId, w, d.bool("payAtDoor", true));
        tableOrders.sendToKitchen(orderId);
        return view(db.value("SELECT id FROM deliveries WHERE order_id = :o", UUID.class, "o", orderId));
    }

    /** Saved addresses for a phone number, newest first (staff only: the public never sees them). */
    public List<Map<String, Object>> addresses(String phoneRaw) {
        String phone = TableOrderService.phone(phoneRaw);
        if (phone == null) {
            return List.of();
        }
        return db.list("""
                SELECT a.name, a.area_id, ar.name AS area_name, a.address, a.landmark, a.last_used_at
                FROM delivery_addresses a LEFT JOIN delivery_areas ar ON ar.id = a.area_id
                WHERE a.phone = :phone ORDER BY a.last_used_at DESC LIMIT 5""", "phone", phone);
    }

    // ---------------- the board and the run sheet ----------------

    private static final String SELECT = """
            SELECT d.id, d.order_id, d.area_name, d.name, d.phone, d.address, d.landmark, d.fee, d.pay_on_delivery, d.status,
                   d.rider_id, r.name AS rider_name, r.phone AS rider_phone, d.rider_pay, d.picked_at, d.delivered_at, d.failed_reason,
                   d.collected, d.handed_in_at, d.rider_paid_at, d.rider_paid_how, d.created_at,
                   o.number, o.total, o.paid, greatest(o.total - o.paid, 0) AS to_collect, o.status AS order_status,
                   o.held_for_payment, o.ready_at, o.public_token, o.needs_pay_check,
                   (SELECT count(*) FROM order_lines l WHERE l.order_id = o.id AND l.kitchen_status IN ('NEW', 'PREPARING')) AS cooking,
                   (SELECT string_agg(trim(to_char(l.qty, 'FM999990.###')) || ' × ' || l.name, ', ' ORDER BY l.created_at)
                      FROM order_lines l WHERE l.order_id = o.id AND l.item_id IS NOT NULL) AS items
            FROM deliveries d JOIN orders o ON o.id = d.order_id LEFT JOIN riders r ON r.id = d.rider_id
            """;

    public Map<String, Object> view(UUID id) {
        return withStage(db.one("Delivery", SELECT + " WHERE d.id = :id", "id", id));
    }

    private Map<String, Object> withStage(Map<String, Object> d) {
        String stage;
        if (!"WAITING".equals(d.get("status"))) {
            stage = (String) d.get("status");
        } else if ("VOID".equals(d.get("orderStatus"))) {
            stage = "CANCELLED";
        } else if (Boolean.TRUE.equals(d.get("heldForPayment"))) {
            stage = "WAITING_PAYMENT";
        } else if (((Number) d.get("cooking")).intValue() > 0 || d.get("readyAt") == null) {
            stage = "COOKING";
        } else {
            stage = "READY";
        }
        d.put("stage", stage);
        // Plain words for the screen.
        d.put("stageLabel", switch (stage) {
            case "WAITING_PAYMENT" -> "Waiting for the M-Pesa payment";
            case "COOKING" -> "Cooking";
            case "READY" -> d.get("riderId") == null ? "Ready: needs a rider" : "Ready: rider to pick up";
            case "ON_THE_WAY" -> "On the way";
            case "DELIVERED" -> "Delivered";
            case "FAILED" -> "Couldn't deliver";
            default -> "Cancelled";
        });
        return d;
    }

    /** Today's deliveries (and any still on the road from earlier), with riders, cash and pay not yet given. */
    public Map<String, Object> board(LocalDate day) {
        LocalDate d = day == null ? LocalDate.now(NAIROBI) : day;
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> x : db.list(SELECT + """
                 WHERE (d.created_at AT TIME ZONE 'Africa/Nairobi')::date = :day
                    OR d.status IN ('WAITING', 'ON_THE_WAY')
                    OR (d.collected > 0 AND d.handed_in_at IS NULL)
                 ORDER BY d.created_at DESC""", "day", d)) {
            list.add(withStage(x));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", d.toString());
        out.put("deliveries", list);
        out.put("riders", riders());
        out.put("areas", areas(false));
        out.put("rules", rulesView(rules(TenantContext.tenantId())));
        return out;
    }

    /** The signed-in rider's run sheet: their trips, ready food nobody has taken, their cash and pay. */
    public Map<String, Object> mine() {
        UUID rider = myRider();
        if (rider == null) {
            throw ApiException.forbidden("You're not set up as a rider. Ask the owner to give you the Rider job.");
        }
        List<Map<String, Object>> trips = new ArrayList<>();
        for (Map<String, Object> x : db.list(SELECT + """
                 WHERE d.rider_id = :r AND (d.status IN ('WAITING', 'ON_THE_WAY')
                       OR (d.created_at AT TIME ZONE 'Africa/Nairobi')::date = (now() AT TIME ZONE 'Africa/Nairobi')::date
                       OR (d.collected > 0 AND d.handed_in_at IS NULL))
                 ORDER BY d.status = 'DELIVERED', d.created_at""", "r", rider)) {
            trips.add(withStage(x));
        }
        List<Map<String, Object>> free = new ArrayList<>();
        for (Map<String, Object> x : db.list(SELECT + " WHERE d.rider_id IS NULL AND d.status = 'WAITING' AND o.status <> 'VOID' ORDER BY d.created_at", new Object[0])) {
            Map<String, Object> v = withStage(x);
            if (List.of("READY", "COOKING").contains(v.get("stage"))) {
                free.add(v);
            }
        }
        Map<String, Object> me = db.one("Rider", "SELECT id, name, phone FROM riders WHERE id = :id", "id", rider);
        Map<String, Object> out = new LinkedHashMap<>(me);
        out.put("trips", trips);
        out.put("free", free);
        out.put("cashWithMe", db.value("SELECT coalesce(sum(collected), 0) FROM deliveries WHERE rider_id = :r AND collected > 0 AND handed_in_at IS NULL",
                BigDecimal.class, "r", rider));
        out.put("payNotPaid", db.value("SELECT coalesce(sum(rider_pay), 0) FROM deliveries WHERE rider_id = :r AND status = 'DELIVERED' AND rider_paid_at IS NULL",
                BigDecimal.class, "r", rider));
        out.put("paidToday", db.value("""
                SELECT coalesce(sum(rider_pay), 0) FROM deliveries WHERE rider_id = :r AND rider_paid_at IS NOT NULL
                  AND (rider_paid_at AT TIME ZONE 'Africa/Nairobi')::date = (now() AT TIME ZONE 'Africa/Nairobi')::date""",
                BigDecimal.class, "r", rider));
        return out;
    }

    // ---------------- moving a delivery along ----------------

    private Map<String, Object> lock(UUID id) {
        Map<String, Object> d = db.one("Delivery", "SELECT * FROM deliveries WHERE id = :id FOR UPDATE", "id", id);
        // A rider only touches their own trips (or takes a free one).
        TenantContext.Caller c = TenantContext.caller();
        if ("RIDER".equals(c.role())) {
            UUID me = myRider();
            if (d.get("riderId") != null && !d.get("riderId").toString().equals(String.valueOf(me))) {
                throw ApiException.forbidden("This delivery is another rider's");
            }
        }
        return d;
    }

    /** Give the delivery to a rider (staff), or a rider takes a free one ({@code riderId} null = me). */
    public Map<String, Object> assign(UUID id, UUID riderId) {
        Map<String, Object> d = lock(id);
        if (!List.of("WAITING", "ON_THE_WAY").contains(d.get("status"))) {
            throw ApiException.conflict("This delivery is finished");
        }
        UUID rider = riderId;
        if ("RIDER".equals(TenantContext.caller().role()) || rider == null) {
            rider = myRider();
            if (rider == null) {
                throw ApiException.bad("Choose a rider");
            }
            if (d.get("riderId") != null && !d.get("riderId").toString().equals(rider.toString())) {
                throw ApiException.conflict("Another rider has taken this one");
            }
        }
        Map<String, Object> r = db.one("Rider", "SELECT id, active FROM riders WHERE id = :id", "id", rider);
        if (!Boolean.TRUE.equals(r.get("active"))) {
            throw ApiException.bad("That rider is switched off");
        }
        db.exec("UPDATE deliveries SET rider_id = :r WHERE id = :id", "id", id, "r", rider);
        return view(id);
    }

    public Map<String, Object> picked(UUID id) {
        Map<String, Object> d = lock(id);
        if (!"WAITING".equals(d.get("status"))) {
            throw ApiException.conflict("This delivery has already left");
        }
        if (d.get("riderId") == null) {
            throw ApiException.bad("Choose the rider taking it first");
        }
        Map<String, Object> v = view(id);
        if ("WAITING_PAYMENT".equals(v.get("stage"))) {
            throw ApiException.bad("This order isn't paid yet, so it can't leave. Wait for the M-Pesa payment.");
        }
        db.exec("UPDATE deliveries SET status = 'ON_THE_WAY', picked_at = now() WHERE id = :id", "id", id);
        // The food has left the kitchen.
        db.exec("UPDATE order_lines SET kitchen_status = 'SERVED' WHERE order_id = :o AND kitchen_status = 'READY'", "o", d.get("orderId"));
        return view(id);
    }

    /**
     * Delivered. If the customer pays at the door, say how: {how: CASH} (the rider keeps it until handed in) or
     * {how: MPESA} (the prompt was paid, or {reference} when they paid the till directly: the cashier checks it).
     */
    public Map<String, Object> delivered(UUID id, Body b) {
        Map<String, Object> d = lock(id);
        if (!List.of("WAITING", "ON_THE_WAY").contains(d.get("status"))) {
            throw ApiException.conflict("This delivery is already finished");
        }
        if (d.get("riderId") == null) {
            throw ApiException.bad("Choose the rider who took it first");
        }
        UUID orderId = UUID.fromString(d.get("orderId").toString());
        Map<String, Object> o = db.one("Order", "SELECT status, total, paid FROM orders WHERE id = :id", "id", orderId);
        BigDecimal left = ((BigDecimal) o.get("total")).subtract((BigDecimal) o.get("paid")).max(BigDecimal.ZERO);
        BigDecimal collected = BigDecimal.ZERO;
        if ("OPEN".equals(o.get("status")) && left.signum() > 0) {
            String how = b.str("how", "").toUpperCase();
            if ("CASH".equals(how)) {
                collected = left;
            } else if ("MPESA".equals(how)) {
                String ref = clip(b.str("reference", "").trim().toUpperCase(), 20);
                if (ref.length() < 8) {
                    throw ApiException.bad("The M-Pesa payment hasn't come in yet. Send the prompt again, or type the M-Pesa code from the customer's message.");
                }
                orders.settleByServer(orderId, "MPESA", left, ref);
                db.exec("UPDATE orders SET needs_pay_check = true WHERE id = :id", "id", orderId);
            } else {
                throw ApiException.bad("Say how the customer paid: cash or M-Pesa");
            }
        }
        db.exec("""
                UPDATE deliveries SET status = 'DELIVERED', delivered_at = now(), picked_at = coalesce(picked_at, now()), collected = :c
                WHERE id = :id""", "id", id, "c", collected);
        db.exec("UPDATE order_lines SET kitchen_status = 'SERVED' WHERE order_id = :o AND kitchen_status = 'READY'", "o", orderId);
        return view(id);
    }

    /** Couldn't deliver (no answer, wrong address, refused). An unpaid order is cancelled; a paid one stays for a refund. */
    public Map<String, Object> failed(UUID id, Body b) {
        Map<String, Object> d = lock(id);
        if (!List.of("WAITING", "ON_THE_WAY").contains(d.get("status"))) {
            throw ApiException.conflict("This delivery is already finished");
        }
        String reason = clip(b.str("reason", "").trim(), 200);
        if (reason.isEmpty()) {
            throw ApiException.bad("Say what happened, e.g. the customer didn't pick up");
        }
        UUID orderId = UUID.fromString(d.get("orderId").toString());
        db.exec("UPDATE deliveries SET status = 'FAILED', failed_reason = :r WHERE id = :id", "id", id, "r", reason);
        Map<String, Object> o = db.one("Order", "SELECT status, paid FROM orders WHERE id = :id", "id", orderId);
        if ("OPEN".equals(o.get("status")) && ((BigDecimal) o.get("paid")).signum() == 0) {
            orders.voidOrder(orderId, "Delivery not made: " + reason);
        }
        return view(id);
    }

    /** Send the customer the M-Pesa prompt at the door (no till needed: it goes to the business's till or paybill). */
    public Map<String, Object> askMpesa(UUID id, String phoneRaw) {
        Map<String, Object> d = lock(id);
        UUID orderId = UUID.fromString(d.get("orderId").toString());
        Map<String, Object> o = db.one("Order", "SELECT status, total, paid FROM orders WHERE id = :id", "id", orderId);
        BigDecimal left = ((BigDecimal) o.get("total")).subtract((BigDecimal) o.get("paid"));
        if (!"OPEN".equals(o.get("status")) || left.signum() <= 0) {
            throw ApiException.conflict("This order is already paid");
        }
        String phone = phoneRaw == null || phoneRaw.isBlank() ? (String) d.get("phone") : phoneRaw;
        return mpesa.startTablePayment(orderId, phone, left, null, null);
    }

    // ---------------- cash and rider pay ----------------

    private UUID openTill() {
        UUID shift = db.value("SELECT id FROM shifts WHERE user_id = :u AND status = 'OPEN' LIMIT 1", UUID.class);
        if (shift == null) {
            throw ApiException.bad("Open your till first: the cash goes into it");
        }
        return shift;
    }

    /**
     * The rider hands in the cash they collected: {counted, keepPay}. Each bill is paid in cash into the cashier's till.
     * With keepPay the rider keeps their unpaid trip pay from it. Cash short is recorded against a staff rider.
     */
    public Map<String, Object> handIn(UUID riderId, Body b) {
        TenantContext.requireRole("MANAGER", "CASHIER", "ACCOUNTANT");
        UUID shift = openTill();
        Map<String, Object> rider = db.one("Rider", "SELECT * FROM riders WHERE id = :id", "id", riderId);
        List<Map<String, Object>> cash = db.list("""
                SELECT id, order_id, collected FROM deliveries
                WHERE rider_id = :r AND collected > 0 AND handed_in_at IS NULL FOR UPDATE""", "r", riderId);
        BigDecimal collected = cash.stream().map(x -> (BigDecimal) x.get("collected")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal kept = BigDecimal.ZERO;
        if (b.bool("keepPay", false)) {
            kept = payRider(riderId, "FROM_COLLECTED", shift);
        }
        BigDecimal expected = collected.subtract(kept);
        BigDecimal counted = b.num("counted", expected);
        if (counted.signum() < 0) {
            throw ApiException.bad("The cash counted can't be below zero");
        }
        if (cash.isEmpty() && kept.signum() == 0) {
            throw ApiException.bad(rider.get("name") + " has no cash to hand in");
        }
        for (Map<String, Object> x : cash) {
            UUID orderId = UUID.fromString(x.get("orderId").toString());
            Map<String, Object> o = db.one("Order", "SELECT status FROM orders WHERE id = :id", "id", orderId);
            if ("OPEN".equals(o.get("status"))) {
                orders.pay(orderId, new Body(Map.of("payments", List.of(Map.of("method", "CASH", "amount", x.get("collected"))))));
            }
            db.exec("UPDATE deliveries SET handed_in_at = now(), handed_in_to = :u WHERE id = :id", "id", x.get("id"));
        }
        BigDecimal short_ = expected.subtract(counted).max(BigDecimal.ZERO);
        boolean recorded = false;
        if (short_.signum() > 0 && rider.get("userId") != null) {
            // The till got the full bills; the missing cash is the rider's, like a waiter's shortage.
            db.exec("""
                    INSERT INTO staff_debts (tenant_id, user_id, kind, amount, note, by_user)
                    VALUES (:t, :w, 'SHORTAGE', :a, :note, :u)""",
                    "w", rider.get("userId"), "a", short_, "note", "Delivery cash short at hand-in");
            recorded = true;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collected", collected);
        out.put("keptAsPay", kept);
        out.put("expected", expected);
        out.put("counted", counted);
        out.put("short", short_);
        out.put("shortRecordedAgainstRider", recorded);
        return out;
    }

    /** Pay a rider for their delivered trips not yet paid: {how: TILL_CASH | MPESA}. Till cash comes off the till. */
    public Map<String, Object> pay(UUID riderId, Body b) {
        TenantContext.requireRole("MANAGER", "CASHIER", "ACCOUNTANT");
        db.one("Rider", "SELECT id FROM riders WHERE id = :id", "id", riderId);
        String how = "MPESA".equals(b.str("how", "TILL_CASH").toUpperCase()) ? "MPESA" : "TILL_CASH";
        UUID shift = "TILL_CASH".equals(how) ? openTill()
                : db.value("SELECT id FROM shifts WHERE user_id = :u AND status = 'OPEN' LIMIT 1", UUID.class);
        BigDecimal paid = payRider(riderId, how, shift);
        if (paid.signum() == 0) {
            throw ApiException.bad("Nothing to pay: every delivered trip is already paid");
        }
        return Map.of("paid", paid, "how", how);
    }

    private BigDecimal payRider(UUID riderId, String how, UUID shift) {
        return db.value("""
                WITH p AS (UPDATE deliveries SET rider_paid_at = now(), rider_paid_by = :u, rider_paid_how = :how, rider_paid_shift = :shift
                           WHERE rider_id = :r AND status = 'DELIVERED' AND rider_paid_at IS NULL RETURNING rider_pay)
                SELECT coalesce(sum(rider_pay), 0) FROM p""", BigDecimal.class, "r", riderId, "how", how, "shift", shift);
    }

    // ---------------- delivery-app orders ----------------

    /** An order from Glovo, Uber Eats or Bolt Food: {app, ref, lines}. The app pays later, less its cut. */
    public Map<String, Object> appOrder(Body b) {
        TenantContext.requireRole("MANAGER", "CASHIER", "WAITER");
        Rules r = rules(TenantContext.tenantId());
        String key = b.str("app", "");
        App app = r.apps().stream().filter(a -> a.key().equals(key)).findFirst()
                .orElseThrow(() -> ApiException.bad("Choose the app the order came from"));
        String ref = clip(b.str("ref", "").trim(), 40);
        if (ref.isEmpty()) {
            throw ApiException.bad("Type the app's order number, so it can be matched when the app pays");
        }
        if (b.list("lines") == null || b.list("lines").isEmpty()) {
            throw ApiException.bad("Add what was ordered");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", "DELIVERY");
        body.put("lines", b.list("lines"));
        body.put("customerLabel", clip(app.name() + " " + ref, 60));
        UUID orderId = orders.create(new Body(body));
        tableOrders.sendToKitchen(orderId);
        BigDecimal total = db.value("SELECT total FROM orders WHERE id = :id", BigDecimal.class, "id", orderId);
        BigDecimal cut = total.multiply(app.cut()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        db.exec("UPDATE orders SET delivery_app = :app, app_order_ref = :ref, app_commission = :cut WHERE id = :id",
                "id", orderId, "app", app.key(), "ref", ref, "cut", cut);
        // The sale is done: the app collects from the customer and pays the restaurant later. No cash at the till.
        orders.settleByServer(orderId, "APP", total, app.name() + " " + ref);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("number", db.value("SELECT number FROM orders WHERE id = :id", Integer.class, "id", orderId));
        out.put("total", total);
        out.put("cut", cut);
        out.put("kept", total.subtract(cut));
        out.put("app", app.name());
        return out;
    }

    // ---------------- report ----------------

    public Map<String, Object> report(LocalDate from, LocalDate to) {
        TenantContext.requireRole("MANAGER", "ACCOUNTANT");
        LocalDate f = from == null ? LocalDate.now(NAIROBI).withDayOfMonth(1) : from;
        LocalDate t = to == null ? LocalDate.now(NAIROBI) : to;
        Object[] p = {"from", f, "to", t};
        String inDays = "(%s AT TIME ZONE 'Africa/Nairobi')::date BETWEEN :from AND :to";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("own", db.one("Deliveries", """
                SELECT count(*) AS orders,
                       count(*) FILTER (WHERE d.status = 'DELIVERED') AS delivered,
                       count(*) FILTER (WHERE d.status = 'FAILED') AS failed,
                       coalesce(sum(o.total) FILTER (WHERE d.status = 'DELIVERED'), 0) AS sales,
                       coalesce(sum(d.fee) FILTER (WHERE d.status = 'DELIVERED'), 0) AS fees,
                       coalesce(sum(d.rider_pay) FILTER (WHERE d.status = 'DELIVERED'), 0) AS rider_pay,
                       round(avg(extract(epoch FROM d.delivered_at - d.created_at) / 60) FILTER (WHERE d.status = 'DELIVERED')) AS avg_minutes
                FROM deliveries d JOIN orders o ON o.id = d.order_id WHERE """ + inDays.formatted("d.created_at"), p));
        out.put("apps", db.list("""
                SELECT delivery_app AS app, count(*) AS orders, coalesce(sum(total), 0) AS sales,
                       coalesce(sum(app_commission), 0) AS cut, coalesce(sum(total - app_commission), 0) AS kept
                FROM orders WHERE delivery_app IS NOT NULL AND status IN ('PAID', 'CREDIT') AND """ + inDays.formatted("created_at") + " " + """
                 GROUP BY delivery_app ORDER BY sales DESC""", p));
        out.put("riders", db.list("""
                SELECT r.name, count(*) AS trips, coalesce(sum(d.rider_pay), 0) AS pay,
                       coalesce(sum(d.rider_pay) FILTER (WHERE d.rider_paid_at IS NULL), 0) AS not_paid
                FROM deliveries d JOIN riders r ON r.id = d.rider_id
                WHERE d.status = 'DELIVERED' AND """ + inDays.formatted("d.created_at") + " " + """
                 GROUP BY r.name ORDER BY trips DESC""", p));
        out.put("areas", db.list("""
                SELECT d.area_name AS area, count(*) AS orders, coalesce(sum(o.total), 0) AS sales
                FROM deliveries d JOIN orders o ON o.id = d.order_id
                WHERE d.status = 'DELIVERED' AND """ + inDays.formatted("d.created_at") + " " + """
                 GROUP BY d.area_name ORDER BY orders DESC""", p));
        return out;
    }

    static String clip(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
