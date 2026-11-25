package com.lezitech.suite.attendance;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * Staff attendance. Anyone signed in clocks themselves (and only themselves: no endpoint takes a person's id for
 * clocking). The owner and managers see the board and timesheets and set up branches; the accountant reads the
 * board and timesheets. RoleGuard holds the role rules and the plan check covers the whole path.
 */
@RestController
@RequestMapping("/api/attendance")
@Transactional
public class AttendanceController {

    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return service.me();
    }

    @PostMapping("/clock")
    public Map<String, Object> clock(@RequestBody Map<String, Object> body) {
        return service.clock(body);
    }

    /** My own days (this month unless dates are given). */
    @GetMapping("/mine")
    public Map<String, Object> mine(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate end = to == null ? service.today() : LocalDate.parse(to);
        return service.timesheet(TenantContext.userId(), from == null ? end.withDayOfMonth(1) : LocalDate.parse(from), end);
    }

    @GetMapping("/board")
    public Map<String, Object> board(@RequestParam(required = false) String day, @RequestParam(required = false) UUID branchId) {
        return service.board(day == null ? service.today() : LocalDate.parse(day), branchId);
    }

    @GetMapping("/timesheet")
    public Map<String, Object> timesheet(@RequestParam UUID userId, @RequestParam String from, @RequestParam String to) {
        return service.timesheet(userId, LocalDate.parse(from), LocalDate.parse(to));
    }

    @GetMapping("/timesheets")
    public Map<String, Object> timesheets(@RequestParam String from, @RequestParam String to, @RequestParam(required = false) UUID branchId) {
        return service.timesheets(LocalDate.parse(from), LocalDate.parse(to), branchId);
    }

    // ---- set-up: owner and managers ----

    @GetMapping("/branches")
    public List<Map<String, Object>> branches() {
        boss();
        return service.branches();
    }

    @PutMapping("/branches/{id}/location")
    public Map<String, Object> location(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        boss();
        return service.setLocation(id, body);
    }

    @PutMapping("/branches/{id}/rules")
    public Map<String, Object> rules(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        boss();
        return service.setRules(id, body);
    }

    @PutMapping("/branches/{id}/hours")
    public List<Map<String, Object>> hours(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        boss();
        return service.setHours(id, null, body);
    }

    @GetMapping("/staff/{userId}/hours")
    public List<Map<String, Object>> staffHours(@PathVariable UUID userId) {
        boss();
        return service.hoursList(service.branchOf(userId), userId);
    }

    /** {days: []} replaces this person's own hours; an empty list puts them back on the branch's hours. */
    @PutMapping("/staff/{userId}/hours")
    public List<Map<String, Object>> setStaffHours(@PathVariable UUID userId, @RequestBody Map<String, Object> body) {
        boss();
        return service.setHours(service.branchOf(userId), userId, body);
    }

    @GetMapping("/branches/{id}/code")
    public Map<String, Object> code(@PathVariable UUID id) {
        boss();
        return service.liveCode(id);
    }

    @PostMapping("/branches/{id}/rotate-code")
    public Map<String, Object> rotateCode(@PathVariable UUID id) {
        boss();
        return service.rotateSecret(id);
    }

    @GetMapping("/branches/{id}/printed")
    public Map<String, Object> printed(@PathVariable UUID id) {
        boss();
        return service.printed(id, false);
    }

    @PostMapping("/branches/{id}/rotate-printed")
    public Map<String, Object> rotatePrinted(@PathVariable UUID id) {
        boss();
        return service.printed(id, true);
    }

    @GetMapping("/branches/{id}/screens")
    public List<Map<String, Object>> screens(@PathVariable UUID id) {
        boss();
        return service.kiosks(id);
    }

    @PostMapping("/branches/{id}/screens")
    public Map<String, Object> newScreen(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        boss();
        Object label = body == null ? null : body.get("label");
        return service.newKiosk(id, label == null ? null : label.toString());
    }

    @DeleteMapping("/screens/{id}")
    public Map<String, Object> revokeScreen(@PathVariable UUID id) {
        boss();
        service.revokeKiosk(id);
        return Map.of("ok", true);
    }

    private static void boss() {
        String role = TenantContext.caller().role();
        if (!"OWNER".equals(role) && !"MANAGER".equals(role)) {
            throw ApiException.forbidden("Only the owner or a manager can do this.");
        }
    }
}
