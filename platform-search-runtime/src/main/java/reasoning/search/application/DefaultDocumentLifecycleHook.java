package reasoning.search.application;

import reasoning.search.api.DocumentLifecycleHook;
import reasoning.search.api.IndexingOrchestrationService;
import reasoning.search.api.VectorSearchProvider;
import reasoning.search.infrastructure.persistence.JpaDocumentChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Handles document lifecycle events by deleting search indices on deletion and triggering re-indexing on updates. */
public class DefaultDocumentLifecycleHook implements DocumentLifecycleHook {

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentLifecycleHook.class);

    private final JpaDocumentChunkRepository chunks;
    private final VectorSearchProvider vectorSearchProvider;
    private final IndexingOrchestrationService indexingOrchestrator;

    public DefaultDocumentLifecycleHook(
            JpaDocumentChunkRepository chunks,
            VectorSearchProvider vectorSearchProvider,
            IndexingOrchestrationService indexingOrchestrator) {
        this.chunks = chunks;
        this.vectorSearchProvider = vectorSearchProvider;
        this.indexingOrchestrator = indexingOrchestrator;
    }

    @Override
    @Transactional
    public void onDocumentDeleted(UUID documentId) {
        vectorSearchProvider.deleteByDocument(documentId);
        int deletedChunks = chunks.deleteByDocumentId(documentId);
        log.info("Deleted {} chunks and Qdrant vectors for document {}", deletedChunks, documentId);
    }

    @Override
    public void onDocumentReindexed(UUID documentId) {
        indexingOrchestrator.reindexDocument(documentId);
    }
}
