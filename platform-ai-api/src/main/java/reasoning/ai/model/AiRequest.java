package reasoning.ai.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Platform-agnostic search filter placeholder. Uses an untyped filter bag so
 * this module can operate without a dependency on the search layer.
 *
 * <p>{@code retrievalQuery} separates the text used for evidence retrieval
 * (search query, anchor, coverage) from the full question sent to the LLM.
 * Case analyses use it to search by the case topic (name + description)
 * instead of the instruction boilerplate wrapped around it. Null falls back
 * to {@code question} at use sites.</p>
 */
public record AiRequest(
        String question,
        String model,
        Object searchFilter,
        AiConversationContext context,
        int maxRetrievalResults,
        RetrievalScope retrievalScope,
        UUID workspaceId,
        LocalDate asOf,
        String retrievalQuery
) {
    public AiRequest {
        if (retrievalScope == null) retrievalScope = RetrievalScope.HYBRID;
    }

    /** Legacy constructor without an as-of date (treated as today at use sites). */
    public AiRequest(String question, String model, Object searchFilter,
                     AiConversationContext context, int maxRetrievalResults,
                     RetrievalScope retrievalScope, UUID workspaceId) {
        this(question, model, searchFilter, context, maxRetrievalResults,
                retrievalScope, workspaceId, null, null);
    }

    /** Legacy constructor without a retrieval query (falls back to the question). */
    public AiRequest(String question, String model, Object searchFilter,
                     AiConversationContext context, int maxRetrievalResults,
                     RetrievalScope retrievalScope, UUID workspaceId, LocalDate asOf) {
        this(question, model, searchFilter, context, maxRetrievalResults,
                retrievalScope, workspaceId, asOf, null);
    }

    public AiRequest(String question, String model, Object searchFilter,
                     AiConversationContext context, int maxRetrievalResults) {
        this(question, model, searchFilter, context, maxRetrievalResults,
                RetrievalScope.HYBRID, null, null, null);
    }

    /** Text used for retrieval; falls back to the full question. */
    public String retrievalQueryOrQuestion() {
        return retrievalQuery != null && !retrievalQuery.isBlank()
                ? retrievalQuery : question;
    }
}
