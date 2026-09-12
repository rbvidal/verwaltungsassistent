package reasoning.ai.api;

import reasoning.ai.model.PromptTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Registry of versioned prompt templates.
 * Enables prompt reproducibility, auditing, and regression testing.
 */
public interface PromptRegistry {

    /** Returns a prompt by its qualified ID (e.g., "rag-answer/v2"). */
    Optional<PromptTemplate> get(String qualifiedId);

    /** Returns the latest version of a prompt by its base ID. */
    Optional<PromptTemplate> getLatest(String promptId);

    /** Lists all registered prompt IDs. */
    List<String> listPromptIds();

    /** Returns all versions of a prompt. */
    List<PromptTemplate> getVersions(String promptId);

    /** Registers or updates a prompt template. */
    void register(PromptTemplate template);
}
