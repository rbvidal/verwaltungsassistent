package reasoning.document.model;
import reasoning.common.model.DocumentStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Immutable domain model representing a document with metadata, status, and version history.
 * <p>Temporal validity (publishedAt/validFrom/validUntil/supersededAt) is distinct from the
 * ingestion timestamps createdAt/updatedAt. Null = unknown — never inferred from upload dates.
 */
public record Document(
        UUID id,
        String tenantId,
        DocumentMetadata metadata,
        DocumentStatus status,
        int currentVersion,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt,
        LocalDate publishedAt,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate supersededAt,
        List<DocumentVersion> versions
) {
    public Document {
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    /** Legacy constructor without temporal validity metadata (treated as unknown). */
    public Document(
            UUID id,
            String tenantId,
            DocumentMetadata metadata,
            DocumentStatus status,
            int currentVersion,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt,
            List<DocumentVersion> versions) {
        this(id, tenantId, metadata, status, currentVersion, createdBy, updatedBy, createdAt,
                updatedAt, null, null, null, null, versions);
    }
}
