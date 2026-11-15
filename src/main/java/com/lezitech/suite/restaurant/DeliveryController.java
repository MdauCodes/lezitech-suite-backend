package com.lezitech.suite.restaurant;

import com.lezitech.suite.common.Body;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** R5, delivery: the board, the rider's run sheet, cash hand-in and rider pay, app orders, areas, rules and the report. */
@RestController
@RequestMapping("/api/delivery")
@Transactional
public class DeliveryController {

    private final Delivery delivery;

    public DeliveryController(Delivery delivery) {
        this.delivery = delivery;
    }

    private static void staffOnly() {
        TenantContext.requireRole("MANAGER", "CASHIER", "WAITER", "ACCOUNTANT");
    }

    @GetMapping
    public Map<String, Object> board(@RequestParam(required = false) LocalDate day) {
        staffOnly();
        return delivery.board(day);
    }

    /** A delivery taken by phone: {lines, delivery: {phone, name, areaId, address, landmark, payAtDoor}, note}. */
    @PostMapping
    public Map<String, Object> place(@RequestBody Map<String, Object> raw) {
        return delivery.placeByStaff(new Body(raw));
    }

    @GetMapping("/addresses")
    public List<Map<String, Object>> addresses(@RequestParam String phone) {
        staffOnly();
        return delivery.addresses(phone);
    }

    /** What should ring on this screen (new online deliveries, or a rider's new trips). */
    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        return delivery.alerts();
    }

    @PostMapping("/alerts/stop")
    public Map<String, Object> stopAlerts() {
        return Map.of("stopped", delivery.stopAlerts());
    }

    @GetMapping("/mine")
    public Map<String, Object> mine() {
        return delivery.mine();
    }

    @GetMapping("/{id}")
    public Map<String, Object> one(@PathVariable UUID id) {
        if ("RIDER".equals(TenantContext.caller().role())) {
            Map<String, Object> d = delivery.view(id);
            UUID me = delivery.myRider();
            if (d.get("riderId") != null && !d.get("riderId").toString().equals(String.valueOf(me))) {
                throw com.lezitech.suite.common.ApiException.forbidden("This delivery is another rider's");
            }
            return d;
        }
        return delivery.view(id);
    }

    /** {riderId}; a rider sends nothing to take a free delivery themselves. */
    @PostMapping("/{id}/rider")
    public Map<String, Object> assign(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        Body b = new Body(raw == null ? Map.of() : raw);
        return delivery.assign(id, b.has("riderId") ? UUID.fromString(b.str("riderId")) : null);
    }

    @PostMapping("/{id}/picked")
    public Map<String, Object> picked(@PathVariable UUID id) {
        return delivery.picked(id);
    }

    /** {how: CASH | MPESA, reference}. */
    @PostMapping("/{id}/delivered")
    public Map<String, Object> delivered(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        return delivery.delivered(id, new Body(raw == null ? Map.of() : raw));
    }

    /** {reason}. */
    @PostMapping("/{id}/failed")
    public Map<String, Object> failed(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return delivery.failed(id, new Body(raw));
    }

    /** Send the customer the M-Pesa prompt at the door: {phone} (default the delivery's number). */
    @PostMapping("/{id}/mpesa")
    public Map<String, Object> mpesa(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> raw) {
        return delivery.askMpesa(id, raw == null ? null : (String) raw.get("phone"));
    }

    @GetMapping("/riders")
    public List<Map<String, Object>> riders() {
        staffOnly();
        return delivery.riders();
    }

    @PostMapping("/riders")
    public Map<String, Object> addRider(@RequestBody Map<String, Object> raw) {
        return delivery.saveRider(null, new Body(raw));
    }

    @PutMapping("/riders/{id}")
    public Map<String, Object> saveRider(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return delivery.saveRider(id, new Body(raw));
    }

    /** {counted, keepPay}. */
    @PostMapping("/riders/{id}/hand-in")
    public Map<String, Object> handIn(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return delivery.handIn(id, new Body(raw));
    }

    /** {how: TILL_CASH | MPESA}. */
    @PostMapping("/riders/{id}/pay")
    public Map<String, Object> pay(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return delivery.pay(id, new Body(raw));
    }

    /** {app, ref, lines}. */
    @PostMapping("/app-orders")
    public Map<String, Object> appOrder(@RequestBody Map<String, Object> raw) {
        return delivery.appOrder(new Body(raw));
    }

    @GetMapping("/areas")
    public List<Map<String, Object>> areas() {
        staffOnly();
        return delivery.areas(false);
    }

    @PostMapping("/areas")
    public Map<String, Object> addArea(@RequestBody Map<String, Object> raw) {
        return delivery.saveArea(null, new Body(raw));
    }

    /** Add the Nairobi starting areas (estimates to change). */
    @PostMapping("/areas/nairobi")
    public Map<String, Object> addNairobiAreas() {
        return delivery.addNairobiAreas();
    }

    @PutMapping("/areas/{id}")
    public Map<String, Object> saveArea(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        return delivery.saveArea(id, new Body(raw));
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        staffOnly();
        return delivery.rulesView(delivery.rules(TenantContext.tenantId()));
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> raw) {
        delivery.saveRules(new Body(raw));
        return settings();
    }

    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return delivery.report(from, to);
    }
}
