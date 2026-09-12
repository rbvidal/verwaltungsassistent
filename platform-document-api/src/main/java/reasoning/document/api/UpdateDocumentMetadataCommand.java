package reasoning.document.api;

import reasoning.common.model.DocumentFileType;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Command to update an existing document's metadata (title, type, category, tags, visibility) and temporal validity. */
public record UpdateDocumentMetadataCommand(
        UUID documentId,
        String title,
        DocumentFileType type,
        String category,
        Set<String> tags,
        String visibility,
        String actorId,
        LocalDate publishedAt,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate supersededAt
) {
    /** Legacy constructor without temporal validity metadata (treated as unknown). */
    public UpdateDocumentMetadataCommand(
            UUID documentId,
            String title,
            DocumentFileType type,
            String category,
            Set<String> tags,
            String visibility,
            String actorId) {
        this(documentId, title, type, category, tags, visibility, actorId, null, null, null, null);
    }
}
