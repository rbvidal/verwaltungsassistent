package reasoning.common.model;

/** Lifecycle status of a document (draft, pending, ingesting, ready, failed, archived, obsolete, deleted). */
public enum DocumentStatus {
    DRAFT,
    INGESTION_PENDING,
    INGESTING,
    READY,
    FAILED,
    ARCHIVED,
    /** Marked obsolete by an administrator: no longer an active knowledge source, source file retained. */
    OBSOLETE,
    DELETED
}
