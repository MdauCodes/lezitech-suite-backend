package com.lezitech.suite.team;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.Db;
import com.lezitech.suite.tenant.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Staff accounts. users has no RLS, so every query here filters on :t explicitly. */
@RestController
@RequestMapping("/api/team")
@Transactional
public class TeamController {

    public static final Set<String> ROLES = Set.of("OWNER", "MANAGER", "CASHIER", "WAITER", "KITCHEN", "SALES", "STYLIST", "RECEPTION", "ACCOUNTANT", "TECHNICIAN", "COLLECTOR", "RIDER", "STOREKEEPER");

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.platform.PlanFeatures planFeatures;

    private final Db db;
    private final PasswordEncoder encoder;

    private final com.lezitech.suite.auth.TokenVersions versions;

    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.settings.PlanLimits limits;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.activity.ActivityLog activity;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.auth.LoginCodes codes;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lezitech.suite.notify.Mailer mailer;
    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper json;

    /** The real owner (by job, not by powers given): only they give owner powers or change another owner. */
    private boolean callerIsOwner() {
        return "OWNER".equals(db.value("SELECT role FROM users WHERE id = :u AND tenant_id = :t", String.class));
    }

    // ---------- job titles ----------

    /** The business's job titles (its own, or the starting list for its kind of business) and the access levels. */
    @GetMapping("/jobs")
    public Map<String, Object> jobs() {
        String raw = db.value("SELECT settings->'jobs' FROM tenants WHERE id = :t", String.class);
        String sector = db.value("SELECT sector FROM tenants WHERE id = :t", String.class);
        Object jobs;
        try {
            jobs = raw == null || raw.equals("null") ? JobTitles.presets(sector) : json.readValue(raw, List.class);
        } catch (Exception e) {
            jobs = JobTitles.presets(sector);
        }
        return Map.of("jobs", jobs, "presets", JobTitles.presets(sector));
    }

