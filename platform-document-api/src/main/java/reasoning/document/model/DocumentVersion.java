package reasoning.document.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable record representing a specific version of a document with file and storage details. */
public record DocumentVersion(
        UUID id,
        int versionNumber,
        String fileName,
        String contentType,
        long sizeBytes,
        String storageProvider,
        String storageKey,
        String checksumSha256,
        String createdBy,
        Instant createdAt
) {
}
