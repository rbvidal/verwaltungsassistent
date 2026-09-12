package reasoning.audit.api;

import java.util.List;

/** Paginated result of audit events with page, size, and total element counts. */
public record AuditEventPage(
        List<AuditEvent> events,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