    /** Save the business's job titles: [{title, role, description}]. Owner only. */
    @PutMapping("/jobs")
    public Map<String, Object> saveJobs(@RequestBody Map<String, Object> raw) throws Exception {
        TenantContext.requireRole("OWNER");
        List<Map<String, Object>> clean = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> j : new Body(raw).list("jobs")) {
            Body b = new Body(j);
            String title = b.str("title", "").trim();
            if (title.isEmpty()) {
                continue;
            }
            if (title.length() > 60) {
                throw ApiException.bad("Keep job titles under 60 letters");
            }
            if (!seen.add(title.toLowerCase())) {
                throw ApiException.bad("Two jobs are called " + title);
            }
            String role = b.str("role", "CASHIER").toUpperCase();
            if (!ROLES.contains(role)) {
                throw ApiException.bad("Choose what " + title + " can do");
            }
            String description = b.str("description", "").trim();
            clean.add(Map.of("title", title, "role", role, "description", description.length() > 300 ? description.substring(0, 300) : description));
        }
        db.exec("UPDATE tenants SET settings = settings || jsonb_build_object('jobs', cast(:j as jsonb)) WHERE id = :t", "j", json.writeValueAsString(clean));
        return jobs();
    }

    // ---------- invites ----------

    /** Email this person a code and a link to set their password (new staff, or a forgotten password). */
    @PostMapping("/{id}/invite")
    public Map<String, Object> invite(@PathVariable UUID id) {
        TenantContext.requireRole("MANAGER");
        Map<String, Object> u = db.one("User", "SELECT id, name, email, role, job_title, active FROM users WHERE id = :id AND tenant_id = :t", "id", id);
        String email = (String) u.get("email");
        if (email == null || email.endsWith(".suite.local")) {
            throw ApiException.bad("Add their email first, then send the code");
        }
        if (!Boolean.TRUE.equals(u.get("active"))) {
            throw ApiException.bad("Switch on Can sign in for them first");
        }
        if ("OWNER".equals(u.get("role")) && !callerIsOwner() && !id.equals(TenantContext.userId())) {
            throw ApiException.forbidden("Only the owner can send codes to an owner");
        }
        Map<String, Object> t = db.one("Business", "SELECT name, slug FROM tenants WHERE id = :t");
        String status = codes.sendInvite(id, email, (String) u.get("name"), (String) t.get("name"), (String) t.get("slug"),
                (String) u.get("jobTitle"), TenantContext.caller().name(), mailer.appUrl());
        return Map.of("ok", true, "emailStatus", status, "email", email,
                "message", "A code and a link to set their password are on the way to " + email + ". The code works for 3 days.");
    }

    public TeamController(Db db, PasswordEncoder encoder, com.lezitech.suite.auth.TokenVersions versions) {
        this.db = db;
        this.encoder = encoder;
        this.versions = versions;
    }

    /** Managers who can approve a price change or discount at the till with their PIN. Names only. */
    @GetMapping("/approvers")
    public List<Map<String, Object>> approvers() {
        return db.list("""
                SELECT id, name, role FROM users WHERE tenant_id = :t AND active AND pin_hash IS NOT NULL
                  AND role IN ('OWNER','MANAGER') ORDER BY role = 'OWNER', name""");
    }

    /** Everyone active, names and jobs only (e.g. to credit a stylist at the till). */
    @GetMapping("/names")
    public List<Map<String, Object>> names() {
        return db.list("SELECT id, name, role, active FROM users WHERE tenant_id = :t AND active ORDER BY name");
    }

    /** People who can take over the till with their PIN (cashier switch). Names only. */
    @GetMapping("/till-users")
    public List<Map<String, Object>> tillUsers() {
        return db.list("""
                SELECT id, name, role FROM users WHERE tenant_id = :t AND active AND pin_hash IS NOT NULL ORDER BY name""");
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return db.list("""
                SELECT u.id, u.email, u.name, u.role, u.job_title, u.owner_powers, u.password_hash IS NOT NULL AS has_password,
                       u.active, u.pin_hash IS NOT NULL AS has_pin, u.commission_rate, u.created_at,
                       u.location_id, l.name AS location_name
                FROM users u LEFT JOIN locations l ON l.id = u.location_id
                WHERE u.tenant_id = :t ORDER BY u.role = 'OWNER' DESC, u.name""");
    }

    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        String role = role(b);
        // Name, job and a till PIN are enough: many staff have no email. An email (optional) lets them reset their own
        // PIN with a 6-digit code; a password (optional) lets them also sign in with email. The owner or a manager can
        // always set a new PIN here.
        String email = b.str("email", "").trim().toLowerCase();
        String password = b.str("password", "");
        boolean hasPin = !b.str("pin", "").isBlank();
        if (b.str("name", "").isBlank()) {
            throw ApiException.bad("Type their name");
        }
        if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.bad("That email doesn't look right");
        }
        if (!password.isEmpty() && email.isEmpty()) {
            throw ApiException.bad("A password needs an email to sign in with. Leave both empty to use the PIN only");
        }
        if (!password.isEmpty() && password.length() < 8) {
            throw ApiException.bad("Use a password of at least 8 characters");
        }
        if ("OWNER".equals(role) && (email.isEmpty() || password.isEmpty())) {
            throw ApiException.bad("An owner signs in with an email and a password; add both");
        }
        if (!hasPin && password.isEmpty() && email.isEmpty()) {
            throw ApiException.bad("Give them a till PIN (4 to 6 numbers), or their email so they get a code to set a password");
        }
        boolean powers = b.bool("ownerPowers", false) && !"OWNER".equals(role);
        if (powers && !callerIsOwner()) {
            throw ApiException.forbidden("Only the owner can give someone owner powers");
        }
        if (powers) {
            planFeatures.require("ownerPowers");
        }
        if (!email.isEmpty() && Boolean.TRUE.equals(db.value("SELECT exists(SELECT 1 FROM users WHERE email = :e)", Boolean.class, "e", email))) {
            throw ApiException.conflict("That email is already in use");
        }
        limits.checkUsers(1);
        UUID id = db.insert("""
                INSERT INTO users (tenant_id, email, name, password_hash, role, pin_hash, job_title, owner_powers)
                VALUES (:t, :email, :name, :hash, :role, :pin, :job, :powers) RETURNING id""",
                "email", email.isEmpty() ? null : email, "name", b.str("name").trim(),
                "hash", password.isEmpty() ? null : encoder.encode(password), "role", role,
                "pin", hasPin ? encoder.encode(pin(b)) : null, "job", jobTitle(b), "powers", powers);
        return db.one("User", "SELECT id, email, name, role, job_title, owner_powers, active, commission_rate FROM users WHERE id = :id AND tenant_id = :t", "id", id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        Map<String, Object> u = db.one("User", "SELECT * FROM users WHERE id = :id AND tenant_id = :t", "id", id);
        // Someone with owner powers runs things, but only the real owner changes an owner's account.
        if ("OWNER".equals(u.get("role")) && !callerIsOwner()) {
            throw ApiException.forbidden("Only the owner can change an owner's account");
        }
        String role = b.has("role") ? role(b) : (String) u.get("role");
        if (b.has("ownerPowers")) {
            boolean powers = b.bool("ownerPowers", false) && !"OWNER".equals(role);
            if (powers) {
                planFeatures.require("ownerPowers");
            }
            if (powers != Boolean.TRUE.equals(u.get("ownerPowers"))) {
                if (!callerIsOwner()) {
                    throw ApiException.forbidden("Only the owner can give or take owner powers");
                }
                db.exec("UPDATE users SET owner_powers = :p WHERE id = :id AND tenant_id = :t", "id", id, "p", powers);
                versions.bump(id, false); // they sign in again with the new powers
                activity.record("STAFF_CHANGE", com.lezitech.suite.activity.ActivityLog.NOTICE,
                        u.get("name") + (powers ? ": can now do owner things (plan, payments, who can do what)" : ": owner powers taken away"),
                        null, null, null, null, java.util.Map.of("userId", id.toString()));
            }
        }
        if (b.has("jobTitle")) {
            db.exec("UPDATE users SET job_title = :j WHERE id = :id AND tenant_id = :t", "id", id, "j", jobTitle(b));
        }
        if (b.has("email")) {
            String email = b.str("email", "").trim().toLowerCase();
            if (!email.isEmpty() && !email.equals(u.get("email"))) {
                if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                    throw ApiException.bad("That email doesn't look right");
                }
                if (Boolean.TRUE.equals(db.value("SELECT exists(SELECT 1 FROM users WHERE email = :e AND id <> :id)", Boolean.class, "e", email, "id", id))) {
                    throw ApiException.conflict("That email is already in use");
                }
                db.exec("UPDATE users SET email = :e, email_confirmed_at = NULL WHERE id = :id AND tenant_id = :t", "id", id, "e", email);
            }
        }
        if ("OWNER".equals(u.get("role")) && !"OWNER".equals(role)
                && db.value("SELECT count(*) FROM users WHERE tenant_id = :t AND role = 'OWNER' AND active", Long.class) <= 1) {
            throw ApiException.bad("A workspace needs at least one owner");
        }
        if (!Boolean.TRUE.equals(u.get("active")) && b.bool("active", true)) {
            limits.checkUsers(1); // switching someone back on counts again
        }
        if (!role.equals(u.get("role")) || !b.bool("active", true) || b.has("password")) {
            // Their old logins stop working at once.
            versions.bump(id, false);
        }
        db.exec("UPDATE users SET name = :name, role = :role, active = :active WHERE id = :id AND tenant_id = :t",
                "id", id, "name", b.str("name", (String) u.get("name")), "role", role, "active", b.bool("active", true));
        boolean nowActive = b.bool("active", true);
        if (!role.equals(u.get("role")) || nowActive != Boolean.TRUE.equals(u.get("active"))) {
            activity.record("STAFF_CHANGE", com.lezitech.suite.activity.ActivityLog.NOTICE,
                    u.get("name") + ": job " + String.valueOf(u.get("role")).toLowerCase() + " to " + role.toLowerCase()
                            + (nowActive == Boolean.TRUE.equals(u.get("active")) ? "" : nowActive ? ", can sign in again" : ", can no longer sign in"),
                    null, null, null, null, java.util.Map.of("userId", id.toString()));
        }
        if (b.has("pin")) {
            db.exec("UPDATE users SET pin_hash = :pin WHERE id = :id AND tenant_id = :t", "id", id, "pin", encoder.encode(pin(b)));
        }
        if (b.has("commissionRate")) {
            // Their commission on the sales and services credited to them; blank clears it.
            db.exec("UPDATE users SET commission_rate = :cr WHERE id = :id AND tenant_id = :t", "id", id,
                    "cr", com.lezitech.suite.catalog.CatalogController.rate(b.num("commissionRate", null)));
        }
        if (b.has("password")) {
            db.exec("UPDATE users SET password_hash = :h WHERE id = :id AND tenant_id = :t", "id", id, "h", encoder.encode(b.str("password")));
        }
        return db.one("User", "SELECT id, email, name, role, job_title, owner_powers, active, commission_rate FROM users WHERE id = :id AND tenant_id = :t", "id", id);
    }

    private static String role(Body b) {
        String role = b.str("role", "CASHIER").toUpperCase();
        if (!ROLES.contains(role)) {
            throw ApiException.bad("Unknown role " + role);
        }
        if ("OWNER".equals(role)) {
            TenantContext.requireRole("OWNER");
        }
        return role;
    }

    private static String jobTitle(Body b) {
        String j = b.str("jobTitle", "").trim();
        return j.isEmpty() ? null : (j.length() > 60 ? j.substring(0, 60) : j);
    }

    private static String pin(Body b) {
        String pin = b.str("pin");
        if (!pin.matches("\\d{4,6}")) {
            throw ApiException.bad("PIN must be 4 to 6 digits");
        }
        return pin;
    }
}
