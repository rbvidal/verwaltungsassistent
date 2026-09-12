package reasoning.search.api;

import java.util.List;

/** Service for extracting structured metadata suggestions from document text. */
public interface MetadataExtractionService {
    /** Extracts metadata from document text and file name, returning a suggestion with confidence. */
    MetadataSuggestion extractMetadata(String text, String fileName);

    /** Suggested metadata including title, document type, domain, category, tags, date, and confidence. */
    record MetadataSuggestion(
            String suggestedTitle,
            String documentType,
            String domain,
            String category,
            List<String> tags,
            String date,
            double confidence
    ) {
    }
}
