package reasoning.search.model;

/** Position of a chunk within a document (page, section, chunk index, character offsets). */
public record ChunkPosition(
        Integer pageNumber,
        Integer sectionIndex,
        int chunkIndex,
        Integer startOffset,
        Integer endOffset
) {
}
