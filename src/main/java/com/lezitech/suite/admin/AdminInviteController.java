package com.lezitech.suite.admin;

import com.lezitech.suite.common.ApiException;
import com.lezitech.suite.common.Body;
import com.lezitech.suite.common.ClientIp;
import com.lezitech.suite.publicapi.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The page a control-panel invite link opens. No sign-in: the long random part of the link is the key. */
@RestController
public class AdminInviteController {

    /** Link opens and password submissions from one address: 20 an hour (a real person needs two). */
    public static final RateLimit PER_CLIENT = new RateLimit(20, 3_600_000);

    private final AdminInvites invites;

    public AdminInviteController(AdminInvites invites) {
        this.invites = invites;
    }

    @GetMapping("/api/public/admin-invite/{token}")
    public Map<String, Object> look(@PathVariable String token, HttpServletRequest req) {
        slow(req);
        return invites.look(token);
    }

    @PostMapping("/api/public/admin-invite/{token}")
    public Map<String, Object> accept(@PathVariable String token, @RequestBody Map<String, Object> raw, HttpServletRequest req) {
        slow(req);
        return invites.accept(token, new Body(raw).str("password", ""));
    }

    /** Asking for a reset link: 5 an hour from one address, and the same answer for every email, team member or not. */
    public static final RateLimit ASKS_PER_CLIENT = new RateLimit(5, 3_600_000);

    /** Reset links for one email: 3 an hour from anywhere, so rotating addresses cannot be used to flood one inbox. */
    public static final RateLimit ASKS_PER_EMAIL = new RateLimit(3, 3_600_000);

    @PostMapping("/api/public/admin-forgot")
    public Map<String, Object> forgot(@RequestBody Map<String, Object> raw, HttpServletRequest req) {
        if (!ASKS_PER_CLIENT.allow(ClientIp.of(req))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Too many tries. Please wait a while and try again.");
        }
        String email = new Body(raw).str("email", "").trim().toLowerCase();
        // Per email, counted before anything looks the person up. Over the limit it is quietly skipped with the same
        // answer, so nobody can tell a team address from any other and nobody can lock a colleague out of asking.
        if (ASKS_PER_EMAIL.allow(email.length() > 254 ? email.substring(0, 254) : email)) {
            invites.requestResetLater(email);
        }
        return Map.of("ok", true);
    }

    private static void slow(HttpServletRequest req) {
        if (!PER_CLIENT.allow(ClientIp.of(req))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Too many tries. Please wait a while and open the link again.");
        }
    }
}
