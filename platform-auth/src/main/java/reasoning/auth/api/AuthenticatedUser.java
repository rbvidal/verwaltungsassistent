package reasoning.auth.api;

import java.util.Set;
import java.util.UUID;

/** Immutable representation of an authenticated user with id, email, display name, and roles. */
public record AuthenticatedUser(
        UUID id,
        String email,
        String displayName,
        Set<String> roles
) {
}
