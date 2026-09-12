package reasoning.search.api;

import reasoning.common.model.DocumentFileType;
import reasoning.search.model.ChunkType;
import reasoning.search.model.MetadataFilter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Command carrying all data needed to index a single document chunk. */
public record IndexChunkCommand(
        UUID documentId,
        int documentVersion,
        ChunkType chunkType,
        String text,
        Integer pageNumber,
        Integer sectionIndex,
        int chunkIndex,
        Integer startOffset,
        Integer endOffset,
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
    /** Legacy constructor without temporal validity metadata (treated as unknown). */
    public IndexChunkCommand(
            UUID documentId,
            int documentVersion,
            ChunkType chunkType,
            String text,
            Integer pageNumber,
            Integer sectionIndex,
            int chunkIndex,
            Integer startOffset,
            Integer endOffset,
            String title,
            DocumentFileType documentType,
            String category,
            Set<String> tags,
            String source,
            String tenantId,
            Instant documentCreatedAt,
            List<MetadataFilter> attributes,
            String embeddingReference) {
        this(documentId, documentVersion, chunkType, text, pageNumber, sectionIndex, chunkIndex,
                startOffset, endOffset, title, documentType, category, tags, source, tenantId,
                documentCreatedAt, attributes, embeddingReference, null, null, null, null);
    }
}
