package reasoning.auth.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Immutable record holding access/refresh token pair with expiry and user identity. */
public record AuthTokens(
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        UUID userId,
        String email,
        Set<String> roles
) {
}
