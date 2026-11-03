package com.lezitech.suite.tenant;

import com.lezitech.suite.common.ApiException;

import java.util.Set;
import java.util.UUID;

/** The signed-in caller for the current request thread. Set by JwtFilter, cleared after. */
public final class TenantContext {

    public record Caller(UUID userId, UUID tenantId, String role, String name) {
    }

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Caller caller) {
        CURRENT.set(caller);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Caller callerOrNull() {
        return CURRENT.get();
    }

    public static Caller caller() {
        Caller c = CURRENT.get();
        if (c == null) {
            throw ApiException.unauthorized();
        }
        return c;
    }

    public static UUID tenantId() {
        return caller().tenantId();
    }

    public static UUID userId() {
        return caller().userId();
    }

    /** Owners and developers pass every role check. */
    public static void requireRole(String... roles) {
        String role = caller().role();
        if ("OWNER".equals(role)) {
            return;
        }
        if (!Set.of(roles).contains(role)) {
            throw ApiException.forbidden("Your role (" + role + ") can't do this.");
        }
    }

    /**
     * Run code as a given tenant, e.g. during signup before a token exists.
     * Must wrap the transaction, not run inside it: the tenant is read when the transaction begins.
     */
    public static <T> T runAs(UUID tenantId, UUID userId, java.util.function.Supplier<T> work) {
        Caller previous = CURRENT.get();
        CURRENT.set(new Caller(userId, tenantId, "OWNER", "system"));
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
