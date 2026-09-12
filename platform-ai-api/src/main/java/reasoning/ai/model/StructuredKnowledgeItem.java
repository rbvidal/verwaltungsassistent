package reasoning.ai.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A domain-agnostic structured knowledge item extracted from a document.
 *
 * <p>No domain semantics live here: {@code kind} is an execution shape
 * (TABLE/THRESHOLD/RULE/…), {@code domain} and {@code key} are data that
 * the source document supplies (e.g. the extracted regulation name as
 * {@code key}). The engine evaluates the generic kind — it never knows
 * what a particular regulation means.
 *
 * <p>{@code payloadJson} is kind-typed, e.g. THRESHOLD:
 * {@code {"bounds":[{"min":0,"max":10000,"outcome":"…","requirements":["…"]}]}}
 * or TABLE: {@code {"columns":["grade","step","amount"],"rows":[["EG 9b","1",3528.00]]}}.
 *
 * <p>Provenance (sourceDocumentId/Version/Page/ChunkIndex/Excerpt) is
 * mandatory for execution; temporal dates are explicit and never inferred —
 * null means unknown.
 */
public record StructuredKnowledgeItem(
        UUID id,
        StructuredKnowledgeKind kind,
        String domain,
        String key,
        String payloadJson,
        StructuredKnowledgeStatus status,
        LocalDate effectiveFrom,
        LocalDate effectiveUntil,
        UUID sourceDocumentId,
        int sourceDocumentVersion,
        Integer sourcePage,
        Integer sourceChunkIndex,
        String sourceExcerpt,
        double extractionConfidence,
        String extractedBy,
        Instant createdAt,
        Instant updatedAt,
        UUID supersededById
) {
    public StructuredKnowledgeItem {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (status == null) status = StructuredKnowledgeStatus.CANDIDATE;
        if (sourceDocumentVersion < 0) throw new IllegalArgumentException("sourceDocumentVersion must be >= 0");
        if (extractionConfidence < 0.0 || extractionConfidence > 1.0) {
            throw new IllegalArgumentException("extractionConfidence must be in [0,1]");
        }
        if (effectiveFrom != null && effectiveUntil != null && effectiveFrom.isAfter(effectiveUntil)) {
            throw new IllegalArgumentException("effectiveFrom must not be after effectiveUntil");
        }
    }
}
