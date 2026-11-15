package com.lezitech.suite.notify;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A staff member turns phone or computer notifications on (or off) for this device. */
@RestController
@RequestMapping("/api/push")
@Transactional
public class PushController {

    private final WebPush push;

    public PushController(WebPush push) {
        this.push = push;
    }

    /** The key to subscribe with (null when notifications can't run here), and how many of my devices have them on. */
    @GetMapping
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("publicKey", push.publicKey());
        out.put("devices", push.devices(TenantContext.userId()));
        return out;
    }

    /** {endpoint, keys: {p256dh, auth}, device} as the browser gives it. */
    @PostMapping("/subscribe")
    public Map<String, Object> subscribe(@RequestBody Map<String, Object> raw) {
        Body b = new Body(raw);
        Body keys = new Body(b.map("keys"));
        String endpoint = b.str("endpoint", "");
        if (!endpoint.startsWith("https://") || !keys.has("p256dh") || !keys.has("auth")) {
            throw ApiException.bad("This browser didn't give a usable notification address");
        }
        String device = b.str("device", null);
        push.subscribe(endpoint, keys.str("p256dh"), keys.str("auth"), device == null ? null : device.substring(0, Math.min(120, device.length())));
        return info();
    }

    @PostMapping("/unsubscribe")
    public Map<String, Object> unsubscribe(@RequestBody Map<String, Object> raw) {
        push.unsubscribe(new Body(raw).str("endpoint", ""));
        return info();
    }

    /** Send myself a test notification, to check this device. */
    @PostMapping("/test")
    public Map<String, Object> test() {
        push.toUser(TenantContext.userId(), "Notifications are on", "This device will show new orders, bookings and deliveries.", "/", "test");
        return Map.of("sent", true, "devices", List.of());
    }
}
