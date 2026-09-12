package reasoning.document.model;
import reasoning.common.model.IngestionStatus;

import java.time.Instant;
import java.util.UUID;

/** Domain model for a document ingestion job tracking its lifecycle and failure reason. */
public record DocumentIngestionJob(
        UUID id,
        UUID documentId,
        IngestionStatus status,
        String sourceType,
        String requestedBy,
        String tenantId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        long sequenceNumber
) {
}
