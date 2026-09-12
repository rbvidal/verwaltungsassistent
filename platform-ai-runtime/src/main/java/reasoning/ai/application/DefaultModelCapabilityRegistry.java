package reasoning.ai.application;

import reasoning.ai.api.ModelCapabilityRegistry;
import reasoning.ai.model.ModelCapability;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory model capability registry seeded with known Ollama and OpenAI models.
 * In production, capabilities would be discovered dynamically via provider APIs or loaded from config.
 */
@Component
public class DefaultModelCapabilityRegistry implements ModelCapabilityRegistry {

    private final Map<String, ModelCapability> models = new ConcurrentHashMap<>();

    public DefaultModelCapabilityRegistry() {
        registerDefaults();
    }

    @Override
    public Optional<ModelCapability> get(String modelName) {
        ModelCapability exact = models.get(modelName);
        if (exact != null) return Optional.of(exact);
        return models.values().stream()
                .filter(m -> m.modelName().equalsIgnoreCase(modelName))
                .findFirst();
    }

    @Override
    public List<ModelCapability> findByProvider(String provider) {
        return models.values().stream()
                .filter(m -> m.provider().equalsIgnoreCase(provider))
                .toList();
    }

    @Override
    public List<ModelCapability> findByCapability(ModelCapability.CapabilityRequest capability) {
        return models.values().stream()
                .filter(m -> m.supports(capability))
                .toList();
    }

    @Override
    public List<ModelCapability> findByProviderAndCapability(String provider,
                                                              ModelCapability.CapabilityRequest capability) {
        return models.values().stream()
                .filter(m -> m.provider().equalsIgnoreCase(provider) && m.supports(capability))
                .toList();
    }

    @Override
    public List<ModelCapability> listAll() {
        return List.copyOf(models.values());
    }

    /** Registers a model capability descriptor. */
    public void register(ModelCapability capability) {
        models.put(capability.modelName(), capability);
    }

    private void registerDefaults() {
        // Ollama — local models
        register(new ModelCapability("qwen2.5:14b", "ollama",
                false, false, false, false, false, false, false,
                32768, 8192, 500, 0.0, 0.7,
                List.of("general", "rag", "analysis", "summarization"),
                List.of("local", "small", "general-purpose")));
        register(new ModelCapability("qwen2.5:7b", "ollama",
                true, false, false, false, false, false, false,
                32768, 4096, 200, 0.0, 0.7,
                List.of("general", "rag", "chat"),
                List.of("local", "small", "fast")));
        register(new ModelCapability("llama3.2", "ollama",
                true, false, true, true, false, false, false,
                131072, 4096, 800, 0.0, 0.7,
                List.of("general", "rag", "tool-calling", "json"),
                List.of("local", "large-context")));
        register(new ModelCapability("nomic-embed-text", "ollama",
                false, false, false, false, true, false, false,
                8192, 0, 50, 0.0, 1.0,
                List.of("embedding"),
                List.of("local", "embedding")));

        // OpenAI — cloud models
        register(new ModelCapability("gpt-4o", "openai",
                true, true, true, true, false, false, true,
                128000, 16384, 1200, 5.0, 0.7,
                List.of("general", "rag", "vision", "json", "tool-calling", "analysis"),
                List.of("cloud", "multimodal", "enterprise")));
        register(new ModelCapability("gpt-4o-mini", "openai",
                true, true, true, true, false, false, true,
                128000, 16384, 500, 0.15, 0.7,
                List.of("general", "chat", "json", "fast"),
                List.of("cloud", "multimodal", "cost-effective")));
    }
}
