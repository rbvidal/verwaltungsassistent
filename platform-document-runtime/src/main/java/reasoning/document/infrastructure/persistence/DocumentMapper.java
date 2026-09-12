package reasoning.document.infrastructure.persistence;

import reasoning.document.model.Document;
import reasoning.document.model.DocumentIngestionJob;
import reasoning.document.model.DocumentMetadata;
import reasoning.document.model.DocumentVersion;

/** Static mappers for converting JPA entities to domain model records. */
public final class DocumentMapper {

    private DocumentMapper() {
    }

    /** Converts a document entity (with versions) to the domain model. */
    public static Document toModel(DocumentEntity entity) {
        return new Document(
                entity.getId(),
                entity.getTenantId(),
                new DocumentMetadata(
                        entity.getTitle(),
                        entity.getType(),
                        entity.getCategory(),
                        entity.getTags(),
                        entity.getVisibility()),
                entity.getStatus(),
                entity.getCurrentVersion(),
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPublishedAt(),
                entity.getValidFrom(),
                entity.getValidUntil(),
                entity.getSupersededAt(),
                entity.getVersions().stream().map(DocumentMapper::toModel).toList());
    }

    /** Converts a version entity to the domain model. */
    public static DocumentVersion toModel(DocumentVersionEntity entity) {
        return new DocumentVersion(
                entity.getId(),
                entity.getVersionNumber(),
                entity.getFileName(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getStorageProvider(),
                entity.getStorageKey(),
                entity.getChecksumSha256(),
                entity.getCreatedBy(),
                entity.getCreatedAt());
    }

    /** Converts an ingestion job entity to the domain model. */
    public static DocumentIngestionJob toModel(IngestionJobEntity entity) {
        return new DocumentIngestionJob(
                entity.getId(),
                entity.getDocumentId(),
                entity.getStatus(),
                entity.getSourceType(),
                entity.getRequestedBy(),
                entity.getTenantId(),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCompletedAt(),
                entity.getSequenceNumber());
    }
}
