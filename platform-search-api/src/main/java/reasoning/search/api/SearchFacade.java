package reasoning.search.api;

import reasoning.search.model.SearchQuery;
import reasoning.search.model.SearchResultPage;

/** Facade for executing search queries and returning paginated results. */
public interface SearchFacade {
    /** Executes a search query and returns a paginated result page. */
    SearchResultPage search(SearchQuery query);
}
