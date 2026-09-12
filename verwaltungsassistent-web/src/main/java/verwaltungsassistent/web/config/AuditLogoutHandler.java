package verwaltungsassistent.web.config;

import reasoning.audit.api.AuditMetadata;
import reasoning.audit.api.AuditRequestContext;
import reasoning.audit.api.AuditService;
import reasoning.audit.api.AuditSource;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.audit.AuditEventType;
import reasoning.common.audit.AuditSubject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Records browser logouts as USER_LOGOUT audit events so the administration
 * page can show real "Letzter Logout" data. Uses the existing audit model;
 * the audit request context is set manually because the regular context
 * filter runs after the security chain.
 */
@Component
public class AuditLogoutHandler implements LogoutHandler {

    private final AuditService auditService;

    /** Same flag as {@link AuthFacadeAuthenticationProvider}; see application.yml. */
    private final boolean clientAddressLoggingEnabled;

    public AuditLogoutHandler(AuditService auditService,
                              @Value("${app.security.client-address-logging.enabled:true}")
                              boolean clientAddressLoggingEnabled) {
        this.auditService = auditService;
        this.clientAddressLoggingEnabled = clientAddressLoggingEnabled;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        Object principal = authentication != null ? authentication.getPrincipal() : null;
        if (!(principal instanceof AuthenticatedUser user)) {
            return;
        }
        String clientIp = clientAddressLoggingEnabled ? request.getRemoteAddr() : null;
        AuditRequestContext.set(new AuditRequestContext(
                null, null, "/logout", "POST", clientIp));
        try {
            auditService.emit(
                    AuditEventType.USER_LOGOUT,
                    AuditSubject.of(user.id().toString(), "AUTH_USER", user.id().toString()),
                    AuditSource.of("auth", AuditMetadata.from(Map.of("email", user.email()))));
        } finally {
            AuditRequestContext.clear();
        }
    }
}
