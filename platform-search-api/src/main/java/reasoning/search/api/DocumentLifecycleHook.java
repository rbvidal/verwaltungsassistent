package reasoning.search.api;

import java.util.UUID;

/** Hook invoked on document lifecycle events to manage related search indices. */
public interface DocumentLifecycleHook {
    /** Removes chunks and vectors when a document is deleted. */
    void onDocumentDeleted(UUID documentId);

    /** Triggers re-indexing when a document is updated. */
    void onDocumentReindexed(UUID documentId);
}
