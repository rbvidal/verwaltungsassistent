package reasoning.search.api;

import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchQuery;

import java.util.List;

/**
 * SPI for graph-based retrieval in the search pipeline.
 * Provides entity/concept traversal results from a knowledge graph.
 * Optional — retrieval continues with keyword+vector when unavailable.
 */
public interface GraphSearchProvider {

    /** Returns true if the graph database is connected and queryable. */
    boolean isAvailable();

    /**
     * Traverses the graph for entities and concepts related to the query.
     * Returns retrieval candidates scored by graph proximity.
     */
    List<RetrievalCandidate> search(SearchQuery query);

    /**
     * Returns related document IDs within maxDepth hops from the given document.
     * Used to expand retrieval scope with semantically related documents.
     */
    List<String> findRelatedDocuments(String documentId, int maxDepth);
}
