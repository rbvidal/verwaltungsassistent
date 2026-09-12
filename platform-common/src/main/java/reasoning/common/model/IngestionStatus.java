package reasoning.common.model;

/** Status of a document ingestion job (pending, running, completed, failed, cancelled). */
public enum IngestionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
