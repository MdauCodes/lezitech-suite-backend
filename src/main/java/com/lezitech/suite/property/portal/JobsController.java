package com.lezitech.suite.property.portal;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A caretaker's or technician's own repair jobs: only the ones given to them. They mark a job as being fixed or done,
 * write to the tenant, and add photos (e.g. the finished repair). Owners and managers use Rent & tenants instead.
 */
@RestController
@RequestMapping("/api/jobs")
@Transactional
public class JobsController {

    private final Db db;
    private final Maintenance maintenance;

    public JobsController(Db db, Maintenance maintenance) {
        this.db = db;
        this.maintenance = maintenance;
    }

    private void mine(UUID id) {
        TenantContext.Caller c = TenantContext.caller();
        if ("OWNER".equals(c.role()) || "MANAGER".equals(c.role())) {
            return;
        }
        if (db.find("SELECT id FROM maintenance_requests WHERE id = :id AND assigned_to = :u", "id", id, "u", c.userId()).isEmpty()) {
            throw ApiException.notFound("Job");
        }
    }

    @GetMapping
    public List<Map<String, Object>> jobs(@RequestParam(defaultValue = "open") String show) {
        return maintenance.list("m.assigned_to = :u" + ("all".equals(show) ? "" : " AND m.status NOT IN ('DONE','CLOSED','CANCELLED')"),
                "u", TenantContext.userId());
    }

    @GetMapping("/{id}")
    public Map<String, Object> job(@PathVariable UUID id) {
        mine(id);
        Map<String, Object> d = maintenance.detail(id, false);
        d.remove("cost");
        return d;
    }

    @PostMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        mine(id);
        boolean technician = !List.of("OWNER", "MANAGER").contains(TenantContext.caller().role());
        return maintenance.staffUpdate(id, new Body(raw), TenantContext.caller().name(), technician);
    }

    @PostMapping("/{id}/photos")
    public Map<String, Object> photo(@PathVariable UUID id, @RequestParam("file") MultipartFile upload,
                                     @RequestParam(value = "caption", required = false) String caption) throws IOException {
        mine(id);
        return maintenance.addPhoto(id, upload.getBytes(), "STAFF", TenantContext.caller().name(), caption);
    }
}
