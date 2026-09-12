package reasoning.ai.model;

import java.util.List;

/**
 * A single retrieval plan — one question, one plan, one execution.
 *
 * <p>Domain-specific collections and authorities are supplied by the
 * domain application; the core stores them but does not define them.
 */
public record RetrievalPlan(
        Domain primaryDomain,
        Domain secondaryDomain,
        List<String> eligibleCollections,
        List<String> eligibleAuthorities,
        String retrievalStrategy,
        int maxResults,
        int maxChunksPerDocument
) {
    public boolean isGeneralDomain() {
        return primaryDomain.isGeneral();
    }
}
