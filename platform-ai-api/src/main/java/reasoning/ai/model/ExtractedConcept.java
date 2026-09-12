package reasoning.ai.model;

import java.util.List;

/**
 * A concept extracted from a query, with a label, confidence score, governing references, and related concepts.
 */
public record ExtractedConcept(
        String concept,
        String label,
        double confidence,
        List<String> governingReferences,
        List<String> relatedConcepts
) {
    public ExtractedConcept {
        governingReferences = governingReferences == null ? List.of() : List.copyOf(governingReferences);
        relatedConcepts = relatedConcepts == null ? List.of() : List.copyOf(relatedConcepts);
    }
}
