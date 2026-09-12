package reasoning.auth.api;

/** Command record carrying login credentials and client metadata. */
public record LoginCommand(
        String email,
        String password,
        String ipAddress,
        String userAgent
) {
}
