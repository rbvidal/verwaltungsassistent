package reasoning.auth.api;

/** Command record carrying the refresh token and actor identity for logout. */
public record LogoutCommand(
        String refreshToken,
        String actorId
) {
}
