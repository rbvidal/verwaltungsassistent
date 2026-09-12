package reasoning.document.api;

import java.util.UUID;

/** Command to add a new version to an existing document. */
public record AddDocumentVersionCommand(
        UUID documentId,
        String fileName,
        String contentType,
        long sizeBytes,
        String storageProvider,
        String storageKey,
        String checksumSha256,
        String actorId
) {
}
