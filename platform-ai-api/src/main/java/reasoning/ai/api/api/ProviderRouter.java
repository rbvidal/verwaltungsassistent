package reasoning.ai.api;

import reasoning.ai.model.ModelCapability;
import reasoning.ai.model.InferenceRequest;

import java.util.List;

/**
 * Routes inference requests to the appropriate provider based on model name, capabilities, or fallback order.
 * Business services request capabilities — the router selects the provider.
 */
public interface ProviderRouter {

    /**
     * Selects the best ChatCompletionProvider for the given request.
     * Strategy: model name prefix → capability match → preferred provider → first available.
     */
    ChatCompletionProvider routeChat(InferenceRequest request);

    /**
     * Returns the ordered list of providers that can fulfill the requested capability.
     */
    List<ChatCompletionProvider> resolveProviders(ModelCapability.CapabilityRequest capability);

    /**
     * Returns the list of available model names across all registered providers.
     */
    List<String> listAvailableModels();
}
