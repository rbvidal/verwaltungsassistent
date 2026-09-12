package reasoning.search.model;

/** Search retrieval mode. */
public enum SearchMode {
    /** Keyword-only: PostgreSQL full-text / LIKE search. */
    KEYWORD,
    /** Semantic-only: vector similarity via Qdrant. */
    SEMANTIC,
    /** Hybrid: keyword + vector fusion. */
    HYBRID,
    /** Graph-only: knowledge graph traversal via Neo4j. */
    GRAPH,
    /** Full hybrid: keyword + vector + graph traversal. */
    HYBRID_GRAPH
}
