package com.lezitech.suite.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.notify.EmailLayout;
import com.lezitech.suite.notify.Mailer;
import com.lezitech.suite.notify.WebPush;
import com.lezitech.suite.sales.OrderService;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * R7, cake orders: flavour and size from the bakery's price list, the writing, a photo of the design, pickup or
 * delivery on a day and time, and a deposit by M-Pesa (the owner's %; 0% is shown to customers as "no deposit needed
 * for now"). Staff get the order on their phones; the customer gets an email, and a reminder the day before; the cake
 * shows on the bake list from two days before. On pickup the bill opens with the deposit already counted.
 */
@Service
public class Cakes {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Flavour(String name, BigDecimal perKg) {
    }

    public record Rules(boolean online, int depositPct, int leadHours, List<Flavour> flavours, List<BigDecimal> sizes,
                        BigDecimal writingPrice, boolean delivery) {
    }

    private final Db db;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OrderService orders;
    private final Mailer mailer;
    private final WebPush push;
    private final Delivery delivery;

    public Cakes(Db db, JdbcTemplate jdbc, ObjectMapper json, OrderService orders, Mailer mailer, WebPush push, Delivery delivery) {
        this.db = db;
        this.jdbc = jdbc;
        this.json = json;
        this.orders = orders;
        this.mailer = mailer;
        this.push = push;
        this.delivery = delivery;
    }

    // ---------------- rules ----------------

    public Rules rules(UUID tenant) {
        JsonNode s;
        try {
            s = json.readTree(jdbc.queryForObject("SELECT coalesce(settings->'cakes', '{}'::jsonb)::text FROM tenants WHERE id = ?", String.class, tenant));
        } catch (Exception e) {
            s = json.createObjectNode();
        }
        List<Flavour> flavours = new ArrayList<>();
        if (s.path("flavours").isArray()) {
            s.path("flavours").forEach(f -> flavours.add(new Flavour(f.path("name").asText(), f.path("perKg").decimalValue())));
        } else {
            // A starting price list (Nairobi bakeries, per kg); the owner changes it.
            flavours.add(new Flavour("Vanilla", new BigDecimal("1800")));
            flavours.add(new Flavour("Chocolate", new BigDecimal("2000")));
            flavours.add(new Flavour("Black forest", new BigDecimal("2200")));
            flavours.add(new Flavour("Red velvet", new BigDecimal("2400")));
            flavours.add(new Flavour("Fruit cake", new BigDecimal("2600")));
        }
        List<BigDecimal> sizes = new ArrayList<>();
        if (s.path("sizes").isArray()) {
            s.path("sizes").forEach(x -> sizes.add(x.decimalValue()));
        } else {
            for (String x : new String[]{"0.5", "1", "1.5", "2", "3", "4", "5"}) {
                sizes.add(new BigDecimal(x));
            }
        }
        return new Rules(s.path("online").asBoolean(true), Math.max(0, Math.min(100, s.path("depositPct").asInt(50))),
                Math.max(0, Math.min(720, s.path("leadHours").asInt(24))), flavours, sizes,
                s.path("writingPrice").isNumber() ? s.path("writingPrice").decimalValue() : BigDecimal.ZERO, s.path("delivery").asBoolean(true));
    }

    public Map<String, Object> rulesView(Rules r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", r.online());
        out.put("depositPct", r.depositPct());
        out.put("leadHours", r.leadHours());
        out.put("flavours", r.flavours().stream().map(f -> Map.of("name", f.name(), "perKg", f.perKg())).toList());
        out.put("sizes", r.sizes());
        out.put("writingPrice", r.writingPrice());
        out.put("delivery", r.delivery());
        return out;
    }

    public void saveRules(Body b) {
        TenantContext.requireRole("MANAGER");
        List<Map<String, Object>> flavours = new ArrayList<>();
        for (Map<String, Object> f : b.list("flavours") == null ? List.<Map<String, Object>>of() : b.list("flavours")) {
            Body fb = new Body(f);
            String name = fb.str("name", "").trim();
            BigDecimal per = fb.num("perKg", null);
            if (name.isEmpty()) {
                continue;
            }
            if (per == null || per.signum() <= 0) {
                throw ApiException.bad("Give " + name + " a price per kg");
            }
            flavours.add(Map.of("name", name.length() > 60 ? name.substring(0, 60) : name, "perKg", per));
        }
        if (flavours.isEmpty()) {
            throw ApiException.bad("Add at least one flavour with its price per kg");
        }
        List<BigDecimal> sizes = new ArrayList<>();
        if (b.raw("sizes") instanceof List<?> l) {
            for (Object o : l) {
                BigDecimal kg = new BigDecimal(String.valueOf(o));
                if (kg.signum() > 0 && kg.compareTo(new BigDecimal("50")) <= 0 && !sizes.contains(kg)) {
                    sizes.add(kg);
                }
            }
        }
        if (sizes.isEmpty()) {
            throw ApiException.bad("Add at least one size in kg");
        }
        sizes.sort(BigDecimal::compareTo);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("online", b.bool("online", true));
        s.put("depositPct", Math.max(0, Math.min(100, b.integer("depositPct", 50))));
        s.put("leadHours", Math.max(0, Math.min(720, b.integer("leadHours", 24))));
        s.put("flavours", flavours);
        s.put("sizes", sizes);
        s.put("writingPrice", b.num("writingPrice", BigDecimal.ZERO).max(BigDecimal.ZERO));
        s.put("delivery", b.bool("delivery", true));
        jdbc.update("UPDATE tenants SET settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{cakes}', cast(? as jsonb)) WHERE id = ?",
                db.toJson(s), TenantContext.tenantId());
    }

    // ---------------- ordering ----------------

    static BigDecimal price(Rules r, String flavour, BigDecimal kg, boolean writing) {
        Flavour f = r.flavours().stream().filter(x -> x.name().equalsIgnoreCase(flavour)).findFirst()
                .orElseThrow(() -> ApiException.bad("Choose a flavour from the list"));
        BigDecimal p = f.perKg().multiply(kg);
        if (writing) {
            p = p.add(r.writingPrice());
        }
        return p.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * A cake order: {name, phone, email, flavour, sizeKg, tiers, message, notes, date, time, fulfil, areaId, address,
     * landmark}; staff may also send {price, depositPaid}.
     */
    public Map<String, Object> place(Body b, String source) {
        Rules r = rules(TenantContext.tenantId());
        boolean online = "ONLINE".equals(source);
        if (online && !r.online()) {
            throw ApiException.forbidden("This bakery isn't taking cake orders online right now. Please call them.");
        }
        String name = clip(b.str("name", "").trim(), 80);
        if (name.isEmpty()) {
            throw ApiException.bad("Type the name for the order");
        }
        String phone = TableOrderService.phone(b.str("phone", null));
        if (phone == null) {
            throw ApiException.bad("Type a phone number we can call about the cake");
        }
        String email = b.str("email", "").trim();
        if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.bad("That email doesn't look right");
        }
        BigDecimal kg = b.num("sizeKg", null);
        if (kg == null || (online ? r.sizes().stream().noneMatch(x -> x.compareTo(kg) == 0) : kg.signum() <= 0)) {
            throw ApiException.bad("Choose a size");
        }
        String flavour = b.str("flavour", "").trim();
        String message = clip(b.str("message", "").trim(), 120);
        BigDecimal price = online || !b.has("price") ? price(r, flavour, kg, !message.isEmpty()) : b.num("price");
        if (price.signum() <= 0) {
            throw ApiException.bad("Give the cake's price");
        }
        LocalDate date;
        LocalTime time;
        try {
            date = LocalDate.parse(b.str("date"));
            time = LocalTime.parse(b.str("time", "12:00"));
        } catch (Exception e) {
            throw ApiException.bad("Choose the day and time you need it");
        }
        Instant due = ZonedDateTime.of(date, time, NAIROBI).toInstant();
        if (online && due.isBefore(Instant.now().plusSeconds(r.leadHours() * 3600L))) {
            throw ApiException.bad("We need at least " + r.leadHours() + " hours to bake it. Choose a later time, or call us for something urgent.");
        }
        String fulfil = "DELIVERY".equals(b.str("fulfil", "PICKUP")) ? "DELIVERY" : "PICKUP";
        UUID areaId = null;
        if ("DELIVERY".equals(fulfil)) {
            if (!r.delivery()) {
                throw ApiException.bad("This bakery doesn't deliver cakes. Please choose pickup.");
            }
            Body where = new Body(Map.of("phone", phone, "areaId", b.str("areaId", ""), "address", b.str("address", "")));
            areaId = delivery.where(where).areaId();
        }
        BigDecimal deposit = online ? price.multiply(BigDecimal.valueOf(r.depositPct())).divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING) : BigDecimal.ZERO;
        String status = deposit.signum() > 0 ? "WAITING_DEPOSIT" : "CONFIRMED";
        String token = token();
        UUID id = db.insert("""
                INSERT INTO cake_orders (tenant_id, token, source, status, name, phone, email, flavour, size_kg, tiers, message, notes, price,
                                         deposit_amount, deposit_status, due_at, fulfil, area_id, address, landmark, created_by, acknowledged_at)
                VALUES (:t, :tk, :src, :st, :name, :phone, :email, :fl, :kg, :tiers, :msg, :notes, :price, :dep, :ds, :due, :fulfil,
                        :area, :addr, :lm, :u, CASE WHEN :staff THEN now() END) RETURNING id""",
                "tk", token, "src", source, "st", status, "name", name, "phone", phone, "email", email.isEmpty() ? null : email,
                "fl", clip(flavour, 80), "kg", kg, "tiers", Math.max(1, Math.min(6, b.integer("tiers", 1))), "msg", message.isEmpty() ? null : message,
                "notes", clip(b.str("notes", null), 500), "price", price, "dep", deposit, "ds", deposit.signum() > 0 ? "WAITING" : "NONE",
                "due", Timestamp.from(due), "fulfil", fulfil, "area", areaId, "addr", clip(b.str("address", null), 300),
                "lm", clip(b.str("landmark", null), 200), "staff", !online);
        // Staff may take a deposit at the counter when ordering by phone or in person.
        if (!online && b.num("depositPaid", BigDecimal.ZERO).signum() > 0) {
            db.exec("UPDATE cake_orders SET deposit_amount = :d, deposit_status = 'PAID', deposit_receipt = :rc WHERE id = :id",
                    "id", id, "d", b.num("depositPaid").min(price), "rc", clip(b.str("depositReference", "Counter"), 30));
        }
        if (online) {
            push.toStaff(List.of("MANAGER", "CASHIER", "KITCHEN"), "New cake order",
                    name + " · " + kg.stripTrailingZeros().toPlainString() + " kg " + flavour + " · " + DAY.format(due.atZone(NAIROBI)) + " " + TIME.format(due.atZone(NAIROBI)),
                    "/cakes", "cake-" + id);
        }
        if ("CONFIRMED".equals(status)) {
            confirmEmail(id);
        }
        return view(id);
    }

    /** The deposit was paid by M-Pesa. */
    public void depositPaid(UUID id) {
        confirmEmail(id);
        Map<String, Object> v = view(id);
        push.toStaff(List.of("MANAGER", "CASHIER", "KITCHEN"), "Cake deposit paid", v.get("name") + " · KES " + ((BigDecimal) v.get("depositAmount")).toBigInteger(),
                "/cakes", "cake-" + id);
    }

    public Map<String, Object> view(UUID id) {
        Map<String, Object> c = new LinkedHashMap<>(db.one("Cake order", """
                SELECT c.*, a.name AS area_name, o.number AS bill_number, o.status AS bill_status, extract(epoch FROM c.due_at) AS due_epoch
                FROM cake_orders c LEFT JOIN delivery_areas a ON a.id = c.area_id LEFT JOIN orders o ON o.id = c.order_id
                WHERE c.id = :id""", "id", id));
        Instant due = epoch(c.get("dueEpoch"));
        c.put("day", DAY.format(due.atZone(NAIROBI)));
        c.put("time", TIME.format(due.atZone(NAIROBI)));
        c.put("balance", ((BigDecimal) c.get("price")).subtract("PAID".equals(c.get("depositStatus")) || "APPLIED".equals(c.get("depositStatus"))
                ? (BigDecimal) c.get("depositAmount") : BigDecimal.ZERO));
        return c;
    }

    /** What the customer's page shows (no staff-only fields). */
    public Map<String, Object> publicView(UUID id) {
        Map<String, Object> v = view(id);
        for (String k : List.of("createdBy", "tenantId", "orderId", "acknowledgedAt", "remindedAt", "designFileId", "billStatus")) {
            v.remove(k);
        }
        return v;
    }

    public UUID idOfToken(String token) {
        UUID id = db.value("SELECT id FROM cake_orders WHERE token = :tk", UUID.class, "tk", token);
        if (id == null) {
            throw ApiException.notFound("Cake order");
        }
        return id;
    }

    /** The bakery's list: from a day (default today) on, with the bake list (due in the next two days). */
    public Map<String, Object> list(LocalDate from) {
        LocalDate f = from == null ? LocalDate.now(NAIROBI) : from;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : db.list("""
                SELECT id FROM cake_orders
                WHERE (due_at AT TIME ZONE 'Africa/Nairobi')::date >= :f OR status IN ('CONFIRMED', 'BAKING', 'READY', 'WAITING_DEPOSIT')
                ORDER BY due_at LIMIT 300""", "f", f)) {
            rows.add(view(UUID.fromString(r.get("id").toString())));
        }
        Instant soon = Instant.now().plusSeconds(48 * 3600);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", rows);
        out.put("bakeList", rows.stream().filter(x -> List.of("CONFIRMED", "BAKING").contains(x.get("status")) && epoch(x.get("dueEpoch")).isBefore(soon)).toList());
        out.put("rules", rulesView(rules(TenantContext.tenantId())));
        return out;
    }

    /** BAKING or READY (or back to CONFIRMED). READY emails the customer. */
    public Map<String, Object> setStatus(UUID id, String status) {
        TenantContext.requireRole("MANAGER", "CASHIER", "KITCHEN");
        Map<String, Object> c = db.one("Cake order", "SELECT status FROM cake_orders WHERE id = :id FOR UPDATE", "id", id);
        if (!List.of("CONFIRMED", "BAKING", "READY").contains(status)) {
            throw ApiException.bad("Choose baking or ready");
        }
        if (!List.of("CONFIRMED", "BAKING", "READY", "WAITING_DEPOSIT").contains(c.get("status"))) {
            throw ApiException.conflict("This order is finished");
        }
        db.exec("UPDATE cake_orders SET status = :s, acknowledged_at = coalesce(acknowledged_at, now()) WHERE id = :id", "id", id, "s", status);
        if ("READY".equals(status)) {
            readyEmail(id);
        }
        return view(id);
    }

    /** Change the price after a chat with the customer (e.g. a fancier design). The deposit already paid stays. */
    public Map<String, Object> setPrice(UUID id, BigDecimal price) {
        TenantContext.requireRole("MANAGER", "CASHIER");
        if (price == null || price.signum() <= 0) {
            throw ApiException.bad("Give the new price");
        }
        Map<String, Object> c = db.one("Cake order", "SELECT status, deposit_status, deposit_amount FROM cake_orders WHERE id = :id FOR UPDATE", "id", id);
        if (List.of("COLLECTED", "CANCELLED").contains(c.get("status"))) {
            throw ApiException.conflict("This order is finished");
        }
        db.exec("UPDATE cake_orders SET price = :p WHERE id = :id", "id", id, "p", price);
        return view(id);
    }

    /**
     * Collected (or sent out with a rider): the cake's bill opens with the deposit already on it; the rest is paid at
     * the till as usual. For delivery, the bill goes on the Deliveries board, ready for a rider.
     */
    public Map<String, Object> collect(UUID id) {
        TenantContext.requireRole("MANAGER", "CASHIER", "WAITER");
        Map<String, Object> c = db.one("Cake order", "SELECT * FROM cake_orders WHERE id = :id FOR UPDATE", "id", id);
        if (!List.of("CONFIRMED", "BAKING", "READY").contains(c.get("status"))) {
            throw ApiException.conflict("WAITING_DEPOSIT".equals(c.get("status")) ? "The deposit isn't paid yet" : "This order is finished");
        }
        BigDecimal kg = (BigDecimal) c.get("sizeKg");
        String label = kg.stripTrailingZeros().toPlainString() + " kg " + c.get("flavour") + " cake" + (c.get("message") != null ? " (\"" + c.get("message") + "\")" : "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", "DELIVERY".equals(c.get("fulfil")) ? "DELIVERY" : "TAKEAWAY");
        body.put("customerLabel", c.get("name"));
        body.put("lines", List.of(Map.of("name", clip(label, 200), "unitPrice", c.get("price"), "qty", 1, "taxRate", 16)));
        UUID orderId = orders.createTrusted(new Body(body));
        db.exec("UPDATE orders SET customer_phone = :p, ready_at = now() WHERE id = :o", "o", orderId, "p", c.get("phone"));
        BigDecimal dep = (BigDecimal) c.get("depositAmount");
        if ("PAID".equals(c.get("depositStatus")) && dep.signum() > 0) {
            orders.depositByServer(orderId, "MPESA", dep, (String) c.get("depositReceipt"));
        }
        if ("DELIVERY".equals(c.get("fulfil"))) {
            Delivery.Where w = delivery.where(new Body(Map.of("phone", c.get("phone"), "name", c.get("name"),
                    "areaId", String.valueOf(c.get("areaId")), "address", c.get("address") == null ? "" : c.get("address"),
                    "landmark", c.get("landmark") == null ? "" : c.get("landmark"))));
            delivery.attach(orderId, w, true);
        }
        db.exec("""
                UPDATE cake_orders SET status = 'COLLECTED', order_id = :o,
                       deposit_status = CASE WHEN deposit_status = 'PAID' THEN 'APPLIED' ELSE deposit_status END WHERE id = :id""",
                "id", id, "o", orderId);
        return view(id);
    }

    public Map<String, Object> cancel(UUID id, String reason, boolean byCustomer) {
        Map<String, Object> c = db.one("Cake order", "SELECT status, extract(epoch FROM due_at) AS due_epoch FROM cake_orders WHERE id = :id FOR UPDATE", "id", id);
        if (List.of("COLLECTED", "CANCELLED").contains(c.get("status"))) {
            throw ApiException.conflict("This order is already finished");
        }
        if (byCustomer && epoch(c.get("dueEpoch")).isBefore(Instant.now().plusSeconds(24 * 3600))) {
            throw ApiException.bad("It's less than a day away, so the cake may already be baking. Please call the bakery.");
        }
        db.exec("UPDATE cake_orders SET status = 'CANCELLED', cancel_reason = :r WHERE id = :id", "id", id,
                "r", clip(reason == null || reason.isBlank() ? (byCustomer ? "Cancelled by the customer" : "Cancelled") : reason, 200));
        return view(id);
    }

    /** New online orders nobody has opened (for the cake page's badge). */
    public int unseen() {
        return db.value("SELECT count(*) FROM cake_orders WHERE source = 'ONLINE' AND acknowledged_at IS NULL AND status <> 'CANCELLED'", Integer.class);
    }

    public int seen() {
        return db.exec("UPDATE cake_orders SET acknowledged_at = now() WHERE acknowledged_at IS NULL");
    }

    // ---------------- reminders and emails ----------------

    /** The day-before reminder (run every few minutes; each order once). */
    public void remind() {
        LocalDate tomorrow = LocalDate.now(NAIROBI).plusDays(1);
        for (Map<String, Object> r : db.list("""
                SELECT id FROM cake_orders WHERE reminded_at IS NULL AND status IN ('CONFIRMED', 'BAKING', 'READY')
                  AND (due_at AT TIME ZONE 'Africa/Nairobi')::date <= :d AND due_at > now()""", "d", tomorrow)) {
            UUID id = UUID.fromString(r.get("id").toString());
            db.exec("UPDATE cake_orders SET reminded_at = now() WHERE id = :id", "id", id);
            reminderEmail(id);
        }
        // An unpaid deposit after a day: the order lapses.
        db.exec("""
                UPDATE cake_orders SET status = 'CANCELLED', cancel_reason = 'The deposit wasn''t paid'
                WHERE status = 'WAITING_DEPOSIT' AND created_at < now() - interval '24 hours'""");
    }

    private Map<String, Object> biz() {
        return jdbc.queryForMap("SELECT name, slug, brand_color AS \"brandColor\" FROM tenants WHERE id = ?", TenantContext.tenantId());
    }

    String link(Map<String, Object> biz, String token) {
        return mailer.appUrl() + "/cakes/" + biz.get("slug") + "/o/" + token;
    }

    private String[][] facts(Map<String, Object> v) {
        List<String[]> f = new ArrayList<>();
        f.add(new String[]{"Cake", ((BigDecimal) v.get("sizeKg")).stripTrailingZeros().toPlainString() + " kg " + v.get("flavour")});
        if (v.get("message") != null) {
            f.add(new String[]{"Writing", "“" + v.get("message") + "”"});
        }
        f.add(new String[]{"DELIVERY".equals(v.get("fulfil")) ? "Delivery" : "Pickup", v.get("day") + ", " + v.get("time")});
        f.add(new String[]{"Price", "KES " + ((BigDecimal) v.get("price")).stripTrailingZeros().toPlainString()});
        BigDecimal dep = (BigDecimal) v.get("depositAmount");
        if (dep.signum() > 0 && List.of("PAID", "APPLIED").contains(v.get("depositStatus"))) {
            f.add(new String[]{"Deposit paid", "KES " + dep.stripTrailingZeros().toPlainString()});
            f.add(new String[]{"Left to pay", "KES " + ((BigDecimal) v.get("balance")).stripTrailingZeros().toPlainString()});
        }
        return f.toArray(new String[0][]);
    }

    void confirmEmail(UUID id) {
        Map<String, Object> v = view(id);
        if (v.get("email") == null) {
            return;
        }
        Map<String, Object> biz = biz();
        String name = (String) v.get("name");
        mailer.trySendAs(TenantContext.tenantId(), (String) v.get("email"), name, "Your cake order at " + biz.get("name") + ": " + v.get("day"),
                "Hi " + first(name) + ",\n\nThank you! We have your cake order for " + v.get("day") + ", " + v.get("time") + ".\n"
                        + "See your order: " + link(biz, (String) v.get("token")) + "\n\n" + biz.get("name"),
                EmailLayout.h("We have your cake order", null)
                        + EmailLayout.p("Hi " + Mailer.esc(first(name)) + ", thank you! We'll bake it fresh for you.")
                        + EmailLayout.facts(facts(v))
                        + EmailLayout.button("See your order", link(biz, (String) v.get("token")), (String) biz.get("brandColor")));
    }

    void reminderEmail(UUID id) {
        Map<String, Object> v = view(id);
        if (v.get("email") == null) {
            return;
        }
        Map<String, Object> biz = biz();
        String name = (String) v.get("name");
        boolean delivering = "DELIVERY".equals(v.get("fulfil"));
        mailer.trySendAs(TenantContext.tenantId(), (String) v.get("email"), name,
                (delivering ? "Your cake arrives " : "Your cake is ready ") + v.get("day") + ", " + v.get("time"),
                "Hi " + first(name) + ",\n\nA reminder: your cake " + (delivering ? "comes to you" : "is ready to collect") + " on "
                        + v.get("day") + ", " + v.get("time") + ".\n" + link(biz, (String) v.get("token")) + "\n\n" + biz.get("name"),
                EmailLayout.h("See you " + v.get("day"), null)
                        + EmailLayout.p("Hi " + Mailer.esc(first(name)) + ", a reminder about your cake.")
                        + EmailLayout.facts(facts(v))
                        + EmailLayout.button("See your order", link(biz, (String) v.get("token")), (String) biz.get("brandColor")));
    }

    void readyEmail(UUID id) {
        Map<String, Object> v = view(id);
        if (v.get("email") == null || "DELIVERY".equals(v.get("fulfil"))) {
            return;
        }
        Map<String, Object> biz = biz();
        String name = (String) v.get("name");
        mailer.trySendAs(TenantContext.tenantId(), (String) v.get("email"), name, "Your cake is ready at " + biz.get("name"),
                "Hi " + first(name) + ",\n\nYour cake is ready to collect.\n" + link(biz, (String) v.get("token")) + "\n\n" + biz.get("name"),
                EmailLayout.h("Your cake is ready", null)
                        + EmailLayout.p("Hi " + Mailer.esc(first(name)) + ", come and collect it whenever suits you.")
                        + EmailLayout.facts(facts(v))
                        + EmailLayout.button("See your order", link(biz, (String) v.get("token")), (String) biz.get("brandColor")));
    }

    // ---------------- helpers ----------------

    static String token() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String first(String name) {
        return name == null ? "" : name.trim().split("\\s+")[0];
    }

    static String clip(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }

    static Instant epoch(Object seconds) {
        return Instant.ofEpochMilli(Math.round(((Number) seconds).doubleValue() * 1000));
    }

    static Instant asInstant(Object o) {
        if (o instanceof Timestamp t) {
            return t.toInstant();
        }
        String s = String.valueOf(o).replace(' ', 'T');
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(s);
            } catch (RuntimeException e2) {
                return LocalDateTime.parse(s.replaceAll("[+Z].*$", "")).atZone(NAIROBI).toInstant();
            }
        }
    }
}
