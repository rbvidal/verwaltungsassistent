package reasoning.search.model;

import reasoning.common.model.DocumentFileType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Metadata attached to a document chunk including title, type, tags, and embedding reference. */
public record ChunkMetadata(
        String title,
        DocumentFileType documentType,
        String category,
        Set<String> tags,
        String source,
        String tenantId,
        Instant documentCreatedAt,
        List<MetadataFilter> attributes,
        String embeddingReference,
        LocalDate publishedAt,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate supersededAt
) {
    public ChunkMetadata {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    /** Legacy constructor without temporal validity metadata (treated as unknown). */
    public ChunkMetadata(
            String title,
            DocumentFileType documentType,
            String category,
            Set<String> tags,
            String source,
            String tenantId,
            Instant documentCreatedAt,
            List<MetadataFilter> attributes,
            String embeddingReference) {
        this(title, documentType, category, tags, source, tenantId, documentCreatedAt,
                attributes, embeddingReference, null, null, null, null);
    }
}
