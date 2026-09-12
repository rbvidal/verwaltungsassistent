package reasoning.search.api;

import java.util.UUID;

/** Service orchestrating full document indexing: text extraction, chunking, embedding, and vector storage. */
public interface IndexingOrchestrationService {
    /** Indexes a document by extracting text, chunking, generating embeddings, and storing vectors. */
    void indexDocument(UUID documentId);

    /** Deletes existing chunks and vectors, then re-indexes the document. */
    void reindexDocument(UUID documentId);

    /** Deletes all vector-indexed chunks for a document. */
    void deleteDocumentChunks(UUID documentId);
}
