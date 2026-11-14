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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class JwtFilter extends OncePerRequestFilter {

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final JwtService jwt;
    private final TokenVersions versions;
    private final SupportAccess support;
    private final com.lezitech.suite.property.portal.RenterSessions renters;

    public JwtFilter(JwtService jwt, TokenVersions versions, SupportAccess support, com.lezitech.suite.property.portal.RenterSessions renters) {
        this.jwt = jwt;
        this.versions = versions;
        this.support = support;
        this.renters = renters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        UUID grant = null;
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            int tv = jwt.versionOf(token);
            Optional<UUID> sg = jwt.supportGrantOf(token);
            Optional<TenantContext.Caller> workspace = jwt.parse(token).filter(c -> versions.valid(c.userId(), false, tv))
                    // A LeziTech support sign-in works only while the owner's grant is still in force.
                    .filter(c -> sg.isEmpty() || support.stillActive(sg.get(), c.tenantId()));
            if (workspace.isPresent()) {
                TenantContext.Caller caller = workspace.get();
                TenantContext.set(caller);
                grant = sg.orElse(null);
                if (grant != null) {
                    SupportAccess.set(grant);
                }
                var auth = new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + caller.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else if (sg.isEmpty() && req.getRequestURI().startsWith("/api/portal/")) {
                // A landlord's tenant, in their portal. Their token works nowhere else (it isn't a staff token).
                jwt.parseRenter(token).filter(r -> renters.valid(r.accountId(), r.tenantId(), r.tokenVersion())).ifPresent(r -> {
                    TenantContext.Caller caller = new TenantContext.Caller(r.accountId(), r.tenantId(), "RENTER", r.name());
                    TenantContext.set(caller);
                    var auth = new UsernamePasswordAuthenticationToken(caller, null, List.of(new SimpleGrantedAuthority("ROLE_RENTER")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            } else if (sg.isEmpty()) {
                jwt.parseAdmin(token).filter(a -> versions.valid(a.id(), true, tv)).ifPresent(admin -> {
                    com.lezitech.suite.admin.AdminContext.set(admin);
                    var auth = new UsernamePasswordAuthenticationToken(admin, null,
                            List.of(new SimpleGrantedAuthority("ROLE_PLATFORM")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        try {
            chain.doFilter(req, res);
            // Everything LeziTech changes while signed in as the business goes in the business's activity log.
            if (grant != null && WRITES.contains(req.getMethod()) && req.getRequestURI().startsWith("/api/")) {
                support.logChange(req.getMethod(), req.getRequestURI(), res.getStatus());
            }
        } finally {
            TenantContext.clear();
            SupportAccess.clear();
            com.lezitech.suite.admin.AdminContext.clear();
        }
    }
}
