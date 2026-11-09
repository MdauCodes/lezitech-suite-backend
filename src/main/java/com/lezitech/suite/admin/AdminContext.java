package com.lezitech.suite.admin;

import com.lezitech.suite.common.ApiException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The signed-in LeziTech staff member for a control-panel request. */
public final class AdminContext {

    public record Admin(UUID id, String role, String name) {
    }

    /**
     * What each staff role may do. SUPERADMIN can do everything including managing the team;
     * the others get the slices their job needs.
     */
    private static final Map<String, Set<String>> PERMISSIONS = Map.of(
            "ADMIN", Set.of("read", "billing", "trials", "suspend", "impersonate", "notes", "modules", "sectors", "demos", "system", "payments", "setup"),
            "SUPPORT", Set.of("read", "trials", "impersonate", "notes", "modules", "payments", "setup"),
            "SALES", Set.of("read", "billing", "trials", "notes", "demos", "setup"),
            "DEVELOPER", Set.of("read", "impersonate", "modules", "sectors", "demos", "system"));

    private static final ThreadLocal<Admin> CURRENT = new ThreadLocal<>();

    private AdminContext() {
    }

    public static void set(Admin a) {
        CURRENT.set(a);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Admin admin() {
        Admin a = CURRENT.get();
        if (a == null) {
            throw ApiException.unauthorized();
        }
        return a;
    }

    public static boolean can(String role, String permission) {
        return "SUPERADMIN".equals(role) || PERMISSIONS.getOrDefault(role, Set.of()).contains(permission);
    }

    public static void require(String permission) {
        Admin a = admin();
        if (!can(a.role(), permission)) {
            throw ApiException.forbidden("Your control-panel role (" + a.role().toLowerCase() + ") can't do this");
        }
    }

    public static Set<String> permissions(String role) {
        if ("SUPERADMIN".equals(role)) {
            return Set.of("read", "billing", "trials", "suspend", "impersonate", "notes", "modules", "sectors", "demos", "system", "team", "payments", "setup");
        }
        return PERMISSIONS.getOrDefault(role, Set.of());
    }
}
