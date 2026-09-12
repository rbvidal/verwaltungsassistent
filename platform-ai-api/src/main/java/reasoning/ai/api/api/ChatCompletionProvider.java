package reasoning.ai.api;

import reasoning.ai.model.ModelCapabilities;

/**
 * SPI for AI chat completion backends (Ollama, OpenAI, Anthropic, etc.).
 * Implementations handle the HTTP details of calling a specific provider's chat API.
 */
public interface ChatCompletionProvider {
    /** Returns a human-readable provider name (e.g. "ollama", "openai"). */
    String providerName();

    /** Returns true if this provider is currently available. */
    boolean isAvailable();

    /** Sends a prompt to the model and returns the generated text. */
    String complete(String prompt, ModelCapabilities capabilities);

    /**
     * Sends a prompt with an explicit sampling temperature. {@code null} keeps
     * the provider default. Implementations that cannot honor the temperature
     * may delegate to the 2-arg form.
     */
    default String complete(String prompt, ModelCapabilities capabilities, Double temperature) {
        return complete(prompt, capabilities);
    }
}
