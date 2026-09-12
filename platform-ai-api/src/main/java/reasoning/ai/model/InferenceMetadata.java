package reasoning.ai.model;

import java.time.Instant;
import java.util.List;

/**
 * Metadata about an AI inference execution, including provider, model, timing, and retrieval strategy.
 */
public record InferenceMetadata(
        String provider,
        String model,
        Instant requestedAt,
        Instant completedAt,
        String correlationId,
        String requestId,
        String promptTemplateVersion,
        String retrievalStrategy,
        List<String> referencedChunkIds,
        double confidenceScore
) {
    public InferenceMetadata {
        referencedChunkIds = referencedChunkIds == null ? List.of() : List.copyOf(referencedChunkIds);
    }
}
