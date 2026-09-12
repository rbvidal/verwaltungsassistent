package reasoning.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A single structured evidence item extracted from a retrieved document.
 * Carries the document metadata, the relevant excerpt, and what it supports.
 * <p>Provenance: documentId + documentVersion + chunkId + pageNumber identify
 * the exact source of the evidence (the version/page/chunk chain). Temporal
 * fields (publishedAt/validFrom/validUntil/supersededAt) are null when unknown.
 */
public record EvidenceItem(
        int index,
        UUID documentId,
        UUID chunkId,
        int documentVersion,
        Integer pageNumber,
        String documentTitle,
        String authority,
        String paragraph,       // e.g. "EG 9a Stufe 3" or "§ 6 Abs. 1"
        String excerpt,
        String supports,        // what claim/answer this evidence supports
        double confidence,
        NumericExtraction numericExtraction, // structured numbers if any were found
        LocalDate publishedAt,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate supersededAt
) {
    public EvidenceItem {
        if (index < 1) throw new IllegalArgumentException("Evidence index must be >= 1");
    }

    /** Returns true if this evidence item contains structured numeric data. */
    @JsonIgnore
    public boolean hasNumericData() {
        return numericExtraction != null && !numericExtraction.isEmpty();
    }
}
