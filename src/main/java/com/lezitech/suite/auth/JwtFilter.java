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
    private final TokenVersions versions;

    public JwtFilter(JwtService jwt, TokenVersions versions) {
        this.jwt = jwt;
        this.versions = versions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            int tv = jwt.versionOf(token);
            jwt.parse(token).filter(c -> versions.valid(c.userId(), false, tv)).ifPresentOrElse(caller -> {
                TenantContext.set(caller);
                var auth = new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + caller.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }, () -> jwt.parseAdmin(token).filter(a -> versions.valid(a.id(), true, tv)).ifPresent(admin -> {
                com.lezitech.suite.admin.AdminContext.set(admin);
                var auth = new UsernamePasswordAuthenticationToken(admin, null,
                        List.of(new SimpleGrantedAuthority("ROLE_PLATFORM")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }));
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            com.lezitech.suite.admin.AdminContext.clear();
        }
    }
}
