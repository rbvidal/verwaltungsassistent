package reasoning.ai.api;

import reasoning.ai.model.ModelCapabilities;

/**
 * Resolves model capabilities for a requested model name.
 */
public interface ModelProvider {
    /**
     * Returns the capabilities for the requested model.
     */
    ModelCapabilities capabilities(String requestedModel);
}
