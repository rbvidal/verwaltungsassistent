package reasoning.document.api;

import reasoning.common.model.DocumentFileType;

import java.time.LocalDate;
import java.util.Set;

/** Command to create a new document with file, metadata, and tenant information. */
public record CreateDocumentCommand(
        String title,
        DocumentFileType type,
        String fileName,
        String contentType,
        long sizeBytes,
        String storageProvider,
        String storageKey,
        String checksumSha256,
        String category,
        Set<String> tags,
        String visibility,
        String actorId,
        String tenantId,
        LocalDate publishedAt,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate supersededAt
) {
    /** Legacy constructor without temporal validity metadata (treated as unknown). */
    public CreateDocumentCommand(
            String title,
            DocumentFileType type,
            String fileName,
            String contentType,
            long sizeBytes,
            String storageProvider,
            String storageKey,
            String checksumSha256,
            String category,
            Set<String> tags,
            String visibility,
            String actorId,
            String tenantId) {
        this(title, type, fileName, contentType, sizeBytes, storageProvider, storageKey, checksumSha256,
                category, tags, visibility, actorId, tenantId, null, null, null, null);
    }
}
