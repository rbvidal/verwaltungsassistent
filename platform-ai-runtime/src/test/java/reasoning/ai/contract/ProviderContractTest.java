package reasoning.ai.contract;

import reasoning.ai.api.ChatCompletionProvider;
import reasoning.ai.model.ModelCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the ChatCompletionProvider SPI.
 * Every provider implementation must satisfy these behavioral requirements.
 *
 * <p>New providers (Anthropic, Gemini, etc.) should extend or reference these
 * tests to ensure consistent provider behavior across the platform.
 */
@DisplayName("ChatCompletionProvider SPI Contract")
class ProviderContractTest {

    /**
     * Base contract that every ChatCompletionProvider must satisfy.
     * Implementations should create a concrete test class that provides their provider instance.
     */
    abstract static class ChatCompletionProviderContract {

        protected abstract ChatCompletionProvider createProvider();

        @Test
        @DisplayName("providerName() returns non-empty string")
        void providerNameReturnsNonEmpty() {
            var provider = createProvider();
            String name = provider.providerName();
            assertNotNull(name, "providerName() must not return null");
            assertFalse(name.isBlank(), "providerName() must not return blank");
        }

        @Test
        @DisplayName("isAvailable() does not throw")
        void isAvailableDoesNotThrow() {
            var provider = createProvider();
            assertDoesNotThrow(() -> provider.isAvailable(),
                    "isAvailable() must not throw exceptions");
        }

        @Test
        @DisplayName("complete() returns non-null for valid prompt")
        void completeReturnsNonNull() {
            var provider = createProvider();
            var result = provider.complete("Hello",
                    new ModelCapabilities("test", "test-model", 4096, true, true, true));
            assertNotNull(result, "complete() must return a non-null string");
        }

        @Test
        @DisplayName("complete() handles empty prompt gracefully")
        void completeHandlesEmptyPrompt() {
            var provider = createProvider();
            var result = provider.complete("",
                    new ModelCapabilities("test", "test-model", 4096, true, true, true));
            assertNotNull(result, "complete() must handle empty prompt gracefully");
        }
    }

    @Nested
    @DisplayName("StubProvider satisfies ChatCompletionProvider contract")
    class StubProviderContract extends ChatCompletionProviderContract {
        @Override
        protected ChatCompletionProvider createProvider() {
            return new ChatCompletionProvider() {
                @Override public String providerName() { return "stub"; }
                @Override public boolean isAvailable() { return true; }
                @Override public String complete(String p, ModelCapabilities c) {
                    return "stub response: " + (p != null ? p.substring(0, Math.min(10, p.length())) : "");
                }
            };
        }
    }
}
