package reasoning.ai.application;

import reasoning.ai.api.ModelProvider;
import reasoning.ai.config.AiProviderProperties;
import reasoning.ai.model.ModelCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DefaultModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelProvider.class);

    private final AiProviderProperties properties;

    public DefaultModelProvider(AiProviderProperties properties) {
        this.properties = properties;
    }

    @Override
    public ModelCapabilities capabilities(String requestedModel) {
        String model;
        String provider;

        if (requestedModel != null && !requestedModel.isBlank()) {
            model = requestedModel.trim();
            // Only split on ":" when it's a known provider prefix (e.g. "openai:gpt-4o").
            // Ollama model tags like "qwen2.5-coder:14b-instruct-q8_0" use ":" as a
            // version/tag delimiter, not a provider prefix.
            int colonIdx = model.indexOf(':');
            if (colonIdx > 0) {
                String prefix = model.substring(0, colonIdx);
                if ("openai".equalsIgnoreCase(prefix) || "ollama".equalsIgnoreCase(prefix)) {
                    provider = prefix.toLowerCase();
                    model = model.substring(colonIdx + 1);
                } else {
                    provider = "ollama";
                }
            } else {
                provider = "ollama";
            }
        } else {
            // Fall back to the configured Ollama chat model
            model = properties.getOllama().getChatModel();
            provider = "ollama";
            log.info("No model specified in request, falling back to configured default: ollama/{}", model);
        }

        return new ModelCapabilities(provider, model, 8192, true, true, true);
    }
}
