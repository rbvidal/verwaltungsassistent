package reasoning.search.model;

import java.time.Instant;
import java.util.UUID;

/** Represents a single text chunk of a document with position, type, metadata, and timestamps. */
public record DocumentChunk(
        UUID id,
        UUID documentId,
        int documentVersion,
        ChunkType type,
        String text,
        ChunkPosition position,
        ChunkMetadata metadata,
        Instant createdAt,
        Instant updatedAt
) {
}
