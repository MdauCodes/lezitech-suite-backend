package com.lezitech.suite.auth;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Setting a password with a 6-digit code from email: "Forgot password?" on the sign-in page, and the
 * first password of a workspace the LeziTech team handed over. No reset links.
 */
@RestController
public class PasswordController {

    private final LoginCodes codes;
    private final LoginThrottle throttle;
    private final TokenVersions versions;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuthController auth;

    public PasswordController(LoginCodes codes, LoginThrottle throttle, TokenVersions versions, JdbcTemplate jdbc,
                              PasswordEncoder encoder, AuthController auth) {
        this.codes = codes;
        this.throttle = throttle;
        this.versions = versions;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.auth = auth;
    }

    /** Email a code. Always answers the same, so nobody can find out which emails have accounts. */
    @PostMapping("/api/auth/code")
    public Map<String, Object> sendCode(@RequestBody Map<String, Object> raw, HttpServletRequest req) {
        String email = new Body(raw).str("email").trim().toLowerCase();
        throttle.check("code:" + email, req);
        codes.sendResetCode(email);
        return Map.of("ok", true, "message", "If " + email + " has a Lezi Suite account, a 6-digit code is on its way. Check spam too.");
    }

    /** Code + new password: sets the password, signs out other devices, and signs this one in. */
    @PostMapping("/api/auth/reset")
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> reset(@RequestBody Map<String, Object> raw, HttpServletRequest req) {
        Body b = new Body(raw);
        String email = b.str("email").trim().toLowerCase();
        String password = b.str("password");
        if (password.length() < 8) {
            throw ApiException.bad("Use a password of at least 8 characters");
        }
        String account = "reset:" + email;
        throttle.check(account, req);
        UUID user;
        try {
            user = codes.consume(email, b.str("code"));
        } catch (ApiException e) {
            throttle.failed(account, req);
            throw e;
        }
        throttle.succeeded(account);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", encoder.encode(password), user);
        versions.bump(user, false);
        Map<String, Object> u = jdbc.queryForMap("SELECT tenant_id, role, name FROM users WHERE id = ?", user);
        return auth.session(user, (UUID) u.get("tenant_id"), (String) u.get("role"), (String) u.get("name"));
    }
}
