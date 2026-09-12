package reasoning.ai.api;

import reasoning.ai.model.EvidencePackage;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.ai.model.RetrievalContext;

/**
 * Grounds a raw AI answer against retrieved evidence to produce a reasoned answer.
 */
public interface GroundingService {
    /**
     * Grounds a raw answer by reattributing sources, computing confidence, and producing a {@link ReasonedAnswer}.
     */
    ReasonedAnswer ground(String rawAnswer, RetrievalContext retrievalContext);

    /**
     * Grounds a raw answer against the evidence actually SELECTED for the
     * answer (the {@link EvidencePackage}), not the full retrieval candidate
     * list. Candidates that were ranked but not selected must not influence
     * the verification verdict — retrieval candidates and presented evidence
     * are distinct. Implementations that do not consume the package delegate
     * to the 2-arg form.
     */
    default ReasonedAnswer ground(String rawAnswer, RetrievalContext retrievalContext,
                                  EvidencePackage evidencePackage) {
        return ground(rawAnswer, retrievalContext);
    }
}
