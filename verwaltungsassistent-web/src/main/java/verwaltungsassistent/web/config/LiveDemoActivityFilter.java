package verwaltungsassistent.web.config;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.service.LiveDemoStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Records real user activity for the live-demo banner. Runs inside the
 * security filter chain after authentication and deliberately SKIPS the
 * /live-demo/status polling endpoint: otherwise the banner's own 60-second
 * htmx refresh would keep an otherwise idle user counted as "active"
 * forever (every request refreshes the session activity timestamp).
 */
public class LiveDemoActivityFilter extends OncePerRequestFilter {

    private final LiveDemoStatusService statusService;
    private final boolean enabled;

    public LiveDemoActivityFilter(LiveDemoStatusService statusService,
                                  @Value("${app.live-demo.enabled:false}") boolean enabled) {
        this.statusService = statusService;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || isStatusPath(request.getRequestURI(), request.getContextPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            statusService.touch(user.email());
        }
        chain.doFilter(request, response);
    }

    static boolean isStatusPath(String uri, String contextPath) {
        if (uri == null) {
            return false;
        }
        String path = contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        return "/live-demo/status".equals(path);
    }
}
