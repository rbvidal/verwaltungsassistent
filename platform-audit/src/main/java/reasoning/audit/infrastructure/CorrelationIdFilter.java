package reasoning.audit.infrastructure;

import reasoning.audit.api.AuditRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Servlet filter that establishes a correlation/request ID context and populates MDC for every HTTP request. */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = headerOrGenerated(request, CORRELATION_ID_HEADER);
        String requestId = headerOrGenerated(request, REQUEST_ID_HEADER);
        AuditRequestContext.set(new AuditRequestContext(
                correlationId,
                requestId,
                request.getRequestURI(),
                request.getMethod(),
                request.getRemoteAddr()));
        MDC.put("correlationId", correlationId);
        MDC.put("requestId", requestId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuditRequestContext.clear();
            MDC.remove("correlationId");
            MDC.remove("requestId");
        }
    }

    private String headerOrGenerated(HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
