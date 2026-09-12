package reasoning.search.model;

import reasoning.common.model.DocumentFileType;

import java.util.UUID;

/** Lightweight reference to a chunk by document, version, position, and document type. */
public record ChunkReference(
        UUID chunkId,
        UUID documentId,
        int documentVersion,
        String title,
        ChunkPosition position,
        DocumentFileType documentType
) {
    public ChunkReference(UUID chunkId, UUID documentId, int documentVersion, String title, ChunkPosition position) {
        this(chunkId, documentId, documentVersion, title, position, null);
    }
}
