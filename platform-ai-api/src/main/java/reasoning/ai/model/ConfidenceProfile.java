package reasoning.ai.model;

/**
 * A multi-dimensional confidence profile with source, semantic, structural, completeness, and overall scores.
 */
public record ConfidenceProfile(
        double sourceConfidence,
        double semanticConfidence,
        double structuralConfidence,
        double completenessConfidence,
        double overallConfidence,
        String explanation
) {
    /**
     * Returns a zero-confidence placeholder profile.
     */
    public static ConfidenceProfile none() {
        return new ConfidenceProfile(0.0, 0.0, 0.0, 0.0, 0.0, "No assessment available");
    }

    /**
     * Returns a profile indicating the evidence was insufficient for a reliable
     * assessment. Semantically distinct from {@link #none()}: an assessment was
     * attempted but the available sources could not support it.
     */
    public static ConfidenceProfile insufficientEvidence() {
        return new ConfidenceProfile(0.0, 0.0, 0.0, 0.0, 0.0,
                "Insufficient evidence: no reliable assessment possible");
    }
}
