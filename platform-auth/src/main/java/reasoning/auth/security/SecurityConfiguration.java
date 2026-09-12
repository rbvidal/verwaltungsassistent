package reasoning.auth.security;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecurityConfiguration.class);

    private final AuthProperties authProperties;

    public SecurityConfiguration(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /** Loud warning when the well-known development JWT secret is still configured. */
    @jakarta.annotation.PostConstruct
    void warnOnDevelopmentSecret() {
        if (AuthProperties.DEV_JWT_SECRET.equals(authProperties.getJwtSecret())) {
            log.warn("*** DEVELOPMENT JWT SECRET in use (platform.auth.jwt-secret) — "
                    + "set the JWT_SECRET environment variable for production ***");
        }
    }

    private static final RequestMatcher API_PATHS = new AntPathRequestMatcher("/api/**");

    private static final String[] PUBLIC_API_PATHS = {
            "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/providers/**"
    };

    /**
     * API filter chain. Processed first. Only matches /api/** paths. JWT bearer tokens.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain apiFilterChain(
            HttpSecurity http,
            AuthProperties properties,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter
    ) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(jwtSecretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256).build();
        return http
                .securityMatcher(API_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_API_PATHS).permitAll()
                        .requestMatchers("/api/admin/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(decoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    /**
     * Web filter chain. Processed second. Handles everything NOT matched by the API chain.
     * Serves the React SPA shell and static assets. Authentication is enforced by JWT API calls.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(new NegatedRequestMatcher(API_PATHS))
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; "
                                        + "script-src 'self' 'unsafe-inline'; "
                                        + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                                        + "font-src 'self' https://fonts.gstatic.com; "
                                        + "img-src 'self' data:; "
                                        + "connect-src 'self'; "
                                        + "frame-ancestors 'none'; "
                                        + "form-action 'self'"))
                        .frameOptions(frame -> frame.deny())
                        .xssProtection(xss -> xss.disable())
                        .contentTypeOptions(ct -> ct.disable()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter gac = new JwtGrantedAuthoritiesConverter();
        gac.setAuthoritiesClaimName("roles");
        gac.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter jac = new JwtAuthenticationConverter();
        jac.setJwtGrantedAuthoritiesConverter(gac);
        jac.setPrincipalClaimName("sub");
        return jac;
    }

    private SecretKey jwtSecretKey(AuthProperties properties) {
        byte[] secret = properties.getJwtSecret().getBytes();
        if (secret.length < 32)
            throw new IllegalStateException("platform.auth.jwt-secret must be at least 32 bytes");
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
