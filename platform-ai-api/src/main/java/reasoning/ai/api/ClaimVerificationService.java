package reasoning.ai.api;

import reasoning.ai.model.ClaimVerification;
import java.util.List;

/**
 * Independently verifies whether a claim is supported by evidence.
 * This is NOT the generating LLM — it is a separate verification step.
 *
 * <p>Returns structured verdicts: ENTAILED, CONTRADICTED, UNKNOWN.
 * Embedding similarity is not sufficient for this task — the verifier
 * must assess the logical relationship between claim and evidence.
 */
public interface ClaimVerificationService {

    /** Verifies a single claim against a single evidence excerpt. */
    ClaimVerification verify(String claim, String evidenceExcerpt);

    /** Verifies a single claim against multiple evidence excerpts in one call. */
    List<ClaimVerification> verifyBatch(String claim, List<String> evidenceExcerpts);

    /**
     * Verifies all claims against all evidence excerpts. Implementations may
     * collapse this into a single LLM call; the default keeps one call per
     * claim (historical behavior).
     */
    default List<List<ClaimVerification>> verifyAllClaims(List<String> claims,
                                                          List<String> evidenceExcerpts) {
        List<List<ClaimVerification>> results = new java.util.ArrayList<>();
        for (String claim : claims) {
            results.add(verifyBatch(claim, evidenceExcerpts));
        }
        return results;
    }

    /**
     * Verifies all claims against all evidence with the original user
     * question available so the verifier can judge relevance and sufficiency.
     * Default delegates to the question-less form.
     */
    default List<List<ClaimVerification>> verifyAllClaims(List<String> claims,
                                                          List<String> evidenceExcerpts,
                                                          String question) {
        return verifyAllClaims(claims, evidenceExcerpts);
    }
}
