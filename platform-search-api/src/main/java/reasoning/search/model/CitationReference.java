package reasoning.search.model;

import java.time.LocalDate;
import java.util.UUID;

/** Reference pointing to a document/chunk location for citation purposes. */
public record CitationReference(
        UUID documentId,
        UUID chunkId,
        int documentVersion,
        String title,
        Integer pageNumber,
        Integer startOffset,
        Integer endOffset,
        String excerpt,
        LocalDate publishedAt,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate supersededAt
) {
    /** Legacy constructor without temporal validity metadata (treated as unknown). */
    public CitationReference(
            UUID documentId,
            UUID chunkId,
            int documentVersion,
            String title,
            Integer pageNumber,
            Integer startOffset,
            Integer endOffset,
            String excerpt) {
        this(documentId, chunkId, documentVersion, title, pageNumber, startOffset, endOffset,
                excerpt, null, null, null, null);
    }
}
