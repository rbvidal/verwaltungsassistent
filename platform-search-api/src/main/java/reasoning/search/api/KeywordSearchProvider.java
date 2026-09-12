package reasoning.search.api;

import reasoning.search.model.RetrievalCandidate;
import reasoning.search.model.SearchQuery;

import java.util.List;

/** Provider for keyword-based search returning retrieval candidates. */
public interface KeywordSearchProvider {
    /** Searches chunks by keyword/text matching for the given query. */
    List<RetrievalCandidate> search(SearchQuery query);
}
