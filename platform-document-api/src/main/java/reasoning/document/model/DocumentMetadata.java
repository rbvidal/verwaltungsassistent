package reasoning.document.model;
import reasoning.common.model.DocumentFileType;

import java.util.Set;

/** Metadata for a document including title, type, category, tags, and visibility. */
public record DocumentMetadata(
        String title,
        DocumentFileType type,
        String category,
        Set<String> tags,
        String visibility
) {
    public DocumentMetadata {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
