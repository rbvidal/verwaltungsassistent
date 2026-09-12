package reasoning.document.api;

import reasoning.document.model.DocumentIngestionJob;

import java.util.UUID;

/** Service for managing the lifecycle of document ingestion jobs. */
public interface DocumentIngestionService {
    /** Creates a new ingestion job for the given document. */
    DocumentIngestionJob createIngestionJob(UUID documentId, String actorId);

    /** Starts a pending ingestion job. */
    DocumentIngestionJob startIngestion(UUID jobId, String actorId);

    /** Completes a running ingestion job. */
    DocumentIngestionJob completeIngestion(UUID jobId, String actorId);

    /** Fails an ingestion job with a reason. */
    DocumentIngestionJob failIngestion(UUID jobId, String actorId, String reason);

    /** Queries ingestion jobs with filtering and pagination. */
    IngestionJobPage findIngestionJobs(IngestionJobFilter filter);
}
