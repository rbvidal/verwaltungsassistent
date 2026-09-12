package reasoning.search.api;

import reasoning.search.model.DocumentChunk;

import java.util.List;
import java.util.UUID;

/** Strategy for splitting document text into chunks for indexing. */
public interface ChunkingStrategy {
    /** Chunks document text into a list of {@link DocumentChunk} instances. */
    List<DocumentChunk> chunk(UUID documentId, int documentVersion, String title, String text);
}
