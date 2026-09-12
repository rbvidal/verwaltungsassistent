package reasoning.ai.api;

import reasoning.ai.model.ExtractedConcept;

import java.util.List;

/**
 * Extracts and classifies concepts from a query string.
 */
public interface ConceptExtractionService {
    /**
     * Classifies a query into a list of extracted concepts with confidence scores.
     */
    List<ExtractedConcept> classify(String query);
}
