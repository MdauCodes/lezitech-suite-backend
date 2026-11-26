package com.lezitech.suite.admin;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.notify.TenantNotices;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Oversight of every business from the control panel. Reading needs "read" (every role); the actions need the role from the plan:
 * money actions and reminders are for sales and admins ("billing"), pausing for admins ("suspend"), notes for the team ("notes"),
 * fixing the owner's sign-in for support and admins ("signin"). Every action needs a short reason and is audited.
 */
@RestController
@RequestMapping("/api/admin")
public class OversightController {

    private final Oversight ov;
    private final OversightActions actions;
    private final TenantNotices notices;
    private final AdminService admin;

    public OversightController(Oversight ov, OversightActions actions, TenantNotices notices, AdminService admin) {
        this.ov = ov;
        this.actions = actions;
        this.notices = notices;
        this.admin = admin;
    }

    private static ResponseEntity<byte[]> csv(String name, String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(("﻿" + body).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- the Workspaces list ----------

    @GetMapping("/workspaces")
    public Map<String, Object> workspaces(@RequestParam(required = false) String q, @RequestParam(required = false) String sector,
                                          @RequestParam(defaultValue = "false") boolean demo, @RequestParam(required = false) String filter,
                                          @RequestParam(required = false) Integer limit, @RequestParam(required = false) String before) {
        AdminContext.require("read");
        return ov.workspaces(q, sector, demo, filter, limit, before);
    }

    @GetMapping("/workspaces.csv")
    public ResponseEntity<byte[]> workspacesCsv(@RequestParam(required = false) String q, @RequestParam(required = false) String sector,
                                                @RequestParam(defaultValue = "false") boolean demo, @RequestParam(required = false) String filter) {
        AdminContext.require("read");
        return csv("workspaces.csv", ov.workspacesCsv(q, sector, demo, filter));
    }

    @GetMapping("/money-overview")
    public Map<String, Object> overview() {
        AdminContext.require("read");
        return ov.overview();
    }

    // ---------- one business: read ----------

    @GetMapping("/tenants/{id}/money")
    public Map<String, Object> money(@PathVariable UUID id) {
        AdminContext.require("read");
        return ov.money(id);
    }

    @GetMapping("/tenants/{id}/plan-payments")
    public Map<String, Object> payments(@PathVariable UUID id) {
        AdminContext.require("read");
        return ov.payments(id);
    }

    @GetMapping("/tenants/{id}/plan-payments.csv")
    public ResponseEntity<byte[]> paymentsCsv(@PathVariable UUID id) {
        AdminContext.require("read");
        return csv("payments.csv", ov.paymentsCsv(id));
    }

    @GetMapping("/tenants/{id}/plan-payments/{requestId}/receipt")
    public Map<String, Object> receipt(@PathVariable UUID id, @PathVariable UUID requestId) {
        AdminContext.require("read");
        return ov.receipt(id, requestId);
    }

    @GetMapping("/tenants/{id}/plan-requests")
    public Map<String, Object> requests(@PathVariable UUID id) {
        AdminContext.require("read");
        return ov.requests(id);
    }

    @GetMapping("/tenants/{id}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable UUID id) {
        AdminContext.require("read");
        return ov.timeline(id);
    }

    @GetMapping("/tenants/{id}/usage-detail")
    public Map<String, Object> usage(@PathVariable UUID id) {
        AdminContext.require("read");
        return ov.usage(id);
    }

    @GetMapping("/tenants/{id}/notes")
    public List<Map<String, Object>> notes(@PathVariable UUID id) {
        AdminContext.require("read");
        return ov.notes(id);
    }

    @GetMapping("/tenants/{id}/notices")
    public List<Map<String, Object>> noticesSent(@PathVariable UUID id) {
        AdminContext.require("read");
        return ov.noticesSent(id);
    }

    // ---------- one business: act ----------

    @PostMapping("/tenants/{id}/notes")
    public Map<String, Object> addNote(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("notes");
        String note = new Body(raw).str("note", "").trim();
        if (note.length() < 2) {
            throw ApiException.bad("Type the note");
        }
        AdminContext.Admin a = AdminContext.admin();
        ov.addNote(id, a.id(), a.name(), Oversight.clip(note, 4000));
        admin.audit("workspace.note", id, Map.of("note", Oversight.clip(note, 200)));
        return Map.of("ok", true);
    }

    @PostMapping("/tenants/{id}/notices/{noticeId}/whatsapp-sent")
    public Map<String, Object> whatsappSent(@PathVariable UUID id, @PathVariable UUID noticeId) {
        AdminContext.require("notes");
        notices.whatsappSent(id, noticeId);
        admin.audit("workspace.notice_whatsapp", id, Map.of("notice", noticeId.toString()));
        return Map.of("ok", true);
    }

    @PostMapping("/tenants/{id}/record-payment")
    public Map<String, Object> recordPayment(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("billing");
        AdminContext.Admin a = AdminContext.admin();
        return actions.recordPayment(id, raw, a.id(), a.name());
    }

    @PostMapping("/tenants/{id}/free-days")
    public Map<String, Object> freeDays(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("billing");
        return actions.freeDays(id, raw, AdminContext.admin().name());
    }

    @PostMapping("/tenants/{id}/offers")
    public Map<String, Object> applyOffer(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("billing");
        return actions.applyOffer(id, raw, AdminContext.admin().name());
    }

    @PostMapping("/tenants/{id}/offers/{offerId}/end")
    public Map<String, Object> endOffer(@PathVariable UUID id, @PathVariable UUID offerId, @RequestBody Map<String, Object> raw) {
        AdminContext.require("billing");
        return actions.endOffer(id, offerId, raw, AdminContext.admin().name());
    }

    @GetMapping("/tenants/{id}/plan-change/preview")
    public Map<String, Object> planPreview(@PathVariable UUID id, @RequestParam String plan) {
        AdminContext.require("billing");
        return actions.planPreview(id, plan);
    }

    @PostMapping("/tenants/{id}/plan-change")
    public Map<String, Object> changePlan(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("billing");
        return actions.changePlan(id, raw, AdminContext.admin().name());
    }

    @PostMapping("/tenants/{id}/reminder")
    public Map<String, Object> reminder(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("billing");
        return actions.reminder(id, raw, AdminContext.admin().name());
    }

    @PostMapping("/tenants/{id}/owner-email")
    public Map<String, Object> ownerEmail(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("ownerEmail");
        return actions.changeOwnerEmail(id, raw, AdminContext.admin().name());
    }

    @PostMapping("/tenants/{id}/signin-code")
    public Map<String, Object> signInCode(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        AdminContext.require("signin");
        return actions.signInCode(id, raw);
    }
}
