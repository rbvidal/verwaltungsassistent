package reasoning.ai.api;

import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.ExtractedConcept;
import reasoning.ai.model.SemanticCentralityScore;

import java.util.List;

/**
 * Scores and filters authority references by semantic centrality relative to a query.
 */
public interface SemanticCentralityService {
    /**
     * Scores a list of authority references by their semantic centrality to the query.
     */
    List<SemanticCentralityScore> scoreAuthorities(List<AuthorityReference> references, String query);
    /**
     * Filters authority references, keeping only those with core or supporting centrality.
     */
    List<AuthorityReference> filterCentral(List<AuthorityReference> references, String query);

    /**
     * Concept-aware centrality filter. The governing references of the highest-confidence
     * extracted concept are guaranteed to survive filtering regardless of static
     * centrality weights.
     */
    List<AuthorityReference> filterCentral(List<AuthorityReference> references, String query, List<ExtractedConcept> concepts);
}
