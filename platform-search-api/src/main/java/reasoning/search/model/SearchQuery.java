package reasoning.search.model;

/** A search query combining text, mode, filter, request context, and pagination. */
public record SearchQuery(
        String query,
        SearchMode mode,
        SearchFilter filter,
        SearchRequestContext context,
        int page,
        int size
) {
}
