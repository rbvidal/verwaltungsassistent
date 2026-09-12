package verwaltungsassistent.web.config;

import reasoning.audit.api.AuditRequestContext;
import reasoning.auth.api.AuthFacade;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.api.LoginCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.stream.Collectors;

@Component
public class AuthFacadeAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(AuthFacadeAuthenticationProvider.class);

    private final AuthFacade authFacade;

    /**
     * Application-level client-address logging (IP on login/logout audit and
     * session metadata). Default true = normal operation; the public demo
     * profile explicitly disables it for Datenschutz (external testers).
     */
    private final boolean clientAddressLoggingEnabled;

    public AuthFacadeAuthenticationProvider(AuthFacade authFacade,
                                            @Value("${app.security.client-address-logging.enabled:true}")
                                            boolean clientAddressLoggingEnabled) {
        this.authFacade = authFacade;
        this.clientAddressLoggingEnabled = clientAddressLoggingEnabled;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        String password = authentication.getCredentials().toString();

        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String ipAddress = clientAddressLoggingEnabled ? "unknown" : null;
        String userAgent = "unknown";
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            if (clientAddressLoggingEnabled) {
                ipAddress = request.getRemoteAddr();
            }
            userAgent = request.getHeader("User-Agent");
        }

        LoginCommand command = new LoginCommand(email, password, ipAddress, userAgent);

        // The audit request context is normally set by a filter that runs AFTER the
        // security chain — during form login it is therefore empty. Set it here so
        // that USER_LOGIN audit events record the real client IP and request path.
        AuditRequestContext.set(new AuditRequestContext(null, null, "/login", "POST", ipAddress));
        try {
            authFacade.login(command);
        } catch (reasoning.auth.application.AccountLockedException ex) {
            // valid credentials on a blocked account — the specific message
            // must reach the login page unchanged
            throw ex;
        } catch (BadCredentialsException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.debug("Login failed for {}: {}", email, ex.getMessage());
            throw new BadCredentialsException("Ungültige E-Mail oder Passwort", ex);
        } finally {
            AuditRequestContext.clear();
        }

        AuthenticatedUser user = authFacade.currentUser(email);

        var authorities = user.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toUnmodifiableSet());

        return new UsernamePasswordAuthenticationToken(user, null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
