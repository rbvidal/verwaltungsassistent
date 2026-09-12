package reasoning.ai.api;

import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.ExtractedConcept;

import java.util.List;

/**
 * Orchestrates authority retrieval by extracting concepts and retrieving matching references.
 */
public interface AuthorityGroundingService {
    /**
     * Grounds a query by extracting concepts and retrieving matching authority references.
     */
    AuthorityGroundingResult ground(String query);

    /**
     * The result of authority grounding, containing extracted concepts and matching references.
     */
    record AuthorityGroundingResult(
            List<ExtractedConcept> concepts,
            List<AuthorityReference> references
    ) {}
}
