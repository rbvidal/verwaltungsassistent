package reasoning.document.infrastructure.persistence;

import reasoning.common.model.IngestionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA entity tracking a document ingestion job with status lifecycle and sequenced ordering. */
@Entity
@Table(name = "document_ingestion_jobs")
public class IngestionJobEntity {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionStatus status;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "sequence_number", nullable = false, unique = true)
    private long sequenceNumber;

    protected IngestionJobEntity() {
    }

    public IngestionJobEntity(UUID documentId, String sourceType, String requestedBy, String tenantId, long sequenceNumber) {
        this.id = UUID.randomUUID();
        this.documentId = documentId;
        this.status = IngestionStatus.PENDING;
        this.sourceType = sourceType;
        this.requestedBy = requestedBy;
        this.tenantId = tenantId;
        this.sequenceNumber = sequenceNumber;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Transitions the job from PENDING to RUNNING. */
    public void start() {
        requireStatus(IngestionStatus.PENDING, "Only pending ingestion jobs can be started");
        this.status = IngestionStatus.RUNNING;
    }

    /** Transitions the job from RUNNING to COMPLETED and records the completion timestamp. */
    public void complete() {
        requireStatus(IngestionStatus.RUNNING, "Only running ingestion jobs can be completed");
        this.status = IngestionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** Marks the job as FAILED with a reason, unless already COMPLETED or CANCELLED. */
    public void fail(String reason) {
        if (this.status == IngestionStatus.COMPLETED || this.status == IngestionStatus.CANCELLED) {
            throw new IllegalArgumentException("Completed or cancelled ingestion jobs cannot fail");
        }
        this.status = IngestionStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    private void requireStatus(IngestionStatus expected, String message) {
        if (this.status != expected) {
            throw new IllegalArgumentException(message);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }
}
