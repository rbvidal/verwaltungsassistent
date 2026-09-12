package verwaltungsassistent.web.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RunPod's public HTTP proxy rewrites the Host header to the pod-internal
 * address (e.g. http://100.x.x.x:port/...). Absolute redirects built from
 * that Host are unreachable for public visitors. Emitting relative Location
 * headers instead lets every browser resolve them against the public URL the
 * visitor is already on — works through any proxy without forwarded-header
 * support. Registered outermost so Spring Security redirects are covered too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RelativeRedirectFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, new HttpServletResponseWrapper(response) {

            @Override
            public void sendRedirect(String location) throws IOException {
                setStatus(HttpServletResponse.SC_FOUND);
                setHeader("Location", toRelative(location));
            }

            @Override
            public void setHeader(String name, String value) {
                if ("Location".equalsIgnoreCase(name)) {
                    value = toRelative(value);
                }
                super.setHeader(name, value);
            }

            @Override
            public void addHeader(String name, String value) {
                if ("Location".equalsIgnoreCase(name)) {
                    value = toRelative(value);
                }
                super.addHeader(name, value);
            }
        });
    }

    private static String toRelative(String location) {
        if (location == null || (!location.startsWith("http://") && !location.startsWith("https://"))) {
            return location;
        }
        try {
            URI uri = new URI(location);
            if (uri.getHost() == null) {
                return location;
            }
            StringBuilder relative = new StringBuilder();
            relative.append(uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    ? "/" : uri.getRawPath());
            if (uri.getRawQuery() != null) {
                relative.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                relative.append('#').append(uri.getRawFragment());
            }
            return relative.toString();
        } catch (URISyntaxException e) {
            return location;
        }
    }
}
