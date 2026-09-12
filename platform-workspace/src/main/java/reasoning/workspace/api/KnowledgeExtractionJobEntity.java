package reasoning.workspace.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable knowledge-extraction job for one document version. Follows the
 * ingestion-job lifecycle (PENDING → RUNNING → COMPLETED | FAILED) and
 * survives restarts; the worker reconciles and retries.
 */
@Entity
@Table(name = "knowledge_extraction_jobs")
public class KnowledgeExtractionJobEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "document_version", nullable = false)
    private int documentVersion;

    /** PENDING | RUNNING | COMPLETED | FAILED */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected KnowledgeExtractionJobEntity() {
    }

    public KnowledgeExtractionJobEntity(UUID id, UUID documentId, int documentVersion) {
        this.id = id;
        this.documentId = documentId;
        this.documentVersion = documentVersion;
        this.status = "PENDING";
        this.attempts = 0;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }

    public void markRunning() {
        this.status = "RUNNING";
        this.attempts += 1;
    }

    public void markCompleted() {
        this.status = "COMPLETED";
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = "FAILED";
        this.error = reason;
        this.completedAt = Instant.now();
    }

    /** Returns a retryable failure to PENDING so the next poll claims it again. */
    public void markRetryable() {
        this.status = "PENDING";
        this.error = null;
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public int getDocumentVersion() { return documentVersion; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
