package reasoning.ai.api;

import reasoning.ai.model.AiRequest;
import reasoning.ai.model.RetrievalContext;
import reasoning.ai.model.StructuredIntent;

/**
 * Performs retrieval-augmented generation by retrieving sources, authorities, hierarchy, and timeline.
 */
public interface RetrievalAugmentationService {
    /**
     * Retrieves the full augmented context for the given AI request.
     */
    RetrievalContext retrieve(AiRequest request);

    /**
     * Retrieves with a pre-parsed structured intent. When the intent carries
     * an authoritative domain, implementations use it instead of re-classifying
     * the question. Default delegates to {@link #retrieve(AiRequest)}.
     */
    default RetrievalContext retrieve(AiRequest request, StructuredIntent intent) {
        return retrieve(request);
    }
}
