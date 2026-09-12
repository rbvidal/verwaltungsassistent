package reasoning.audit.api;
import reasoning.common.audit.AuditEventType;

import java.time.Instant;
import java.util.List;

/** Query record for filtering and paginating audit events with validation on page and size bounds. */
public record AuditQuery(
        AuditEventType eventType,
        String actorId,
        String tenantId,
        String entityType,
        String entityId,
        String sourceModule,
        String correlationId,
        String requestId,
        Instant from,
        Instant to,
        int page,
        int size,
        List<String> ids
) {
    public AuditQuery {
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 200);
        ids = ids == null ? List.of() : List.copyOf(ids);
    }
}
