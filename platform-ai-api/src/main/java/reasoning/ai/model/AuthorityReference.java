package reasoning.ai.model;

import java.util.UUID;

/**
 * A reference to an authority (legal or domain-specific) with entry number, excerpt, relevance score, and tier.
 */
public record AuthorityReference(
        UUID documentId,
        UUID chunkId,
        String referenceId,
        String domainCode,
        String entryNumber,
        String entryTitle,
        String excerpt,
        String domain,
        String basis,
        double relevanceScore,
        double retrievalScore,
        ReferenceTier tier
) {
    public enum ReferenceTier { PRIMARY, SUPPORTING, BACKGROUND }
}
