package verwaltungsassistent.web.form;

import reasoning.common.model.DocumentFileType;

import java.time.LocalDate;
import java.util.Set;

/**
 * Metadata + temporal validity update for a document. Validity fields are
 * optional; null means "unknown" — never inferred from upload dates.
 * Metadata remains external/admin/source metadata, not LLM output.
 */
public record UpdateDocumentMetadataForm(
        String title,
        DocumentFileType type,
        String category,
        Set<String> tags,
        String visibility,
        LocalDate publishedAt,
        LocalDate validFrom,
        LocalDate validUntil,
        LocalDate supersededAt
) {
}
