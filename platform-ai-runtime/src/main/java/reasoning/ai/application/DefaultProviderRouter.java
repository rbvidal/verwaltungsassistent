package reasoning.ai.application;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.ModelCapabilityRegistry;
import reasoning.ai.api.ProviderRouter;
import reasoning.ai.model.InferenceRequest;
import reasoning.ai.model.ModelCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Default provider router.
 * Selection strategy: model name prefix → capability match → preferred provider → first available.
 */
@Component
public class DefaultProviderRouter implements ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultProviderRouter.class);

    private final List<ChatCompletionProvider> providers;
    private final ModelCapabilityRegistry capabilityRegistry;

    public DefaultProviderRouter(List<ChatCompletionProvider> providers,
                                  ModelCapabilityRegistry capabilityRegistry) {
        this.providers = providers;
        this.capabilityRegistry = capabilityRegistry;
    }

    @Override
    public ChatCompletionProvider routeChat(InferenceRequest request) {
        String modelHint = request.preferredModel() != null ? request.preferredModel().toLowerCase() : null;

        // Strategy 1: Model name prefix (e.g., "openai:gpt-4o" → openai provider)
        if (modelHint != null && modelHint.contains(":")) {
            String providerPrefix = modelHint.substring(0, modelHint.indexOf(':'));
            for (var p : providers) {
                if (p.providerName().equalsIgnoreCase(providerPrefix) && p.isAvailable()) {
                    log.debug("Routed '{}' to provider '{}' by model prefix", modelHint, p.providerName());
                    return p;
                }
            }
        }

        // Strategy 2: Capability-based selection
        if (request.requiredCapability() != null && request.requiredCapability() != ModelCapability.CapabilityRequest.CHAT) {
            var capableModels = capabilityRegistry.findByCapability(request.requiredCapability());
            for (var capability : capableModels) {
                for (var p : providers) {
                    if (p.providerName().equalsIgnoreCase(capability.provider()) && p.isAvailable()) {
                        log.debug("Routed to provider '{}' for capability {}", p.providerName(), request.requiredCapability());
                        return p;
                    }
                }
            }
        }

        // Strategy 3: Preferred provider match
        if (request.preferredProvider() != null) {
            for (var p : providers) {
                if (p.providerName().equalsIgnoreCase(request.preferredProvider()) && p.isAvailable()) {
                    return p;
                }
            }
        }

        // Strategy 4: First available provider
        for (var p : providers) {
            if (p.isAvailable()) {
                log.debug("Routed to first available provider: {}", p.providerName());
                return p;
            }
        }

        throw new IllegalStateException("No available AI provider found. "
                + "Configure at least one provider (Ollama or OpenAI).");
    }

    @Override
    public List<ChatCompletionProvider> resolveProviders(ModelCapability.CapabilityRequest capability) {
        var capableModels = capabilityRegistry.findByCapability(capability);
        return providers.stream()
                .filter(p -> capableModels.stream().anyMatch(m -> m.provider().equalsIgnoreCase(p.providerName())))
                .filter(ChatCompletionProvider::isAvailable)
                .toList();
    }

    @Override
    public List<String> listAvailableModels() {
        return capabilityRegistry.listAll().stream()
                .map(m -> m.provider() + ":" + m.modelName())
                .toList();
    }
}
