package reasoning.search.api;

import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchQuery;

import java.util.List;

/** Provider interface for cross-encoder or LLM-based reranking of retrieval candidates. */
public interface RerankingProvider {
    /** Reranks the given candidates for the specified query. */
    List<RetrievalCandidate> rerank(SearchQuery query, List<RetrievalCandidate> candidates);
}
