package reasoning.audit.api;

import java.util.UUID;

/** Thread-local request context holding correlation ID, request ID, path, and HTTP method. */
public record AuditRequestContext(
        String correlationId,
        String requestId,
        String requestPath,
        String httpMethod,
        String clientIp
) {
    private static final ThreadLocal<AuditRequestContext> CURRENT = new ThreadLocal<>();

    /** Returns the current thread-local request context, or a generated fallback if none is set. */
    public static AuditRequestContext current() {
        AuditRequestContext context = CURRENT.get();
        if (context != null) {
            return context;
        }
        String requestId = UUID.randomUUID().toString();
        return new AuditRequestContext(requestId, requestId, null, null, null);
    }

    /** Stores the given context in the thread-local variable. */
    public static void set(AuditRequestContext context) {
        CURRENT.set(context);
    }

    /** Removes the thread-local request context. */
    public static void clear() {
        CURRENT.remove();
    }
}
