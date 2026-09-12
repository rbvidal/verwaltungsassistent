package reasoning.search.api;

import reasoning.search.model.DocumentChunk;
import reasoning.search.model.SearchFilter;

import java.util.List;
import java.util.UUID;

/** Service for managing the lifecycle of document chunks. */
public interface ChunkManagementService {
    /** Indexes a single chunk from a command. */
    DocumentChunk indexChunk(IndexChunkCommand command);

    /** Retrieves a chunk by its ID. */
    DocumentChunk getChunk(UUID chunkId);

    /** Finds chunks matching the given filter with pagination. */
    List<DocumentChunk> findChunks(SearchFilter filter, int page, int size);

    /** Deletes all chunks for a document, returning the count. */
    int deleteByDocumentId(UUID documentId);
}
