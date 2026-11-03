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

    public static final Set<String> ROLES = Set.of("OWNER", "MANAGER", "CASHIER", "WAITER", "KITCHEN", "SALES", "STYLIST");

    private final Db db;
    private final PasswordEncoder encoder;

    public TeamController(Db db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return db.list("""
                SELECT id, email, name, role, active, pin_hash IS NOT NULL AS has_pin, created_at
                FROM users WHERE tenant_id = :t ORDER BY role = 'OWNER' DESC, name""");
    }

    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        String role = role(b);
        String email = b.str("email").toLowerCase();
        if (Boolean.TRUE.equals(db.value("SELECT exists(SELECT 1 FROM users WHERE email = :e)", Boolean.class, "e", email))) {
            throw ApiException.conflict("That email is already in use");
        }
        String password = b.str("password");
        if (password.length() < 8) {
            throw ApiException.bad("Use a password of at least 8 characters");
        }
        UUID id = db.insert("""
                INSERT INTO users (tenant_id, email, name, password_hash, role, pin_hash)
                VALUES (:t, :email, :name, :hash, :role, :pin) RETURNING id""",
                "email", email, "name", b.str("name"), "hash", encoder.encode(password), "role", role,
                "pin", b.has("pin") ? encoder.encode(pin(b)) : null);
        return db.one("User", "SELECT id, email, name, role, active FROM users WHERE id = :id AND tenant_id = :t", "id", id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> raw) {
        TenantContext.requireRole("MANAGER");
        Body b = new Body(raw);
        Map<String, Object> u = db.one("User", "SELECT * FROM users WHERE id = :id AND tenant_id = :t", "id", id);
        String role = b.has("role") ? role(b) : (String) u.get("role");
        if ("OWNER".equals(u.get("role")) && !"OWNER".equals(role)
                && db.value("SELECT count(*) FROM users WHERE tenant_id = :t AND role = 'OWNER' AND active", Long.class) <= 1) {
            throw ApiException.bad("A workspace needs at least one owner");
        }
        db.exec("UPDATE users SET name = :name, role = :role, active = :active WHERE id = :id AND tenant_id = :t",
                "id", id, "name", b.str("name", (String) u.get("name")), "role", role, "active", b.bool("active", true));
        if (b.has("pin")) {
            db.exec("UPDATE users SET pin_hash = :pin WHERE id = :id AND tenant_id = :t", "id", id, "pin", encoder.encode(pin(b)));
        }
        if (b.has("password")) {
            db.exec("UPDATE users SET password_hash = :h WHERE id = :id AND tenant_id = :t", "id", id, "h", encoder.encode(b.str("password")));
        }
        return db.one("User", "SELECT id, email, name, role, active FROM users WHERE id = :id AND tenant_id = :t", "id", id);
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

    private static String pin(Body b) {
        String pin = b.str("pin");
        if (!pin.matches("\\d{4,6}")) {
            throw ApiException.bad("PIN must be 4 to 6 digits");
        }
        return pin;
    }
}
