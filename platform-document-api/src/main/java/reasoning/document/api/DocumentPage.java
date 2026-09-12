package reasoning.document.api;

import reasoning.document.model.Document;

import java.util.List;

/** Paginated result of documents with page, size, and total element counts. */
public record DocumentPage(
        List<Document> documents,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
