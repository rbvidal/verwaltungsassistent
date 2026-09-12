package reasoning.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration properties for auth: JWT issuer, secret, token TTLs, and login protection. */
@ConfigurationProperties(prefix = "platform.auth")
public class AuthProperties {

    /** Well-known development default — must never be used in production. */
    public static final String DEV_JWT_SECRET = "default-dev-jwt-secret-change-in-production";

    private String issuer = "municipal-decision-assistant";
    private String jwtSecret;
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration refreshTokenTtl = Duration.ofDays(30);
    private LoginProtection login = new LoginProtection();

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public LoginProtection getLogin() {
        return login;
    }

    public void setLogin(LoginProtection login) {
        this.login = login;
    }

    /** Brute-force protection settings (prefix {@code platform.auth.login}). */
    public static class LoginProtection {

        private int maxFailures = 5;
        private Duration failureWindow = Duration.ofMinutes(10);
        private Duration lockoutDuration = Duration.ofMinutes(10);

        public int getMaxFailures() {
            return maxFailures;
        }

        public void setMaxFailures(int maxFailures) {
            this.maxFailures = maxFailures;
        }

        public Duration getFailureWindow() {
            return failureWindow;
        }

        public void setFailureWindow(Duration failureWindow) {
            this.failureWindow = failureWindow;
        }

        public Duration getLockoutDuration() {
            return lockoutDuration;
        }

        public void setLockoutDuration(Duration lockoutDuration) {
            this.lockoutDuration = lockoutDuration;
        }
    }
}
