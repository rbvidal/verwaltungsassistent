package reasoning.ai.model;

/**
 * Result of independently verifying a claim against evidence.
 * Produced by the ClaimVerificationService, not by the generating LLM.
 */
public record ClaimVerification(
        String claim,
        String evidence,
        Verdict verdict,
        double confidence,
        String reason
) {
    public enum Verdict {
        /** The evidence logically supports/entails the claim. */
        ENTAILED,
        /** The evidence contradicts the claim. */
        CONTRADICTED,
        /** The relationship cannot be determined from the evidence. */
        UNKNOWN
    }
}
