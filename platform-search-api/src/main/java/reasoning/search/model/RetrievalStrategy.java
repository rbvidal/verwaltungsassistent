package reasoning.search.model;

/**
 * Defines which retrievers to run for a given query.
 * Selected by the orchestrator based on query intent and available infrastructure.
 */
public enum RetrievalStrategy {
    /** Keyword-only: fast, good for exact matches and metadata queries. */
    KEYWORD_ONLY,
    /** Vector-only: semantic similarity search. Best for conceptual queries. */
    VECTOR_ONLY,
    /** Keyword + vector fusion: balanced hybrid search. */
    HYBRID,
    /** Graph traversal only: entity and concept exploration. */
    GRAPH_ONLY,
    /** Full fusion: keyword + vector + graph. Best for complex analytical queries. */
    HYBRID_GRAPH;

    public SearchMode toSearchMode() {
        return switch (this) {
            case KEYWORD_ONLY -> SearchMode.KEYWORD;
            case VECTOR_ONLY -> SearchMode.SEMANTIC;
            case HYBRID -> SearchMode.HYBRID;
            case GRAPH_ONLY -> SearchMode.GRAPH;
            case HYBRID_GRAPH -> SearchMode.HYBRID_GRAPH;
        };
    }

    /** Selects a retrieval strategy based on query intent and available infrastructure. */
    public static RetrievalStrategy select(QueryIntent intent, boolean graphAvailable) {
        return switch (intent.intent()) {
            case "INDEX_INSPECTION", "CORPUS_DISCOVERY" -> KEYWORD_ONLY;
            case "GENERAL" -> graphAvailable ? HYBRID_GRAPH : HYBRID;
            case "CONTRACT", "FINANCE", "COMPLIANCE" -> HYBRID;
            case "PROCEDURE", "COMMUNICATION" -> graphAvailable ? GRAPH_ONLY : VECTOR_ONLY;
            default -> HYBRID;
        };
    }
}
