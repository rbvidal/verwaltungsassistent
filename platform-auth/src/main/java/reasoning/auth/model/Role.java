package reasoning.auth.model;

import java.util.Arrays;

/**
 * User role enumeration with ADMIN, ANALYST, and USER levels plus the hidden
 * SUPERADMIN maintenance role (Phase 2D.12): SUPERADMIN accounts are the only
 * ones allowed to run destructive technical maintenance (database restore /
 * delete / demo reset). The role is intentionally NOT offered anywhere in the
 * user administration UI and never listed on the login page.
 */
public enum Role {
    ADMIN,
    ANALYST,
    USER,
    SUPERADMIN;

    /** Parses a role from a case-insensitive name string. */
    public static Role fromName(String value) {
        return Arrays.stream(values())
                .filter(role -> role.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported role: " + value));
    }
}
