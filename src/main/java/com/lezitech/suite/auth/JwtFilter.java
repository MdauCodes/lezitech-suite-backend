package com.lezitech.suite.auth;

import com.lezitech.suite.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwt.parse(header.substring(7)).ifPresent(caller -> {
                TenantContext.set(caller);
                var auth = new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + caller.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
