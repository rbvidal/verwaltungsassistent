package reasoning.ai.model;

/**
 * A centrality score for a reference paragraph, indicating its importance tier within the semantic context.
 */
public record SemanticCentralityScore(
        String paragraph,
        double centralityScore,
        SemanticTier tier,
        String rationale
) {
    public enum SemanticTier { CORE, SUPPORTING, PERIPHERAL, IRRELEVANT }
}
