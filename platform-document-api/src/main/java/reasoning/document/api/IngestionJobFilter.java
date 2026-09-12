package reasoning.document.api;

import reasoning.common.model.IngestionStatus;

import java.util.UUID;

/** Filter criteria for querying ingestion jobs by document, status, and tenant. */
public record IngestionJobFilter(
        UUID documentId,
        IngestionStatus status,
        String tenantId,
        int page,
        int size
) {
}
