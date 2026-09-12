package reasoning.search.application.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for the Ollama embedding provider (base URL, model, chat model, dimension). */
@ConfigurationProperties(prefix = "platform.search.embedding.ollama")
public record OllamaEmbeddingConfig(String baseUrl, String model, String chatModel, int dimension) {
    public OllamaEmbeddingConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:11434";
        }
        if (model == null || model.isBlank()) {
            model = "nomic-embed-text";
        }
        if (chatModel == null || chatModel.isBlank()) {
            chatModel = "qwen2.5:14b";
        }
        if (dimension <= 0) {
            dimension = 768;
        }
    }
}
