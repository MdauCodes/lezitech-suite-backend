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

    private static void slow(HttpServletRequest req) {
        if (!PER_CLIENT.allow(ClientIp.of(req))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SLOW_DOWN", "Too many tries. Please wait a while and open the link again.");
        }
    }
}
