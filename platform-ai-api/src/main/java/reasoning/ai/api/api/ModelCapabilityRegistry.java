package reasoning.ai.api;

import reasoning.ai.model.ModelCapability;

import java.util.List;
import java.util.Optional;

/**
 * Registry of known AI model capabilities.
 * Enables the orchestration layer to query what models can do before selecting one.
 */
public interface ModelCapabilityRegistry {

    /** Returns the capability descriptor for a given model name. */
    Optional<ModelCapability> get(String modelName);

    /** Returns all models registered with the given provider. */
    List<ModelCapability> findByProvider(String provider);

    /** Returns models that satisfy the given capability request. */
    List<ModelCapability> findByCapability(ModelCapability.CapabilityRequest capability);

    /** Returns models that satisfy the capability and are from the given provider. */
    List<ModelCapability> findByProviderAndCapability(String provider, ModelCapability.CapabilityRequest capability);

    /** Returns all registered models. */
    List<ModelCapability> listAll();
}
