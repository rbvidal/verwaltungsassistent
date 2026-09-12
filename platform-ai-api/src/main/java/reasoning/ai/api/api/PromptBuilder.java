package reasoning.ai.api;

import reasoning.ai.model.PromptContext;

/**
 * Builds a full prompt string from a {@link PromptContext}.
 */
public interface PromptBuilder {
    /**
     * Builds a prompt string from the given context.
     */
    String build(PromptContext context);

    /**
     * Builds with the user's detected language (ISO 639-1, nullable).
     * Implementations without language support may delegate to the 1-arg form.
     */
    default String build(PromptContext context, String userLanguage) {
        return build(context);
    }

    /**
     * Returns the prompt template version identifier.
     */
    String templateVersion();
}
