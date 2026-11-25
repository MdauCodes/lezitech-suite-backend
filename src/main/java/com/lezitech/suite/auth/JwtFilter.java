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

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final JwtService jwt;
    private final TokenVersions versions;
    private final SupportAccess support;
    private final com.lezitech.suite.property.portal.RenterSessions renters;
    private final com.lezitech.suite.admin.AdminActivity adminActivity;
    private final org.springframework.transaction.support.TransactionTemplate readOnlyWork;

    public JwtFilter(JwtService jwt, TokenVersions versions, SupportAccess support, com.lezitech.suite.property.portal.RenterSessions renters,
                     com.lezitech.suite.admin.AdminActivity adminActivity, org.springframework.transaction.PlatformTransactionManager txm) {
        this.adminActivity = adminActivity;
        this.readOnlyWork = new org.springframework.transaction.support.TransactionTemplate(txm);
        this.jwt = jwt;
        this.versions = versions;
        this.support = support;
        this.renters = renters;
    }

    private void runReadOnly(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        try {
            readOnlyWork.executeWithoutResult(status -> {
                try {
                    chain.doFilter(req, res);
                } catch (IOException | ServletException e) {
                    throw new FilterFailure(e);
                } finally {
                    status.setRollbackOnly();
                }
            });
        } catch (FilterFailure f) {
            if (f.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) f.getCause();
        }
    }

    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Exception cause) {
            super(cause);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        UUID grant = null;
        UUID look = null;
        boolean idleOut = false;
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            int tv = jwt.versionOf(token);
            Optional<UUID> sg = jwt.supportGrantOf(token);
            Optional<UUID> sl = jwt.lookOf(token);
            Optional<TenantContext.Caller> workspace = jwt.parse(token).filter(c -> versions.valid(c.userId(), false, tv))
                    // A LeziTech support sign-in works only while the owner's grant is still in force.
                    .filter(c -> sg.isEmpty() || support.stillActive(sg.get(), c.tenantId()))
                    // A look-only opening works only while it is open (not ended, not past 2 hours, person still on the team).
                    .filter(c -> sl.isEmpty() || support.lookActive(sl.get(), c.tenantId()));
            if (workspace.isPresent()) {
                TenantContext.Caller caller = workspace.get();
                TenantContext.set(caller);
                grant = sg.orElse(null);
                look = sl.orElse(null);
                if (grant != null) {
                    SupportAccess.set(grant);
                } else if (look != null) {
                    SupportAccess.set(look);
                    SupportAccess.setLookOnly(true);
                }
                var auth = new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + caller.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else if (sg.isEmpty() && sl.isEmpty() && req.getRequestURI().startsWith("/api/portal/")) {
                // A landlord's tenant, in their portal. Their token works nowhere else (it isn't a staff token).
                jwt.parseRenter(token).filter(r -> renters.valid(r.accountId(), r.tenantId(), r.tokenVersion())).ifPresent(r -> {
                    TenantContext.Caller caller = new TenantContext.Caller(r.accountId(), r.tenantId(), "RENTER", r.name());
                    TenantContext.set(caller);
                    var auth = new UsernamePasswordAuthenticationToken(caller, null, List.of(new SimpleGrantedAuthority("ROLE_RENTER")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            } else if (sg.isEmpty() && sl.isEmpty()) {
                Optional<com.lezitech.suite.admin.AdminContext.Admin> found = jwt.parseAdmin(token).filter(a -> versions.valid(a.id(), true, tv));
                if (found.isPresent() && !adminActivity.touch(found.get().id())) {
                    idleOut = true;
                    found = Optional.empty();
                }
                found.ifPresent(admin -> {
                    com.lezitech.suite.admin.AdminContext.set(admin);
                    var auth = new UsernamePasswordAuthenticationToken(admin, null,
                            List.of(new SimpleGrantedAuthority("ROLE_PLATFORM")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        try {
            if (idleOut) {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"" + com.lezitech.suite.admin.AdminActivity.IDLE_CODE + "\",\"message\":\""
                        + com.lezitech.suite.admin.AdminActivity.IDLE_MESSAGE + "\"}");
                return;
            }
            // A LeziTech support sign-in must never renew itself. Renewal hands back a plain workspace token with
            // no grant on it, so the session would outlive the owner's 5 hours and its changes would stop being
            // recorded as support in the business's activity log. A grant is shorter than a token's life, so a real
            // support session never needs a renewal: ask the owner for access again instead. Enforced here, with the
            // grant's other checks, rather than in the endpoint. The return still runs the clean-up below.
            if ((grant != null || look != null) && "POST".equals(req.getMethod()) && "/api/auth/refresh".equals(req.getRequestURI())) {
                res.setStatus(403);
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"SUPPORT_NO_REFRESH\",\"message\":"
                        + "\"A LeziTech support sign-in can't be renewed. Ask the owner to give access again.\"}");
                return;
            }
            // Look only: the server refuses every change, whatever the screen shows. Anything but a plain read is stopped
            // here; a read that tried to write anyway is stopped by the read-only database transaction.
            if (look != null && !READS.contains(req.getMethod())) {
                res.setStatus(403);
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"LOOK_ONLY\",\"message\":"
                        + "\"You are only looking at this business, so nothing can be changed. Ask the owner to give help access if you need to change something.\"}");
                return;
            }
            if (look != null) {
                // Look only: the whole request runs inside one read-only transaction that is never committed. Every
                // database call on this thread (also the ones outside any @Transactional) joins it, so no write can commit.
                runReadOnly(req, res, chain);
            } else {
                chain.doFilter(req, res);
            }
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
