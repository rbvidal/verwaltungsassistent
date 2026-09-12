package reasoning.ai.api;

import reasoning.ai.model.AiRequest;
import reasoning.ai.model.PromptContext;
import reasoning.ai.model.RetrievalContext;

/**
 * Assembles a {@link PromptContext} from an AI request and retrieval context.
 */
public interface ContextAssembler {
    /**
     * Assembles the full prompt context including objectives, hierarchy, dossier, and system instruction.
     */
    PromptContext assemble(AiRequest request, RetrievalContext retrievalContext);

    /**
     * Assembles with the user's detected language (ISO 639-1, nullable).
     * Implementations without language support may delegate to the 2-arg form.
     */
    default PromptContext assemble(AiRequest request, RetrievalContext retrievalContext,
                                   String userLanguage) {
        return assemble(request, retrievalContext);
    }
}
