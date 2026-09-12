package reasoning.document.api;

import reasoning.common.model.DocumentStatus;
import reasoning.common.model.DocumentFileType;

import java.time.Instant;

/** Filter criteria for querying documents by status, type, category, tag, tenant, and date range. */
public record DocumentFilter(
        DocumentStatus status,
        DocumentFileType type,
        String category,
        String tag,
        String tenantId,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size
) {
}
