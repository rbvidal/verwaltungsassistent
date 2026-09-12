package reasoning.search.api;

import reasoning.search.model.DocumentChunk;
import reasoning.search.model.SearchFilter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repository SPI for persisting and querying document chunks. */
public interface ChunkRepository {
    /** Persists a chunk and returns it. */
    DocumentChunk save(DocumentChunk chunk);

    /** Finds a chunk by its UUID. */
    Optional<DocumentChunk> findById(UUID chunkId);

    /** Finds chunks matching the given filter with pagination. */
    List<DocumentChunk> find(SearchFilter filter, int page, int size);

    /** Deletes all chunks for a document, returning the count. */
    int deleteByDocumentId(UUID documentId);
}
