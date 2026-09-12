package reasoning.ai.unit.registry;

import reasoning.ai.application.DefaultModelCapabilityRegistry;
import reasoning.ai.model.ModelCapability;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Model Capability Registry")
class ModelCapabilityRegistryTest {

    private DefaultModelCapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultModelCapabilityRegistry();
    }

    @Nested
    @DisplayName("Capability lookup")
    class CapabilityLookup {

        @Test
        @DisplayName("finds models supporting embeddings")
        void findsEmbeddingModels() {
            var models = registry.findByCapability(
                    ModelCapability.CapabilityRequest.EMBEDDING);
            assertFalse(models.isEmpty());
            assertTrue(models.stream().anyMatch(m -> m.modelName().equals("nomic-embed-text")));
        }

        @Test
        @DisplayName("finds models supporting JSON mode")
        void findsJsonModels() {
            var models = registry.findByCapability(
                    ModelCapability.CapabilityRequest.JSON_OUTPUT);
            assertFalse(models.isEmpty());
            assertTrue(models.stream().anyMatch(m -> m.modelName().equals("llama3.2")));
        }

        @Test
        @DisplayName("finds models supporting streaming")
        void findsStreamingModels() {
            var models = registry.findByCapability(
                    ModelCapability.CapabilityRequest.STREAMING);
            assertFalse(models.isEmpty());
            assertTrue(models.stream().anyMatch(m -> m.provider().equals("openai")));
        }

        @Test
        @DisplayName("finds models supporting tool calling")
        void findsToolCallingModels() {
            var models = registry.findByCapability(
                    ModelCapability.CapabilityRequest.TOOL_CALLING);
            assertFalse(models.isEmpty());
            assertTrue(models.stream().allMatch(m -> m.supportsToolCalling()));
        }
    }

    @Nested
    @DisplayName("Provider filtering")
    class ProviderFiltering {

        @Test
        @DisplayName("finds models by provider")
        void findsByProvider() {
            var ollamaModels = registry.findByProvider("ollama");
            var openaiModels = registry.findByProvider("openai");

            assertFalse(ollamaModels.isEmpty());
            assertFalse(openaiModels.isEmpty());
            assertTrue(ollamaModels.stream().allMatch(m -> m.provider().equals("ollama")));
            assertTrue(openaiModels.stream().allMatch(m -> m.provider().equals("openai")));
        }

        @Test
        @DisplayName("finds models by provider and capability")
        void findsByProviderAndCapability() {
            var models = registry.findByProviderAndCapability("ollama",
                    ModelCapability.CapabilityRequest.EMBEDDING);
            assertFalse(models.isEmpty());
            assertTrue(models.stream().allMatch(m ->
                    m.provider().equals("ollama") && m.supportsEmbeddings()));
        }
    }

    @Nested
    @DisplayName("Model properties")
    class ModelProperties {

        @Test
        @DisplayName("GPT-4o has vision support")
        void gpt4oHasVision() {
            var model = registry.get("gpt-4o");
            assertTrue(model.isPresent());
            assertTrue(model.get().supportsVision());
        }

        @Test
        @DisplayName("all models have context window defined")
        void allModelsHaveContextWindow() {
            for (var model : registry.listAll()) {
                assertTrue(model.maxContextWindow() > 0,
                        model.modelName() + " should have maxContextWindow > 0");
            }
        }

        @Test
        @DisplayName("returns empty for unknown model")
        void returnsEmptyForUnknownModel() {
            assertTrue(registry.get("nonexistent-model").isEmpty());
        }
    }
}
