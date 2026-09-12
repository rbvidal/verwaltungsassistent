package reasoning.auth.api;

/** Command record carrying the refresh token and client metadata for token refresh. */
public record RefreshTokenCommand(
        String refreshToken,
        String ipAddress,
        String userAgent
) {
}
