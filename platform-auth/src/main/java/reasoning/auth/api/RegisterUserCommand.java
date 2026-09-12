package reasoning.auth.api;

import java.util.Set;

/** Command record carrying registration details for a new user. */
public record RegisterUserCommand(
        String email,
        String password,
        String displayName,
        Set<String> roles
) {
}
