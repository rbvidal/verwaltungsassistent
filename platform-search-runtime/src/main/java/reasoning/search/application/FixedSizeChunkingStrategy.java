package reasoning.search.application;

import reasoning.search.api.ChunkingStrategy;
import reasoning.search.model.DocumentChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Chunks text at fixed character intervals with configurable size and overlap. */
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    private final int maxChunkSize;
    private final int overlap;

    public FixedSizeChunkingStrategy(ChunkingProperties properties) {
        this.maxChunkSize = Math.max(properties.getMaxChunkSize(), 100);
        this.overlap = Math.min(properties.getOverlap(), this.maxChunkSize / 2);
    }

    @Override
    public List<DocumentChunk> chunk(UUID documentId, int documentVersion, String title, String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = 0;
        int step = maxChunkSize - overlap;

        while (start < normalized.length()) {
            int end = Math.min(start + maxChunkSize, normalized.length());
            String slice = normalized.substring(start, end).trim();
            if (!slice.isBlank()) {
                chunks.add(SentenceAwareChunkingStrategy.createChunk(
                        documentId, documentVersion, title, slice, chunkIndex, start, end));
                chunkIndex++;
            }
            if (end >= normalized.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }
}
