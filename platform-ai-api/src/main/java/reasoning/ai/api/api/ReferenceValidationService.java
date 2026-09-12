package reasoning.ai.api;

import reasoning.ai.model.AuthorityReference;
import reasoning.ai.model.ExtractedConcept;

import java.util.List;

/**
 * Validates and filters authority reference candidates against extracted concepts.
 */
public interface ReferenceValidationService {
    /**
     * Validates a list of candidate authority references, demoting or filtering as needed.
     */
    List<AuthorityReference> validate(List<AuthorityReference> candidates, List<ExtractedConcept> concepts, String query);
}
