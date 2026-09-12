package reasoning.search.api;

import java.util.List;

/** Provider for generating vector embeddings from text. */
public interface EmbeddingProvider {
    /** Generates an embedding vector for a single text. */
    float[] embed(String text);

    /** Generates embedding vectors for a batch of texts. */
    List<float[]> embedBatch(List<String> texts);

    /** Returns the dimensionality of the embedding vectors produced. */
    int dimension();

    /** Returns a human-readable model identifier for embedding reference tracking. */
    String modelName();
}
