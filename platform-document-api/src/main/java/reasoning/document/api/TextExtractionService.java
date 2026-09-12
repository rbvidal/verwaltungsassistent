package reasoning.document.api;

import reasoning.common.model.DocumentFileType;
import reasoning.document.model.DocumentVersion;

/** Service for extracting raw text from document versions. */
public interface TextExtractionService {
    /** Extracts the full text from a document version given its type. */
    String extractText(DocumentFileType type, DocumentVersion version);
}
