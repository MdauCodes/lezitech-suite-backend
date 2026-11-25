package com.lezitech.suite.notify;

import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Messages from Busynez" for the owner: what LeziTech changed about the business's plan or money. Under /api/billing so
 * it still opens, and "Got it" still works, when the workspace is locked.
 */
@RestController
public class NoticeController {

    private final TenantNotices notices;

    public NoticeController(TenantNotices notices) {
        this.notices = notices;
    }

    /** All messages (newest first), or only the ones not yet acknowledged (unseen=true) for the Home and Billing pages. Only the owner sees them. */
    @GetMapping("/api/billing/notices")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean unseen) {
        if (!"OWNER".equals(TenantContext.caller().role())) {
            return List.of();
        }
        return notices.mine(unseen);
    }

    @PostMapping("/api/billing/notices/{id}/seen")
    @Transactional
    public Map<String, Object> gotIt(@PathVariable UUID id) {
        notices.gotIt(id);
        return Map.of("ok", true);
    }
}
