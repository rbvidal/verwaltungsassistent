package reasoning.ai.model;

/** The execution strategy selected by the DecisionRouter. */
public enum DecisionStrategy {
    /** RuleEngine decides. LLM only explains. Retrieval SKIPPED. */
    RULE_ENGINE,
    /** Full hybrid retrieval + GraphRAG + LLM reasoning. */
    HYBRID_RETRIEVAL,
    /** GraphRAG-only traversal. */
    GRAPH_REASONING,
    /** Direct LLM without retrieval (fallback). */
    DIRECT_LLM
}
