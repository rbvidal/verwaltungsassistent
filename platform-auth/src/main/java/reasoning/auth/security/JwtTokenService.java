package reasoning.auth.security;

import reasoning.auth.infrastructure.persistence.UserAccountEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Issues and hashes JWT access tokens and cryptographically random refresh tokens. */
@Service
public class JwtTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;

    public JwtTokenService(JwtEncoder jwtEncoder, AuthProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /** Issues a signed JWT access token for the given user. */
    public IssuedAccessToken issueAccessToken(UserAccountEntity user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getEmail())
                .claim("user_id", user.getId().toString())
                .claim("roles", user.getRoles().stream().map(Enum::name).toList())
                .build();
        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();
        return new IssuedAccessToken(jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue(), expiresAt);
    }

    /** Generates a cryptographically random 64-byte refresh token encoded as URL-safe Base64. */
    public String generateRefreshToken() {
        byte[] bytes = new byte[64];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Produces a SHA-256 hex digest of the refresh token for database storage. */
    public String hashRefreshToken(String refreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** Returns the expiration instant for a newly issued refresh token. */
    public Instant refreshTokenExpiresAt() {
        return Instant.now().plus(properties.getRefreshTokenTtl());
    }

    /** Immutable record holding an issued JWT access token value and its expiration instant. */
    public record IssuedAccessToken(String token, Instant expiresAt) {
    }
}
