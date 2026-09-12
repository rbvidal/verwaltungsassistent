package reasoning.ai.unit.routing;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.api.ModelCapabilityRegistry;
import reasoning.ai.application.DefaultModelCapabilityRegistry;
import reasoning.ai.application.DefaultProviderRouter;
import reasoning.ai.model.InferenceRequest;
import reasoning.ai.model.ModelCapabilities;
import reasoning.ai.model.ModelCapability;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Provider Router")
class ProviderRouterTest {

    private DefaultProviderRouter router;
    private ModelCapabilityRegistry capabilityRegistry;

    private static class FakeProvider implements ChatCompletionProvider {
        private final String name;
        private final boolean available;
        FakeProvider(String name, boolean available) { this.name = name; this.available = available; }
        @Override public String providerName() { return name; }
        @Override public boolean isAvailable() { return available; }
        @Override public String complete(String prompt, ModelCapabilities capabilities) { return name + " response"; }
    }

    @BeforeEach
    void setUp() {
        capabilityRegistry = new DefaultModelCapabilityRegistry();
    }

    @Nested
    @DisplayName("Capability-based routing")
    class CapabilityRouting {

        @Test
        @DisplayName("routes to provider capable of embeddings")
        void routesToEmbeddingCapableProvider() {
            router = new DefaultProviderRouter(
                    List.of(new FakeProvider("ollama", true)),
                    capabilityRegistry);

            var request = new InferenceRequest("prompt", null, null,
                    ModelCapability.CapabilityRequest.EMBEDDING, null, 0, null, "test");
            var provider = router.routeChat(request);
            assertEquals("ollama", provider.providerName());
        }
    }

    @Nested
    @DisplayName("Model-specific routing")
    class ModelRouting {

        @Test
        @DisplayName("routes openai:gpt-4o to openai provider")
        void routesByModelPrefix() {
            var openai = new FakeProvider("openai", true);
            var ollama = new FakeProvider("ollama", true);
            router = new DefaultProviderRouter(List.of(ollama, openai), capabilityRegistry);

            var request = InferenceRequest.forChat("prompt", "openai:gpt-4o");
            var provider = router.routeChat(request);
            assertEquals("openai", provider.providerName());
        }

        @Test
        @DisplayName("routes ollama:qwen2.5 to ollama provider")
        void routesOllamaPrefixToOllama() {
            var openai = new FakeProvider("openai", true);
            var ollama = new FakeProvider("ollama", true);
            router = new DefaultProviderRouter(List.of(ollama, openai), capabilityRegistry);

            var request = InferenceRequest.forChat("prompt", "ollama:qwen2.5:14b");
            var provider = router.routeChat(request);
            assertEquals("ollama", provider.providerName());
        }
    }

    @Nested
    @DisplayName("Preferred provider")
    class PreferredProvider {

        @Test
        @DisplayName("routes to explicitly preferred provider")
        void routesToPreferredProvider() {
            var openai = new FakeProvider("openai", true);
            var ollama = new FakeProvider("ollama", true);
            router = new DefaultProviderRouter(List.of(ollama, openai), capabilityRegistry);

            var request = new InferenceRequest("prompt", null, "openai",
                    ModelCapability.CapabilityRequest.CHAT, null, 0, null, "test");
            var provider = router.routeChat(request);
            assertEquals("openai", provider.providerName());
        }
    }

    @Nested
    @DisplayName("Graceful degradation — fallback")
    class Fallback {

        @Test
        @DisplayName("skips unavailable provider and selects available one")
        void skipsUnavailableProvider() {
            var openai = new FakeProvider("openai", false);
            var ollama = new FakeProvider("ollama", true);
            router = new DefaultProviderRouter(List.of(openai, ollama), capabilityRegistry);

            var request = InferenceRequest.forChat("prompt", null);
            var provider = router.routeChat(request);
            assertEquals("ollama", provider.providerName());
        }

        @Test
        @DisplayName("throws when no providers available")
        void throwsWhenNoProvidersAvailable() {
            var openai = new FakeProvider("openai", false);
            var ollama = new FakeProvider("ollama", false);
            router = new DefaultProviderRouter(List.of(openai, ollama), capabilityRegistry);

            var request = InferenceRequest.forChat("prompt", null);
            assertThrows(IllegalStateException.class, () -> router.routeChat(request));
        }

        @Test
        @DisplayName("preferred provider skipped when unavailable")
        void skipsUnavailablePreferredProvider() {
            var openai = new FakeProvider("openai", false);
            var ollama = new FakeProvider("ollama", true);
            router = new DefaultProviderRouter(List.of(openai, ollama), capabilityRegistry);

            var request = new InferenceRequest("prompt", null, "openai",
                    ModelCapability.CapabilityRequest.CHAT, null, 0, null, "test");
            var provider = router.routeChat(request);
            assertEquals("ollama", provider.providerName());
        }
    }

    @Nested
    @DisplayName("Model listing")
    class ModelListing {

        @Test
        @DisplayName("lists available models across all providers")
        void listsAvailableModels() {
            router = new DefaultProviderRouter(List.of(
                    new FakeProvider("ollama", true)), capabilityRegistry);
            var models = router.listAvailableModels();
            assertFalse(models.isEmpty());
            assertTrue(models.stream().anyMatch(m -> m.startsWith("ollama:")));
            assertTrue(models.stream().anyMatch(m -> m.contains("openai:")));
        }
    }

    @Nested
    @DisplayName("Provider resolution")
    class ProviderResolution {

        @Test
        @DisplayName("resolves providers for a capability")
        void resolvesProvidersForCapability() {
            var openai = new FakeProvider("openai", true);
            var ollama = new FakeProvider("ollama", true);
            router = new DefaultProviderRouter(List.of(ollama, openai), capabilityRegistry);

            var providers = router.resolveProviders(ModelCapability.CapabilityRequest.CHAT);
            assertFalse(providers.isEmpty());
        }
    }
}
