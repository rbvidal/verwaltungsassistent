package verwaltungsassistent.web.config;

import verwaltungsassistent.web.service.SessionInactivityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Registers every authenticated session for inactivity tracking. This filter
 * never updates the activity timestamp itself — only the explicit
 * /session/activity beacon does, so background polling cannot keep an
 * inactive user alive.
 */
@Component
public class SessionTrackingFilter extends OncePerRequestFilter {

    private final SessionInactivityService inactivityService;

    public SessionTrackingFilter(SessionInactivityService inactivityService) {
        this.inactivityService = inactivityService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && request.getSession(false) != null) {
            inactivityService.register(request.getSession(false));
        }
        chain.doFilter(request, response);
    }
}
