package reasoning.document.api;

import reasoning.document.model.DocumentIngestionJob;

import java.util.List;

/** Paginated result of ingestion jobs with page, size, and total element counts. */
public record IngestionJobPage(
        List<DocumentIngestionJob> jobs,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
