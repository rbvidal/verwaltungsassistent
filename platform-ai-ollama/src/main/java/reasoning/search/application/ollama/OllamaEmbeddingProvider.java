package reasoning.search.application.ollama;

import reasoning.search.api.EmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Ollama-based {@link EmbeddingProvider} that calls {@code /api/embeddings} to generate vector embeddings. */
@Component
@ConditionalOnProperty(prefix = "platform.search.embedding.ollama", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private final RestClient restClient;
    private final String model;
    private final int dimension;

    public OllamaEmbeddingProvider(
            @Value("${platform.search.embedding.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${platform.search.embedding.ollama.model:nomic-embed-text}") String model,
            @Value("${platform.search.embedding.ollama.dimension:768}") int dimension) {
        this.model = model;
        this.dimension = dimension;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    @Override
    public float[] embed(String text) {
        Map<String, Object> request = Map.of("model", model, "prompt", text);
        OllamaEmbeddingResponse response = restClient.post()
                .uri("/api/embeddings")
                .body(request)
                .retrieve()
                .body(OllamaEmbeddingResponse.class);
        if (response == null || response.embedding() == null || response.embedding().length == 0) {
            throw new IllegalStateException("Ollama returned empty embedding");
        }
        return response.embedding();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return model;
    }

    private record OllamaEmbeddingResponse(float[] embedding) {
    }
}
