package reasoning.search.api;

import reasoning.search.model.DocumentChunk;
import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchQuery;

import java.util.List;
import java.util.UUID;

/** Provider for vector/semantic search with indexing and deletion capabilities. */
public interface VectorSearchProvider {
    /** Searches by vector similarity for the given query. */
    List<RetrievalCandidate> search(SearchQuery query);

    /** Indexes a single chunk with its embedding vector. */
    void index(DocumentChunk chunk, float[] embedding);

    /** Deletes all vectors associated with a document. */
    void deleteByDocument(UUID documentId);

    /** Deletes every vector in the store (administrative purge, incl. orphans). */
    default void deleteAll() {
        throw new UnsupportedOperationException("deleteAll is not supported by this provider");
    }

    /** Indexes a batch of chunks with their corresponding embeddings. */
    default void indexBatch(List<DocumentChunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("Chunks and embeddings must have same size");
        }
        for (int i = 0; i < chunks.size(); i++) {
            index(chunks.get(i), embeddings.get(i));
        }
    }
}
