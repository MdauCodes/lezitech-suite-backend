package com.lezitech.suite.config;

import com.lezitech.suite.common.ClientIp;
import com.lezitech.suite.publicapi.RateLimit;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Signing up is open to anyone, and until now it was the one open door with no limit: sign-in is throttled and
 * setup requests are capped, but a script could create businesses all day, filling the database and burning the
 * month's emails. A few new businesses an hour from one address is plenty for real people.
 * Kept out of the sign-up endpoint itself so the limit is one small thing in one place.
 */
@Component
public class SignupRateLimitFilter extends OncePerRequestFilter {

    private static final String PATH = "/api/auth/signup";

    private final RateLimit perAddress;

    public SignupRateLimitFilter(@Value("${suite.signup.max-per-hour:5}") int maxPerHour) {
        this.perAddress = new RateLimit(maxPerHour, 3_600_000);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(req.getMethod()) && PATH.equals(req.getRequestURI()) && !perAddress.allow(ClientIp.of(req))) {
            refuse(res);
            return;
        }
        chain.doFilter(req, res);
    }

    private static void refuse(HttpServletResponse res) throws IOException {
        res.setStatus(429);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"TOO_MANY\",\"message\":"
                + "\"That is a lot of new businesses from one place in an hour. Please try again later,"
                + " or talk to us and we will set the rest up with you.\"}");
    }
}
