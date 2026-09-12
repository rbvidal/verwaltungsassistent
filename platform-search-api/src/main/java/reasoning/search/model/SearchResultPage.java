package reasoning.search.model;

import java.util.List;

/** Paginated search result page including the retrieval strategy name. */
public record SearchResultPage(
        List<SearchResult> results,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String retrievalStrategy
) {
}
