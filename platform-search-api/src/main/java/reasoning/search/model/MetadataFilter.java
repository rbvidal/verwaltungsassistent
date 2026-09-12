package reasoning.search.model;

/** A key-value metadata filter for chunk queries. */
public record MetadataFilter(
        String key,
        String value
) {
}
