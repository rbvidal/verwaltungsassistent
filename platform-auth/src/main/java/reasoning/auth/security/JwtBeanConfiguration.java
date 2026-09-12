package reasoning.auth.security;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Isolated JWT encoder bean. Kept separate from SecurityConfiguration
 * to prevent OAuth2ResourceServerAutoConfiguration from creating a global
 * BearerTokenAuthenticationFilter that intercepts static assets.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtBeanConfiguration {

    @Bean
    public JwtEncoder jwtEncoder(AuthProperties properties) {
        byte[] secret = properties.getJwtSecret().getBytes();
        if (secret.length < 32) {
            throw new IllegalStateException("platform.auth.jwt-secret must be at least 32 bytes for HS256");
        }
        return new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret, "HmacSHA256")));
    }
}
