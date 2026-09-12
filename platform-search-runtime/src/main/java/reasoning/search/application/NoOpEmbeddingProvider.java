package reasoning.search.application;

import reasoning.search.api.EmbeddingProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * No-op EmbeddingProvider that returns empty vectors.
 * Used when no real embedding provider (Ollama, OpenAI) is configured.
 * Follows the same pattern as {@link NoOpVectorSearchProvider}.
 */
@Component
@ConditionalOnMissingBean(value = EmbeddingProvider.class, ignored = NoOpEmbeddingProvider.class)
public class NoOpEmbeddingProvider implements EmbeddingProvider {

    @Override
    public float[] embed(String text) {
        return new float[0];
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(t -> new float[0]).toList();
    }

    @Override
    public int dimension() {
        return 0;
    }

    @Override
    public String modelName() {
        return "noop";
    }
}
